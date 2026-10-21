package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.core.ledger.LinkedBillRules
import app.omymoney.core.ledger.LedgerMutationException
import app.omymoney.core.model.*
import app.omymoney.data.backup.BackupArchive
import app.omymoney.data.backup.RestoreMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinkedBillTransactionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private val book = UUID.randomUUID().toString()
    private val cash = UUID.randomUUID().toString()
    private val cny = CurrencySpec.CNY
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        ledger = RoomLedgerRepository(db)
        db.ledgerDao().upsertBook(BookEntity(book, "Linked book"))
        db.ledgerDao().upsertAccount(AccountEntity(cash, "Cash", openingMinor = 20000, balanceMinor = 20000))
    }
    @After fun cleanup() { db.close() }
    private fun draft(kind: BillKind, minor: Long = 10000, source: String? = null) = TransactionDraft(
        bookId = book, kind = kind, amount = Money(minor, cny),
        fromAccountId = cash.takeIf { kind in listOf(BillKind.EXPENSE, BillKind.REIMBURSEMENT) },
        toAccountId = cash.takeIf { source != null }, linkedBillId = source,
        occurredAt = Instant.parse(if (source == null) "2026-01-31T10:00:00Z" else "2026-02-02T10:00:00Z"), timeSource = TimeSource.USER,
    )
    private suspend fun save(draft: TransactionDraft, revision: Long? = null): Bill {
        val result = ledger.save(draft, revision)
        assertTrue("Expected Saved: $result", result is SaveResult.Saved)
        return ledger.observeBill((result as SaveResult.Saved).billId).first()!!
    }

    @Test fun refundEditsDeletesUndoAndBackupPreserveTheSameLinkAndExactBalance() = runBlocking {
        val source = save(draft(BillKind.EXPENSE))
        val request = draft(BillKind.REFUND, 3000, source.id)
        val refund = save(request)
        assertEquals(SaveResult.Saved(refund.id, 1), ledger.save(request, null))
        assertEquals(13000L, db.ledgerDao().account(cash)!!.balanceMinor)
        assertEquals(source.id, refund.linkedBillId)
        assertEquals(2, ledger.observeBills(BillQuery()).first().size)
        val edited = save(refund.toDraft().copy(amount = Money(4000, cny)), refund.revision)
        assertEquals(14000L, db.ledgerDao().account(cash)!!.balanceMinor)
        val token = ledger.delete(edited.id)
        assertEquals(10000L, db.ledgerDao().account(cash)!!.balanceMinor)
        assertTrue(ledger.undo(token)); assertFalse(ledger.undo(token))
        assertEquals(14000L, db.ledgerDao().account(cash)!!.balanceMinor)
        val archive = BackupArchive(db, context)
        val output = ByteArrayOutputStream()
        archive.exportTo(output)
        val preview = archive.inspect(ByteArrayInputStream(output.toByteArray()))
        archive.restore(preview.previewId, RestoreMode.REPLACE)
        assertEquals(source.id, ledger.observeBill(refund.id).first()!!.linkedBillId)
        assertEquals(14000L, db.ledgerDao().account(cash)!!.balanceMinor)
    }

    @Test fun partialReimbursementRejectsOverpaymentSourceMutationAndOrphanDeletion() = runBlocking {
        val source = save(draft(BillKind.REIMBURSEMENT))
        val settlement = save(draft(BillKind.REIMBURSED, 6000, source.id))
        assertEquals(4000L, LinkedBillRules.remainingReimbursement(source, listOf(settlement)).minor)
        assertTrue(ledger.save(draft(BillKind.REIMBURSED, 5000, source.id), null) is SaveResult.Invalid)
        assertTrue(ledger.save(source.toDraft().copy(amount = Money(5000, cny)), 1) is SaveResult.Invalid)
        assertTrue(ledger.save(source.toDraft().copy(kind = BillKind.EXPENSE), 1) is SaveResult.Invalid)
        val error = runCatching { ledger.delete(source.id) }.exceptionOrNull()
        assertTrue(error is LedgerMutationException)
        assertEquals("linked_records", (error as LedgerMutationException).code)
        assertEquals(16000L, db.ledgerDao().account(cash)!!.balanceMinor)
        val childToken = ledger.delete(settlement.id)
        val parentToken = ledger.delete(source.id)
        assertFalse(ledger.undo(childToken))
        assertTrue(ledger.undo(parentToken))
        assertTrue(ledger.undo(childToken))
        assertEquals(16000L, db.ledgerDao().account(cash)!!.balanceMinor)
    }

    @Test fun failedLinkedPostingRollsBackAndNoWrongCurrencyOrMissingSourceIsAccepted() = runBlocking {
        val source = save(draft(BillKind.EXPENSE))
        assertTrue(ledger.save(draft(BillKind.REFUND, 1000, UUID.randomUUID().toString()), null) is SaveResult.Invalid)
        assertTrue(ledger.save(draft(BillKind.REFUND, 1000, source.id).copy(amount = Money(1000, CurrencySpec("USD", 2))), null) is SaveResult.Invalid)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_link BEFORE INSERT ON postings BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertEquals(SaveResult.Failure("storage"), ledger.save(draft(BillKind.REFUND, 1000, source.id), null))
        assertEquals(1, db.ledgerDao().billCount())
        assertEquals(1, db.ledgerDao().submissionCount())
        assertEquals(10000L, db.ledgerDao().account(cash)!!.balanceMinor)
    }

    @Test fun disjointSettlementsCannotMergePastTheSourceButReplacementRemainsAvailable() = runBlocking {
        val source = save(draft(BillKind.REIMBURSEMENT))
        val archive = BackupArchive(db, context)
        val base = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        save(draft(BillKind.REIMBURSED, 6000, source.id))
        val incoming = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        archive.restore(archive.inspect(base.inputStream()).previewId, RestoreMode.REPLACE)
        save(draft(BillKind.REIMBURSED, 6000, source.id))
        val preview = archive.inspect(incoming.inputStream())
        assertTrue("Two distinct 60.00 settlements on a 100.00 source must be flagged before restore", preview.conflicts.isNotEmpty())
        assertTrue(runCatching { archive.restore(preview.previewId, RestoreMode.MERGE) }.isFailure)
        archive.restore(preview.previewId, RestoreMode.REPLACE)
        assertEquals(16000L, db.ledgerDao().account(cash)!!.balanceMinor)
    }
}
