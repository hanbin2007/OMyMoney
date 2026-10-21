package app.omymoney

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.data.*
import app.omymoney.data.exchange.*
import app.omymoney.feature.bills.BillFilters
import app.omymoney.feature.exchange.*
import java.io.File
import java.io.InputStream
import java.time.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ExchangeStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://test.exchange/document")
    private val csv = "occurred_at,kind,amount,currency,from_account,note\n2026-10-11T12:00:00Z,EXPENSE,12.30,CNY,Cash,Good\nbad,EXPENSE,1,CNY,Cash,Bad\n"
    private class Fixture(val context: Context, clock: Clock = Clock.systemUTC()) {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val importer = BillImportService(db, context, clock)
        val ledger = RoomLedgerRepository(db)
        val book = Book(UUID.randomUUID().toString(), "Exchange", zoneId = ZoneOffset.UTC)
        val account = AssetAccount(UUID.randomUUID().toString(), "Cash")
        val file = File(context.cacheDir, "exchange-${UUID.randomUUID()}")
        val models = ViewModelStore()
        suspend fun initialize() { CatalogRepository(db).saveBook(book); CatalogRepository(db).saveAccount(account) }
        suspend fun model(saved: SavedStateHandle = SavedStateHandle(), pipe: ParcelFileDescriptor? = null): ExchangeViewModel = withContext(Dispatchers.Main) {
            val provider = FileProvider(file, pipe).apply { attachInfo(context, ProviderInfo().apply { authority = "test.exchange" }) }
            val model = ExchangeViewModel(importer, BillExportService(db), ledger, ContentResolver.wrap(provider), book, BillFilters(), saved)
            models.put(UUID.randomUUID().toString(), model)
            model
        }
        suspend fun close() { withContext(Dispatchers.Main) { models.clear() }; db.close(); file.delete() }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }
    private suspend fun settled(model: ExchangeViewModel) { await { !model.state.value.busy } }

    @Test fun committedBillsRemainSuccessfulWhenThePreviewExpiresBeforeRefresh(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val now = Instant.parse("2026-10-11T12:00:00Z")
        val reads = java.util.concurrent.atomic.AtomicInteger(-1000)
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = if (reads.incrementAndGet() >= 4) now.plus(Duration.ofDays(2)) else now
        }
        val f = Fixture(context, clock)
        try {
            f.initialize(); f.file.writeText(csv)
            val model = f.model(); settled(model); model.import(uri); settled(model)
            model.selectAll(); reads.set(0)
            model.commit(); settled(model)
            assertEquals(1, f.db.ledgerDao().billCount())
            assertEquals(-1230L, f.db.ledgerDao().account(f.account.id)!!.balanceMinor)
            assertNull("A refresh failure must not say the committed transaction failed", model.state.value.error)
            assertEquals("imported_refresh_failed", model.state.value.notice)
            assertEquals(1, model.state.value.count)
            assertTrue(model.selected.value.isEmpty())
            assertNull(model.state.value.preview)
        } finally { f.close() }
    }

    @Test fun previewSelectionRestoresAndCommitCannotImportAnErrorOrDuplicate(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val f = Fixture(context)
        try {
            f.initialize(); f.file.writeText(csv)
            val saved = SavedStateHandle()
            val model = f.model(saved); settled(model)
            model.import(uri); settled(model)
            assertEquals(2, model.state.value.preview!!.rows.size)
            assertEquals(0, f.db.ledgerDao().billCount())
            model.select(3); assertTrue(model.selected.value.isEmpty())
            model.selectAll(); assertArrayEquals(intArrayOf(2), model.selected.value)
            val restored = f.model(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })); settled(restored)
            assertArrayEquals(intArrayOf(2), restored.selected.value)
            assertEquals(model.state.value.preview, restored.state.value.preview)
            restored.commit(); settled(restored)
            assertNull(restored.state.value.error)
            assertEquals(1, f.db.ledgerDao().billCount())
            assertEquals(-1230L, f.db.ledgerDao().account(f.account.id)!!.balanceMinor)
            assertTrue(restored.state.value.preview!!.rows.first().duplicate)
            restored.selectAll(); assertTrue(restored.selected.value.isEmpty())
            restored.discard(); settled(restored)
            assertNull(restored.state.value.preview)
            assertEquals(1, f.db.ledgerDao().billCount())
        } finally { f.close() }
    }

    @Test fun mappingsAndRawFieldsPersistAndCancelDoesNotWriteBills(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val f = Fixture(context)
        try {
            f.initialize(); f.file.writeText(csv.replace("Cash", "External"))
            val model = f.model(); settled(model); model.import(uri); settled(model)
            val first = model.state.value.preview!!
            assertTrue(first.rows.first().issues.any { it.code == "unavailable_account" })
            model.remap(first.mapping.copy(accounts = mapOf("External" to f.account.id))); settled(model)
            assertTrue(model.state.value.preview!!.rows.first().selectable)
            assertEquals("External", model.state.value.preview!!.rows.first().raw[4])
            model.selectAll(); model.discard(); settled(model)
            assertEquals(0, f.db.ledgerDao().billCount())
            assertTrue(runCatching { f.importer.preview(first.previewId) }.isFailure)
        } finally { f.close() }
    }

    @Test fun eachSafFormatExportsReadableExactFields(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val f = Fixture(context)
        try {
            f.initialize()
            val preview = f.importer.preview(csv.byteInputStream(), ExchangeFormat.CSV, f.book.id)
            f.importer.commit(preview.previewId, ImportChoices(setOf(2)))
            val model = f.model(); settled(model)
            ExchangeFormat.entries.forEach { format ->
                model.chooseFormat(format); model.preparePicker(); model.export(uri); settled(model)
                assertNull(model.state.value.error)
                assertEquals("exported", model.state.value.notice)
                assertEquals(1, model.state.value.count)
                val readback = f.file.inputStream().use { f.importer.preview(it, format, f.book.id) }
                assertEquals("12.30", readback.rows.single().draft!!.amount!!.decimalText())
                assertEquals(f.account.id, readback.rows.single().draft!!.fromAccountId)
                assertEquals("Good", readback.rows.single().draft!!.note)
            }
        } finally { f.close() }
    }

    @Test fun pickerResultsReturnedDuringPreviewReloadAreNotLost(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val f = Fixture(context)
        try {
            f.initialize()
            for (export in listOf(false, true)) {
                f.file.writeText(csv)
                val old = f.importer.preview(csv.byteInputStream(), ExchangeFormat.CSV, f.book.id)
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                val blocked = object : InputStream() {
                    override fun read(): Int { entered.countDown(); release.await(); return -1 }
                    override fun close() { release.countDown() }
                }
                val blocker = launch(Dispatchers.IO) { runCatching { f.importer.preview(blocked, ExchangeFormat.CSV, f.book.id) } }
                try {
                    await { entered.count == 0L }
                    val model = f.model(SavedStateHandle(mapOf(ExchangeViewModel.PREVIEW_KEY to old.previewId)))
                    assertTrue(model.state.value.busy)
                    model.preparePicker()
                    if (export) model.export(uri) else model.import(uri)
                    release.countDown(); blocker.join(); settled(model)
                    if (export) assertEquals("exported", model.state.value.notice)
                    else assertNotEquals(old.previewId, model.state.value.preview?.previewId)
                    assertNull(model.state.value.error)
                } finally { blocked.close(); blocker.cancelAndJoin() }
            }
        } finally { f.close() }
    }

    @Test fun blockedDocumentCanBeCancelledAndAnotherFileSelected(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val f = Fixture(context)
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            f.initialize()
            val model = f.model(pipe = pipe[0]); settled(model)
            model.import(uri); delay(200)
            assertTrue(model.state.value.busy)
            model.cancelOperation()
            withTimeout(2000) { while (model.state.value.busy) delay(10) }
            assertEquals("cancelled", model.state.value.notice)
            assertEquals(0, f.db.ledgerDao().billCount())
            f.file.writeText(csv)
            val next = f.model(); settled(next); next.import(uri); settled(next)
            assertNotNull(next.state.value.preview)
        } finally { pipe.forEach { runCatching { it.close() } }; f.close() }
    }

    private class FileProvider(private val file: File, private val pipe: ParcelFileDescriptor?) : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = pipe ?: ParcelFileDescriptor.open(file,
            if ('w' in mode) ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE else ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
