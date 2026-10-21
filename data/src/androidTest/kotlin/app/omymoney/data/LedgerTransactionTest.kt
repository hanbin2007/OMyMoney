package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerTransactionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: LedgerDatabase
    private lateinit var repository: RoomLedgerRepository
    private val now = Instant.parse("2026-10-11T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val currency = CurrencySpec.CNY
    private val book = UUID.randomUUID().toString()
    private val cash = UUID.randomUUID().toString()
    private val bank = UUID.randomUUID().toString()
    private val category = UUID.randomUUID().toString()
    private var fileName: String? = null

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        repository = RoomLedgerRepository(db, clock)
        seed()
    }

    @After fun cleanup() {
        db.close()
        fileName?.let(context::deleteDatabase)
    }

    private suspend fun seed() {
        db.ledgerDao().upsertBook(BookEntity(book, "日常账本"))
        db.ledgerDao().upsertAccount(AccountEntity(cash, "现金", openingMinor = 10000, balanceMinor = 10000))
        db.ledgerDao().upsertAccount(AccountEntity(bank, "银行卡", openingMinor = 5000, balanceMinor = 5000))
        db.ledgerDao().upsertCategory(CategoryEntity(category, book, "餐饮", BillKind.EXPENSE.name))
    }

    private fun draft() = TransactionDraft(
        bookId = book, kind = BillKind.EXPENSE, amount = Money(1230, currency),
        fromAccountId = cash, categoryId = category, occurredAt = now,
        timeSource = TimeSource.USER, zoneId = ZoneId.of("Asia/Shanghai"),
    )

    private fun saved(result: SaveResult): SaveResult.Saved {
        assertTrue("Expected Saved, got $result", result is SaveResult.Saved)
        return result as SaveResult.Saved
    }

    @Test fun duplicateSubmissionDoesNotDoubleDebit() = runBlocking {
        val request = draft()
        val first = saved(repository.save(request, null))
        assertEquals(first, repository.save(request, null))
        assertEquals(1, db.ledgerDao().billCount())
        assertEquals(1, db.ledgerDao().postingCount())
        assertEquals(8770L, db.ledgerDao().account(cash)!!.balanceMinor)
    }

    @Test fun reusedSubmissionWithDifferentContentIsRejected() = runBlocking {
        val request = draft()
        saved(repository.save(request, null))
        val result = repository.save(request.copy(amount = Money(2000, currency)), null)
        assertTrue(result is SaveResult.Invalid)
        assertEquals(8770L, db.ledgerDao().account(cash)!!.balanceMinor)
        assertEquals(1, db.ledgerDao().billCount())
    }

    @Test fun staleRevisionCannotOverwriteCurrentBill() = runBlocking {
        val first = saved(repository.save(draft(), null))
        val edit = draft().copy(id = first.billId, amount = Money(2000, currency))
        assertEquals(2L, saved(repository.save(edit, first.revision)).revision)
        val stale = repository.save(edit.copy(submissionId = UUID.randomUUID().toString(), amount = Money(4000, currency)), 1)
        assertEquals(SaveResult.Conflict(2), stale)
        assertEquals(8000L, db.ledgerDao().account(cash)!!.balanceMinor)
    }

    @Test fun editingReplacesPriorPostingsInsteadOfAccumulatingThem() = runBlocking {
        val first = saved(repository.save(draft(), null))
        val second = saved(repository.save(draft().copy(id = first.billId, amount = Money(2000, currency)), 1))
        assertEquals(8000L, db.ledgerDao().account(cash)!!.balanceMinor)
        assertEquals(1, db.ledgerDao().postingCount())
        saved(repository.save(draft().copy(id = first.billId, kind = BillKind.TRANSFER,
            amount = Money(3000, currency), toAccountId = bank, categoryId = null), second.revision))
        assertEquals(7000L, db.ledgerDao().account(cash)!!.balanceMinor)
        assertEquals(8000L, db.ledgerDao().account(bank)!!.balanceMinor)
        assertEquals(1, db.ledgerDao().billCount())
        assertEquals(2, db.ledgerDao().postingCount())
    }

    @Test fun failureAfterFirstPostingRollsBackBillBalancesAndSubmission() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_bank_posting BEFORE INSERT ON postings
            WHEN NEW.accountId = '$bank' BEGIN SELECT RAISE(ABORT, 'test storage failure'); END
        """.trimIndent())
        val result = repository.save(draft().copy(kind = BillKind.TRANSFER, toAccountId = bank, categoryId = null), null)
        assertEquals(SaveResult.Failure("storage"), result)
        assertEquals(0, db.ledgerDao().billCount())
        assertEquals(0, db.ledgerDao().postingCount())
        assertEquals(0, db.ledgerDao().submissionCount())
        assertEquals(10000L, db.ledgerDao().account(cash)!!.balanceMinor)
        assertEquals(5000L, db.ledgerDao().account(bank)!!.balanceMinor)
    }

    @Test fun deleteAndUndoEachApplyTheirBalanceChangeOnlyOnce() = runBlocking {
        val first = saved(repository.save(draft(), null))
        val token = repository.delete(first.billId)
        assertEquals(token, repository.delete(first.billId))
        assertEquals(10000L, db.ledgerDao().account(cash)!!.balanceMinor)
        assertTrue(repository.observeBills(BillQuery(bookId = book)).first().isEmpty())
        assertTrue(repository.undo(token))
        assertFalse(repository.undo(token))
        assertEquals(8770L, db.ledgerDao().account(cash)!!.balanceMinor)
        val restored = repository.observeBills(BillQuery(bookId = book)).first().single()
        assertEquals(first.billId, restored.id)
        assertEquals(3L, restored.revision)
    }

    @Test fun tamperedAndExpiredUndoTokensCannotChangeData() = runBlocking {
        val first = saved(repository.save(draft(), null))
        val token = repository.delete(first.billId)
        assertFalse(repository.undo(token.copy(billId = "not-the-bill")))
        assertFalse(repository.undo(token.copy(expiresAt = token.expiresAt.plusSeconds(3600))))
        val later = RoomLedgerRepository(db, Clock.fixed(token.expiresAt.plusSeconds(1), ZoneOffset.UTC))
        assertFalse(later.undo(token))
        assertEquals(10000L, db.ledgerDao().account(cash)!!.balanceMinor)
    }

    @Test fun concurrentDuplicateSavesRemainIdempotent() = runBlocking {
        val request = draft()
        val results = List(12) { async(Dispatchers.IO) { repository.save(request, null) } }.awaitAll()
        val first = saved(results.first())
        assertTrue(results.all { it == first })
        assertEquals(1, db.ledgerDao().billCount())
        assertEquals(8770L, db.ledgerDao().account(cash)!!.balanceMinor)
    }

    @Test fun unknownAndWrongCurrencyAccountsAreRejectedWithoutWrites() = runBlocking {
        assertTrue(repository.save(draft().copy(fromAccountId = "missing"), null) is SaveResult.Invalid)
        assertTrue(repository.save(draft().copy(amount = Money(100, CurrencySpec("USD", 2))), null) is SaveResult.Invalid)
        assertEquals(0, db.ledgerDao().billCount())
        assertEquals(10000L, db.ledgerDao().account(cash)!!.balanceMinor)
    }

    @Test fun overflowingAccountBalanceRollsBack() = runBlocking {
        db.ledgerDao().updateBalance(bank, Long.MAX_VALUE)
        val income = draft().copy(kind = BillKind.INCOME, amount = Money(1, currency),
            fromAccountId = null, toAccountId = bank, categoryId = null)
        assertTrue(repository.save(income, null) is SaveResult.Invalid)
        assertEquals(Long.MAX_VALUE, db.ledgerDao().account(bank)!!.balanceMinor)
        assertEquals(0, db.ledgerDao().billCount())
    }

    @Test fun usedAccountsAreArchivedInsteadOfDestroyingHistory() = runBlocking {
        saved(repository.save(draft(), null))
        db.ledgerDao().retireAccount(cash)
        assertTrue(db.ledgerDao().account(cash)!!.archived)
        assertEquals(1, db.ledgerDao().billCount())
        assertEquals(8770L, db.ledgerDao().account(cash)!!.balanceMinor)
    }

    @Test fun fileDatabaseReopenPreservesAmountsRevisionAndMetadata() = runBlocking {
        db.close()
        fileName = "ledger-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, fileName!!).build()
        repository = RoomLedgerRepository(db, clock)
        seed()
        val request = draft().copy(note = "晚饭", tags = listOf("出差", "可报销"),
            occurredAt = now.plusNanos(123456789), excludeBudget = true)
        val first = saved(repository.save(request, null))
        db.close()
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, fileName!!).build()
        repository = RoomLedgerRepository(db, clock)
        val bill = repository.observeBills(BillQuery(bookId = book)).first().single()
        assertEquals(first.billId, bill.id)
        assertEquals(1L, bill.revision)
        assertEquals(1230L, bill.amount.minor)
        assertEquals(request.occurredAt, bill.occurredAt)
        assertEquals(request.tags, bill.tags)
        assertEquals("晚饭", bill.note)
        assertTrue(bill.excludeBudget)
        assertEquals(8770L, db.ledgerDao().account(cash)!!.balanceMinor)
    }
}
