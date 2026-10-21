package app.omymoney

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.data.*
import app.omymoney.data.backup.*
import app.omymoney.feature.settings.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class BackupStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Test fun missingSavedPreviewDoesNotPreventSelectingAnotherBackup(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val file = File(context.cacheDir, "backup-state-${UUID.randomUUID()}.zip")
        try {
            CatalogRepository(db).ensureLocalDefaults()
            val archive = BackupArchive(db, context)
            file.outputStream().use { archive.exportTo(it) }
            val state = SavedStateHandle(mapOf(BackupViewModel.PREVIEW_KEY to UUID.randomUUID().toString()))
            val provider = FileProvider(file).apply { attachInfo(context, ProviderInfo().apply { authority = "test.backup" }) }
            val model = BackupViewModel(archive, ContentResolver.wrap(provider), state)
            await { !model.state.value.busy }
            assertEquals("invalid_preview", model.state.value.error)
            model.inspect(Uri.parse("content://test.backup/archive"))
            await { !model.state.value.busy }
            assertNotNull("A missing prior preview must not block a fresh selection", model.state.value.preview)
            assertNull(model.state.value.error)
            val id = model.state.value.preview!!.previewId
            archive.cancel(id)
            model.cancelPreview()
            await { !model.state.value.busy }
            assertNull("Cancelling an already removed preview must still release UI state", model.state.value.preview)
            assertNull(state.get<String>(BackupViewModel.PREVIEW_KEY))
        } finally { db.close(); file.delete() }
    }
    private suspend fun await(condition: () -> Boolean) { withTimeout(5000) { while (!condition()) delay(10) } }
    @Test fun importResultArrivingDuringInitializationIsNotLost(): Unit = runBlocking {
        duringInitialization(export = false)
    }
    @Test fun exportResultArrivingDuringInitializationIsNotLost(): Unit = runBlocking {
        duringInitialization(export = true)
    }
    private suspend fun duringInitialization(export: Boolean) = coroutineScope {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val file = File(context.cacheDir, "pending-backup-${UUID.randomUUID()}.zip")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = object : InputStream() {
            override fun read(): Int { entered.countDown(); release.await(); return -1 }
            override fun close() { release.countDown() }
        }
        var blocker: Job? = null
        try {
            CatalogRepository(db).ensureLocalDefaults()
            val archive = BackupArchive(db, context)
            val bytes = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
            val old = archive.inspect(bytes.inputStream())
            file.writeBytes(if (export) byteArrayOf() else bytes)
            val provider = FileProvider(file).apply { attachInfo(context, ProviderInfo().apply { authority = "test.backup" }) }
            blocker = launch(Dispatchers.IO) { runCatching { archive.inspect(blocked) } }
            await { entered.count == 0L }
            val model = BackupViewModel(archive, ContentResolver.wrap(provider), SavedStateHandle(mapOf(BackupViewModel.PREVIEW_KEY to old.previewId)))
            assertTrue(model.state.value.busy)
            if (export) { model.prepareExport(false); model.export(Uri.parse("content://test.backup/archive")) }
            else model.inspect(Uri.parse("content://test.backup/archive"))
            release.countDown()
            blocker.join()
            await { !model.state.value.busy }
            if (export) assertTrue("Returned export URI must receive an archive", file.length() > 0)
            else assertNotEquals("Returned import URI must replace the old preview", old.previewId, model.state.value.preview?.previewId)
        } finally { blocked.close(); blocker?.cancelAndJoin(); db.close(); file.delete() }
    }
    @Test fun slowImportCanBeCancelledWithoutChangingTheLedger(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            CatalogRepository(db).ensureLocalDefaults()
            val provider = FileProvider(null, pipe[0]).apply { attachInfo(context, ProviderInfo().apply { authority = "test.backup" }) }
            val model = BackupViewModel(BackupArchive(db, context), ContentResolver.wrap(provider), SavedStateHandle())
            await { !model.state.value.busy }
            model.inspect(Uri.parse("content://test.backup/slow"))
            delay(200)
            assertTrue(model.state.value.busy)
            model.cancelOperation()
            withTimeout(2000) { while (model.state.value.busy) delay(10) }
            assertNull(model.state.value.preview)
            assertEquals(0, db.ledgerDao().billCount())
        } finally { pipe.forEach { runCatching { it.close() } }; db.close() }
    }
    private class FileProvider(private val file: File?, private val pipe: ParcelFileDescriptor? = null) : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = pipe ?: ParcelFileDescriptor.open(requireNotNull(file),
            if ('w' in mode) ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE else ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = "application/zip"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
