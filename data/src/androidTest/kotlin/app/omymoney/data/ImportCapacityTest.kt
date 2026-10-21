package app.omymoney.data

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.data.backup.BackupArchive
import app.omymoney.data.exchange.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportCapacityTest {
    @Test fun oneThousandRowsRemainExactAndPortableWithRecordedRuntime() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val catalog = CatalogRepository(database)
            val book = Book(UUID.randomUUID().toString(), "Capacity")
            val account = AssetAccount(UUID.randomUUID().toString(), "Capacity cash")
            assertTrue(catalog.saveBook(book) is CatalogResult.Saved)
            assertTrue(catalog.saveAccount(account) is CatalogResult.Saved)
            val table = ExchangeTable(listOf("occurred_at", "kind", "amount", "currency", "from_account", "note"),
                List(1000) { index -> TableRow(index + 2, listOf("2024-01-15T12:00:00Z", "EXPENSE", "12.30", "CNY", account.name, "Row $index")) })
            val input = ByteArrayOutputStream().also { TableCodec.write(table, ExchangeFormat.CSV, it) }.toByteArray()
            val importer = BillImportService(database, context)
            val started = SystemClock.elapsedRealtime()
            val preview = importer.preview(input.inputStream(), ExchangeFormat.CSV, book.id)
            val previewAt = SystemClock.elapsedRealtime()
            assertEquals(1000, preview.rows.count { it.selectable })
            assertEquals(1000, importer.commit(preview.previewId, ImportChoices(preview.rows.map { it.number }.toSet())).imported)
            val committedAt = SystemClock.elapsedRealtime()
            assertEquals(-1_230_000L, database.ledgerDao().account(account.id)!!.balanceMinor)
            val archive = BackupArchive(database, context)
            val bytes = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
            assertEquals(1000, archive.inspect(bytes.inputStream()).summary.bills)
            assertEquals(1000, importer.preview(input.inputStream(), ExchangeFormat.CSV, book.id).rows.count { it.duplicate })
            println("CAPACITY rows=1000 previewMs=${previewAt - started} commitMs=${committedAt - previewAt} inputBytes=${input.size} backupBytes=${bytes.size}")
        } finally { database.close() }
    }
}
