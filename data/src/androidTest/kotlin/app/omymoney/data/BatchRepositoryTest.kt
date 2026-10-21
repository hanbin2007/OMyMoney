package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.data.backup.BackupArchive
import app.omymoney.data.backup.RestoreMode
import java.io.ByteArrayOutputStream
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BatchRepositoryTest {
    private lateinit var db: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var batch: BatchActions
    private lateinit var book: Book
    private lateinit var cash: AssetAccount
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LedgerDatabase::class.java).build()
        ledger = RoomLedgerRepository(db); batch = BatchActions(db)
        book = Book(UUID.randomUUID().toString(), "Batch")
        cash = AssetAccount(UUID.randomUUID().toString(), "Cash")
        CatalogRepository(db).saveBook(book); CatalogRepository(db).saveAccount(cash)
        Unit
    }
    @After fun close() { db.close() }
    private fun draft(minor: Long, kind: BillKind = BillKind.EXPENSE) = TransactionDraft(book.id, kind = kind,
        amount = Money(minor, CurrencySpec.CNY), fromAccountId = cash.id.takeUnless { kind == BillKind.INCOME || kind == BillKind.REIMBURSED },
        toAccountId = cash.id.takeIf { kind == BillKind.INCOME || kind == BillKind.REIMBURSED },
        occurredAt = Instant.parse("2026-10-11T12:00:00Z"), timeSource = TimeSource.USER)
    private suspend fun save(draft: TransactionDraft): Bill {
        val result = ledger.save(draft, null)
        assertTrue(result.toString(), result is SaveResult.Saved)
        return ledger.observeBill((result as SaveResult.Saved).billId).first()!!
    }
    @Test fun bulkPostingUsesFinalNetBalanceAndIsIdempotent() = runBlocking {
        save(draft(Long.MAX_VALUE, BillKind.INCOME))
        val rows = listOf(draft(10, BillKind.INCOME) to null, draft(10) to null)
        val result = ledger.saveAll(rows)
        assertEquals(2, result.size)
        assertEquals(Long.MAX_VALUE, db.ledgerDao().account(cash.id)!!.balanceMinor)
        assertEquals(result, ledger.saveAll(rows))
        assertEquals(3, db.ledgerDao().billCount())
    }
    @Test fun invalidOrStorageFailedLastRowRollsBackAllEarlierRows() = runBlocking {
        val good = draft(100)
        assertTrue(runCatching { ledger.saveAll(listOf(good to null, draft(1).copy(fromAccountId = "missing") to null)) }.isFailure)
        assertEquals(0, db.ledgerDao().billCount())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_last BEFORE INSERT ON bills WHEN NEW.note='reject' BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { ledger.saveAll(listOf(good to null, draft(2).copy(note = "reject") to null)) }.isFailure)
        assertEquals(0L, db.ledgerDao().account(cash.id)!!.balanceMinor)
        assertEquals(0, db.ledgerDao().submissionCount())
    }
    @Test fun bulkEditsValidateTheProposedGraphNotIntermediateOrder() = runBlocking {
        val root = save(draft(10000, BillKind.REIMBURSEMENT))
        val child = save(draft(9000, BillKind.REIMBURSED).copy(linkedBillId = root.id))
        val result = ledger.saveAll(listOf(root.toDraft().copy(amount = Money(5000, CurrencySpec.CNY)) to root.revision,
            child.toDraft().copy(amount = Money(4000, CurrencySpec.CNY)) to child.revision))
        assertEquals(2, result.size)
        assertEquals(-1000L, db.ledgerDao().account(cash.id)!!.balanceMinor)
    }
    @Test fun metadataBatchAndPersistentUndoAreAtomicAndRejectStaleSelection() = runBlocking {
        val first = save(draft(100)); val second = save(draft(200))
        val expected = listOf(first, second).associate { it.id to it.revision }
        val action = batch.edit(expected, BatchPatch(note = "Changed", tags = listOf("Batch"), excludeStats = true))
        assertEquals(listOf("Changed", "Changed"), ledger.observeBills(BillQuery()).first().map { it.note })
        assertEquals(-300L, db.ledgerDao().account(cash.id)!!.balanceMinor)
        assertTrue(runCatching { batch.edit(expected, BatchPatch(note = "Stale")) }.isFailure)
        assertTrue(BatchActions(db).undo(action.id))
        assertFalse(batch.undo(action.id))
        assertTrue(ledger.observeBills(BillQuery()).first().all { it.note.isEmpty() && !it.excludeStats && it.tags.isEmpty() })
    }
    @Test fun aChangedMemberBlocksTheEntireUndo() = runBlocking {
        val first = save(draft(100)); val second = save(draft(200))
        val action = batch.edit(mapOf(first.id to first.revision, second.id to second.revision), BatchPatch(note = "Batch"))
        val updated = ledger.observeBill(first.id).first()!!
        assertTrue(ledger.save(updated.toDraft().copy(note = "Later"), updated.revision) is SaveResult.Saved)
        assertFalse(batch.undo(action.id))
        assertEquals("Batch", ledger.observeBill(second.id).first()!!.note)
    }
    @Test fun selectedLinkedGroupDeletesAndRestoresAsOneTransaction() = runBlocking {
        val root = save(draft(10000, BillKind.REIMBURSEMENT))
        val child = save(draft(9000, BillKind.REIMBURSED).copy(linkedBillId = root.id))
        assertTrue(runCatching { batch.delete(mapOf(root.id to root.revision)) }.isFailure)
        val action = batch.delete(mapOf(root.id to root.revision, child.id to child.revision))
        assertTrue(ledger.observeBills(BillQuery()).first().isEmpty())
        assertEquals(0L, db.ledgerDao().account(cash.id)!!.balanceMinor)
        assertTrue(batch.undo(action.id))
        assertEquals(2, ledger.observeBills(BillQuery()).first().size)
        assertEquals(-1000L, db.ledgerDao().account(cash.id)!!.balanceMinor)
    }

    @Test fun retainedBatchCategoriesCannotChangeKindAndBreakBackupAfterExpiryOrConsumption() = runBlocking {
        val catalog = CatalogRepository(db)
        for (consumed in listOf(false, true)) {
            val original = Category(UUID.randomUUID().toString(), book.id, "Original $consumed", BillKind.EXPENSE)
            val target = original.copy(id = UUID.randomUUID().toString(), name = "Target $consumed")
            assertTrue(catalog.saveCategory(original) is CatalogResult.Saved)
            assertTrue(catalog.saveCategory(target) is CatalogResult.Saved)
            val bill = save(draft(100).copy(categoryId = original.id))
            val historicalBatch = BatchActions(db, Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC))
            val action = historicalBatch.edit(mapOf(bill.id to bill.revision), BatchPatch(changeCategory = true, categoryId = target.id))
            if (consumed) {
                assertTrue(historicalBatch.undo(action.id))
                val current = ledger.observeBill(bill.id).first()!!
                assertTrue(ledger.save(current.toDraft().copy(categoryId = target.id), current.revision) is SaveResult.Saved)
            }
            assertFalse(batch.undo(action.id))
            val changed = catalog.saveCategory(original.copy(kind = BillKind.INCOME))
            assertBackupCanRoundTrip()
            assertTrue("Retained historical categories must remain compatible: $changed", changed is CatalogResult.Invalid)
            assertTrue(catalog.saveCategory(original.copy(name = "Renamed $consumed")) is CatalogResult.Saved)
        }
    }

    @Test fun historicalBatchAccountsKeepCurrencyKindAndOpeningProtectionAfterBillsMoveAway() = runBlocking {
        val catalog = CatalogRepository(db)
        val target = AssetAccount(UUID.randomUUID().toString(), "Other account")
        assertTrue(catalog.saveAccount(target) is CatalogResult.Saved)
        val bill = save(draft(100))
        BatchActions(db, Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC))
            .edit(mapOf(bill.id to bill.revision), BatchPatch(note = "Historical account"))
        val current = ledger.observeBill(bill.id).first()!!
        assertTrue(ledger.save(current.toDraft().copy(fromAccountId = target.id), current.revision) is SaveResult.Saved)
        val usd = CurrencySpec("USD", 2)
        for (changed in listOf(cash.copy(currency = usd, openingBalance = Money(0, usd), balance = Money(0, usd)),
            cash.copy(kind = AccountKind.LIABILITY), cash.copy(openingBalance = Money(1, CurrencySpec.CNY)))) {
            val result = catalog.saveAccount(changed)
            assertBackupCanRoundTrip()
            assertTrue("Historical account change must be refused: $result", result is CatalogResult.Invalid)
        }
        assertTrue(catalog.saveAccount(cash.copy(name = "Renamed cash")) is CatalogResult.Saved)
    }

    private suspend fun assertBackupCanRoundTrip() {
        val archive = BackupArchive(db, ApplicationProvider.getApplicationContext<Context>())
        val before = ledger.observeBills(BillQuery()).first()
        val bytes = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        val preview = archive.inspect(bytes.inputStream())
        archive.restore(preview.previewId, RestoreMode.REPLACE)
        assertEquals(before, ledger.observeBills(BillQuery()).first())
    }
}
