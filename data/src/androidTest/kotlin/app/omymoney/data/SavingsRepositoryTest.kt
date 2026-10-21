package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.core.savings.*
import app.omymoney.data.backup.*
import java.io.ByteArrayOutputStream
import java.time.*
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SavingsRepositoryTest {
    private lateinit var database: LedgerDatabase
    private lateinit var catalog: CatalogRepository
    private lateinit var savings: SavingsRepository
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var book: Book
    private lateinit var cash: AssetAccount
    private lateinit var bank: AssetAccount
    private val date = LocalDate.parse("2024-01-31")
    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LedgerDatabase::class.java).build()
        catalog = CatalogRepository(database); savings = SavingsRepository(database); ledger = RoomLedgerRepository(database)
        book = Book(UUID.randomUUID().toString(), "Savings", zoneId = ZoneOffset.UTC)
        cash = AssetAccount(UUID.randomUUID().toString(), "Cash")
        bank = AssetAccount(UUID.randomUUID().toString(), "Bank")
        assertTrue(catalog.saveBook(book) is CatalogResult.Saved)
        assertTrue(catalog.saveAccount(cash) is CatalogResult.Saved)
        assertTrue(catalog.saveAccount(bank) is CatalogResult.Saved)
    }
    @After fun close() { database.close() }
    private fun plan(name: String = "Travel") = SavingsPlan(bookId = book.id, name = name, zone = book.zoneId,
        rule = SavingsRule(SavingsMode.CUSTOM, Money(10000, CurrencySpec.CNY), date))
    private fun <T> saved(result: SavingsResult<T>): T { assertTrue(result.toString(), result is SavingsResult.Saved); return (result as SavingsResult.Saved).value }
    private suspend fun transfer(): Bill {
        val result = ledger.save(TransactionDraft(book.id, kind = BillKind.TRANSFER, amount = Money(6000, CurrencySpec.CNY),
            fromAccountId = cash.id, toAccountId = bank.id, occurredAt = date.atStartOfDay().toInstant(ZoneOffset.UTC), timeSource = TimeSource.USER), null)
        assertTrue(result.toString(), result is SaveResult.Saved)
        return requireNotNull(ledger.observeBill((result as SaveResult.Saved).billId).first())
    }
    private suspend fun progress(p: SavingsPlan) = SavingsCalculator.progress(p, savings.observeDeposits(p.id).first(),
        ledger.observeBills(BillQuery(bookId = book.id)).first().associateBy { it.id })

    @Test fun manualRecordEditAndUndoOnlyChangeProgress() = runBlocking {
        val p = saved(savings.savePlan(plan()))
        val d = saved(savings.saveDeposit(SavingsDeposit(planId = p.id, date = date, amount = Money(6000, CurrencySpec.CNY))))
        assertEquals("6000", progress(p).savedMinor.toString())
        val changed = saved(savings.saveDeposit(d.copy(amount = Money(11000, CurrencySpec.CNY))))
        assertTrue(progress(p).completed)
        assertTrue(savings.saveDeposit(d.copy(note = "Stale")) is SavingsResult.Conflict)
        assertTrue(savings.removeDeposit(d.id, d.revision) is SavingsResult.Conflict)
        saved(savings.removeDeposit(changed.id, changed.revision))
        assertEquals("0", progress(p).savedMinor.toString())
        assertEquals(0, database.ledgerDao().billCount())
        assertEquals(0L, database.ledgerDao().account(cash.id)!!.balanceMinor)
    }

    @Test fun oneTransferCannotBeCountedTwiceEvenAcrossPlansAndCompetingWriters() = runBlocking {
        val p = saved(savings.savePlan(plan())); val other = saved(savings.savePlan(plan("Other"))); val bill = transfer()
        val first = async { savings.saveDeposit(SavingsDeposit(planId = p.id, date = date, linkedBillId = bill.id)) }
        val second = async { savings.saveDeposit(SavingsDeposit(planId = other.id, date = date, linkedBillId = bill.id)) }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it is SavingsResult.Saved })
        assertEquals(1, results.count { it is SavingsResult.Invalid })
        assertEquals(1, savings.observeBookDeposits(book.id).first().size)
        assertEquals(-6000L, database.ledgerDao().account(cash.id)!!.balanceMinor)
        assertEquals(6000L, database.ledgerDao().account(bank.id)!!.balanceMinor)
        assertEquals(1, database.ledgerDao().billCount())
    }

    @Test fun linkedProgressTracksTransferEditsDeletionAndUndo() = runBlocking {
        val p = saved(savings.savePlan(plan())); val bill = transfer()
        saved(savings.saveDeposit(SavingsDeposit(planId = p.id, date = date, linkedBillId = bill.id)))
        assertEquals("6000", progress(p).savedMinor.toString())
        assertTrue(ledger.save(bill.toDraft().copy(submissionId = UUID.randomUUID().toString(), amount = Money(8000, CurrencySpec.CNY)), bill.revision) is SaveResult.Saved)
        assertEquals("8000", progress(p).savedMinor.toString())
        val undo = ledger.delete(bill.id)
        assertEquals("0", progress(p).savedMinor.toString())
        assertEquals(SavingsDepositProblem.MISSING_TRANSFER, progress(p).records.single().problem)
        assertTrue(ledger.undo(undo))
        assertEquals("8000", progress(p).savedMinor.toString())
    }

    @Test fun wrongCurrencyUnknownReferenceAndPausedNewDepositsAreRejected() = runBlocking {
        val p = saved(savings.savePlan(plan())); val bill = transfer()
        assertTrue(savings.saveDeposit(SavingsDeposit(planId = p.id, date = date, amount = Money(100, CurrencySpec("USD", 2)))) is SavingsResult.Invalid)
        assertTrue(savings.saveDeposit(SavingsDeposit(planId = p.id, date = date, linkedBillId = UUID.randomUUID().toString())) is SavingsResult.Invalid)
        val d = saved(savings.saveDeposit(SavingsDeposit(planId = p.id, date = date, linkedBillId = bill.id)))
        assertTrue(savings.savePlan(p.copy(rule = p.rule.copy(amount = Money(100, CurrencySpec("USD", 2))))) is SavingsResult.Invalid)
        val paused = saved(savings.savePlan(p.copy(paused = true)))
        assertTrue(savings.saveDeposit(SavingsDeposit(planId = p.id, date = date, amount = Money(1, CurrencySpec.CNY))) is SavingsResult.Invalid)
        saved(savings.saveDeposit(d.copy(note = "Correction remains possible")))
        saved(savings.savePlan(paused.copy(paused = false)))
        saved(savings.saveDeposit(SavingsDeposit(planId = p.id, date = date, amount = Money(1, CurrencySpec.CNY))))
        Unit
    }

    @Test fun sortOrderSurvivesRepositoryRestartAndDeletingPlanPreservesTransfer() = runBlocking {
        val plans = (0 until 30).map { saved(savings.savePlan(plan("Plan $it"))) }
        saved(savings.move(plans.last().id, -1))
        val rows = SavingsRepository(database).observePlans(book.id).first()
        assertEquals(plans.last().id, rows[28].id)
        assertEquals(plans[28].id, rows[29].id)
        val bill = transfer()
        saved(savings.saveDeposit(SavingsDeposit(planId = plans[0].id, date = date, linkedBillId = bill.id)))
        val current = requireNotNull(savings.readPlan(plans[0].id))
        saved(savings.removePlan(current.id, current.revision))
        assertTrue(savings.observeDeposits(current.id).first().isEmpty())
        assertNotNull(ledger.observeBill(bill.id).first())
        assertEquals(6000L, database.ledgerDao().account(bank.id)!!.balanceMinor)
    }

    @Test fun failedPlanDeletionRestoresItsRecordsAndCanBeRetried() = runBlocking {
        val p = saved(savings.savePlan(plan()))
        saved(savings.saveDeposit(SavingsDeposit(planId = p.id, date = date, amount = Money(6000, CurrencySpec.CNY))))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_savings BEFORE DELETE ON savings_plans BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(savings.removePlan(p.id, p.revision) is SavingsResult.Failure)
        assertNotNull(savings.readPlan(p.id))
        assertEquals(1, savings.observeDeposits(p.id).first().size)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_savings")
        saved(savings.removePlan(p.id, p.revision))
    }

    @Test fun replacementAndFreshMergeRetainRulesOrderAndLinkedProgress() = runBlocking {
        val p = saved(savings.savePlan(plan())); val bill = transfer()
        saved(savings.saveDeposit(SavingsDeposit(planId = p.id, date = date, linkedBillId = bill.id)))
        val context: Context = ApplicationProvider.getApplicationContext()
        val archive = BackupArchive(database, context)
        val bytes = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        saved(savings.savePlan(p.copy(name = "Changed")))
        val preview = archive.inspect(bytes.inputStream())
        assertEquals(1, preview.summary.savingsPlans)
        assertEquals(1, preview.summary.savingsDeposits)
        assertTrue(preview.conflicts.any { it.kind.name == "SAVINGS_PLAN" })
        archive.restore(preview.previewId, RestoreMode.REPLACE)
        assertEquals("Travel", savings.readPlan(p.id)!!.name)
        assertEquals("6000", progress(p).savedMinor.toString())
        val receiver = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val other = BackupArchive(receiver, context)
            other.restore(other.inspect(bytes.inputStream()).previewId, RestoreMode.MERGE)
            val restored = SavingsRepository(receiver)
            assertEquals(p, restored.readPlan(p.id))
            assertEquals(1, restored.observeDeposits(p.id).first().size)
            assertEquals(6000L, receiver.ledgerDao().account(bank.id)!!.balanceMinor)
        } finally { receiver.close() }
    }

    @Test fun independentlyValidAllocationsOfOneTransferBlockMergeButNotReplacement() = runBlocking {
        val bill = transfer()
        val context: Context = ApplicationProvider.getApplicationContext()
        val source = BackupArchive(database, context)
        val baseline = ByteArrayOutputStream().also { source.exportTo(it) }.toByteArray()
        val receiver = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val archive = BackupArchive(receiver, context)
            archive.restore(archive.inspect(baseline.inputStream()).previewId, RestoreMode.MERGE)
            val local = SavingsRepository(receiver)
            val localPlan = saved(local.savePlan(plan("Local allocation")))
            saved(local.saveDeposit(SavingsDeposit(planId = localPlan.id, date = date, linkedBillId = bill.id)))
            val sourcePlan = saved(savings.savePlan(plan("Incoming allocation")))
            saved(savings.saveDeposit(SavingsDeposit(planId = sourcePlan.id, date = date, linkedBillId = bill.id)))
            val bytes = ByteArrayOutputStream().also { source.exportTo(it) }.toByteArray()
            val preview = archive.inspect(bytes.inputStream())
            assertTrue(preview.conflicts.any { it.kind == ConflictKind.SAVINGS_DEPOSIT && it.localLabel.contains("Local allocation") && it.backupLabel.contains("Incoming allocation") })
            assertTrue(runCatching { archive.restore(preview.previewId, RestoreMode.MERGE) }.isFailure)
            assertEquals(1, local.observeBookDeposits(book.id).first().size)
            archive.restore(preview.previewId, RestoreMode.REPLACE)
            assertEquals(listOf(sourcePlan.id), local.observePlans(book.id).first().map { it.id })
            assertEquals(6000L, receiver.ledgerDao().account(bank.id)!!.balanceMinor)
        } finally { receiver.close() }
    }

    @Test fun archiveRejectsForgedTargetsProjectionsCurrenciesAndDuplicateLinks() = runBlocking {
        val p = saved(savings.savePlan(plan())); val bill = transfer()
        val d = saved(savings.saveDeposit(SavingsDeposit(planId = p.id, date = date, linkedBillId = bill.id)))
        val manual = saved(savings.saveDeposit(SavingsDeposit(planId = p.id, date = date, amount = Money(100, CurrencySpec.CNY))))
        val context: Context = ApplicationProvider.getApplicationContext()
        val bytes = ByteArrayOutputStream().also { BackupArchive(database, context).exportTo(it) }.toByteArray()
        val data = ZipInputStream(bytes.inputStream()).use { zip ->
            var json: String? = null
            while (true) { val entry = zip.nextEntry ?: break; if (entry.name == "data.json") json = zip.readBytes().decodeToString() }
            LedgerJson.format.decodeFromString<LedgerSnapshot>(requireNotNull(json))
        }
        listOf(
            data.copy(savingsPlans = listOf(p.copy(id = "bad/route").entity())),
            data.copy(savingsPlans = listOf(p.copy(rule = p.rule.copy(amount = Money(0, CurrencySpec.CNY))).entity())),
            data.copy(savingsDeposits = listOf(d.entity().copy(planId = UUID.randomUUID().toString()))),
            data.copy(savingsDeposits = listOf(manual.copy(amount = Money(100, CurrencySpec("USD", 2))).entity())),
            data.copy(savingsDeposits = listOf(d.entity(), d.copy(id = UUID.randomUUID().toString()).entity())),
            data.copy(savingsDeposits = listOf(d.copy(linkedBillId = UUID.randomUUID().toString()).entity())),
        ).forEach { assertTrue(runCatching { RestoreValidator.validate(it) }.isFailure) }
        assertEquals(2, savings.observeDeposits(p.id).first().size)
    }
}
