package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.ledger.ExchangeRules
import app.omymoney.core.model.*
import app.omymoney.data.backup.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExchangeHistoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var catalog: CatalogRepository
    private val book = UUID.randomUUID().toString()
    private val usdId = UUID.randomUUID().toString()
    private val cnyId = UUID.randomUUID().toString()
    private val usd = CurrencySpec("USD", 2)
    private val cny = CurrencySpec.CNY
    private val date = LocalDate.of(2026, 10, 11)
    private val time = Instant.parse("2026-10-11T00:00:00Z")
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        ledger = RoomLedgerRepository(db); catalog = CatalogRepository(db)
        catalog.saveBook(Book(book, "FX"))
        catalog.saveAccount(AssetAccount(usdId, "USD", usd, Money(10000, usd)))
        assertTrue(catalog.saveAccount(AssetAccount(cnyId, "CNY")) is CatalogResult.Saved)
    }
    @After fun cleanup() { db.close() }
    private fun draft() = TransactionDraft(book, kind = BillKind.TRANSFER, amount = Money(1000, usd),
        fromAccountId = usdId, toAccountId = cnyId, occurredAt = time, timeSource = TimeSource.USER,
        exchange = ExchangeSnapshot(cnyId, Money(7200, cny), "7.2", date), fee = Money(10, usd))
    private suspend fun save(draft: TransactionDraft, revision: Long? = null): Bill {
        val result = ledger.save(draft, revision)
        assertTrue("Expected Saved: $result", result is SaveResult.Saved)
        return ledger.observeBill((result as SaveResult.Saved).billId).first()!!
    }

    @Test fun quotesDoNotRewriteSavedAmountsAndHistoryRebuildsEditDeleteUndo() = runBlocking {
        val history = AssetHistoryRepository(db)
        val rate = ManualExchangeRate(usd, cny, "7.2", date)
        assertTrue(catalog.saveExchangeRate(rate) is CatalogResult.Saved)
        val bill = save(draft())
        assertEquals(draft().exchange, bill.exchange)
        assertEquals(8990L, db.ledgerDao().account(usdId)!!.balanceMinor)
        assertEquals(7200L, db.ledgerDao().account(cnyId)!!.balanceMinor)
        assertTrue(catalog.saveExchangeRate(rate.copy(rate = "8", asOf = date.plusDays(1))) is CatalogResult.Saved)
        assertEquals(Money(7200, cny), ledger.observeBill(bill.id).first()!!.exchange!!.amount)
        assertEquals(catalog.observeAccounts(true).first(), history.snapshot())
        assertEquals(Money(0, cny), history.snapshot(time.minusNanos(1)).single { it.id == cnyId }.balance)
        assertEquals(listOf(Money(10000, usd), Money(8990, usd)), history.history(usdId, ZoneOffset.UTC).map { it.balance })
        val edited = save(bill.toDraft().copy(exchange = bill.exchange!!.copy(amount = Money(7199, cny))), bill.revision)
        assertEquals(Money(-1, cny), ExchangeRules.difference(edited.amount, edited.exchange!!))
        assertEquals(7199L, db.ledgerDao().account(cnyId)!!.balanceMinor)
        val deleted = ledger.delete(edited.id)
        assertEquals(1, history.history(usdId, ZoneOffset.UTC).size)
        assertEquals(Money(10000, usd), history.snapshot().single { it.id == usdId }.balance)
        assertTrue(ledger.undo(deleted))
        assertEquals(catalog.observeAccounts(true).first(), history.snapshot())
    }

    @Test fun rateSnapshotBackupAndConflictsAreExplicitAndLossless() = runBlocking {
        val rate = ManualExchangeRate(usd, cny, "7.2", date)
        catalog.saveExchangeRate(rate)
        val bill = save(draft())
        val archive = BackupArchive(db, context)
        val bytes = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        catalog.saveExchangeRate(rate.copy(rate = "8"))
        val preview = archive.inspect(bytes.inputStream())
        assertTrue(preview.conflicts.any { it.kind.name == "EXCHANGE_RATE" })
        assertTrue(runCatching { archive.restore(preview.previewId, RestoreMode.MERGE) }.isFailure)
        archive.restore(preview.previewId, RestoreMode.REPLACE)
        assertEquals(listOf(rate), catalog.observeExchangeRates().first())
        assertEquals(bill, ledger.observeBill(bill.id).first())
        assertEquals(catalog.observeAccounts(true).first(), AssetHistoryRepository(db).snapshot())
        assertTrue(catalog.removeExchangeRate(usd, cny) is CatalogResult.Saved)
        assertTrue(catalog.observeExchangeRates().first().isEmpty())
        assertNull(ExchangeRules.value(catalog.observeAccounts().first().map { it.balance }, cny, emptyList()).total)
    }

    @Test fun absentOrWrongCurrencySnapshotAndFailedSecondPostingLeaveNoPartialBalance() = runBlocking {
        assertTrue(ledger.save(draft().copy(exchange = null), null) is SaveResult.Invalid)
        assertTrue(ledger.save(draft().copy(exchange = draft().exchange!!.copy(amount = Money(7200, CurrencySpec("EUR", 2)))), null) is SaveResult.Invalid)
        assertTrue(catalog.saveExchangeRate(ManualExchangeRate(usd, usd, "1", date)) is CatalogResult.Invalid)
        assertTrue(catalog.saveExchangeRate(ManualExchangeRate(usd, cny, "0", date)) is CatalogResult.Invalid)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_exchange BEFORE INSERT ON postings WHEN NEW.position=1 BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertEquals(SaveResult.Failure("storage"), ledger.save(draft(), null))
        assertEquals(10000L, db.ledgerDao().account(usdId)!!.balanceMinor)
        assertEquals(0L, db.ledgerDao().account(cnyId)!!.balanceMinor)
        assertEquals(0, db.ledgerDao().billCount())
    }

    @Test fun preExchangeJsonAndRequestFingerprintsRemainByteCompatible() {
        val ordinary = draft().copy(exchange = null, fee = null, toAccountId = null, kind = BillKind.EXPENSE)
        val encoded = LedgerJson.format.encodeToString(TransactionDraft.serializer(), ordinary)
        assertFalse(encoded.contains("exchange"))
        val bill = ordinary.recorded(UUID.randomUUID().toString(), 1, time)
        assertFalse(LedgerJson.encodeBill(bill).contains("exchange"))
    }

    @Test fun dailyMovementMayExceedLongWhileEveryRecordedBalanceRemainsValid() = runBlocking {
        val id = UUID.randomUUID().toString()
        assertTrue(catalog.saveAccount(AssetAccount(id, "Large movement", usd, Money(Long.MIN_VALUE, usd))) is CatalogResult.Saved)
        val income = TransactionDraft(book, kind = BillKind.INCOME, amount = Money(Long.MAX_VALUE, usd),
            toAccountId = id, occurredAt = time, timeSource = TimeSource.USER)
        save(income)
        save(income.copy(submissionId = UUID.randomUUID().toString()))
        val points = AssetHistoryRepository(db).history(id, ZoneOffset.UTC)
        assertEquals(Money(Long.MAX_VALUE - 1, usd), points.last().balance)
        assertEquals(java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.valueOf(2)), points.last().change)
    }
}
