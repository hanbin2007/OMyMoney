package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.core.schedule.*
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

class ScheduleIdempotencyTest {
    private lateinit var database: LedgerDatabase
    private lateinit var catalog: CatalogRepository
    private lateinit var repository: ScheduledBillRepository
    private lateinit var book: Book
    private lateinit var cash: AssetAccount
    private val zone = ZoneOffset.UTC
    private val through = Instant.parse("2024-04-01T00:00:00Z")
    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LedgerDatabase::class.java).build()
        catalog = CatalogRepository(database)
        repository = ScheduledBillRepository(database)
        book = Book(UUID.randomUUID().toString(), "Schedule", zoneId = zone)
        cash = AssetAccount(UUID.randomUUID().toString(), "Cash")
        assertTrue(catalog.saveBook(book) is CatalogResult.Saved)
        assertTrue(catalog.saveAccount(cash) is CatalogResult.Saved)
    }
    @After fun close() { database.close() }
    private fun plan(automatic: Boolean = false, installments: Boolean = false) = ScheduledPlan(name = "Rent",
        template = TransactionDraft(book.id, kind = BillKind.EXPENSE, amount = Money(10000, CurrencySpec.CNY), fromAccountId = cash.id,
            zoneId = zone, note = "Scheduled rent"),
        rule = ScheduleRule(LocalDate.parse("2024-01-31"), zone = zone, count = 3), automatic = automatic, installments = installments)
    private suspend fun save(plan: ScheduledPlan): ScheduledPlan {
        val result = repository.save(plan)
        assertTrue(result.toString(), result is ScheduleSaveResult.Saved)
        return (result as ScheduleSaveResult.Saved).plan
    }

    @Test fun defaultCreatesReviewableDraftsAndRestartDoesNotDuplicateThem() = runBlocking {
        val plan = save(plan())
        val result = repository.executeDue(through)
        assertEquals(3, result.created)
        assertEquals(3, result.pending)
        assertEquals(0, database.ledgerDao().billCount())
        assertEquals(0L, database.ledgerDao().account(cash.id)!!.balanceMinor)
        val rows = repository.observeExecutions(plan.id).first()
        assertEquals(listOf("2024-01-31T09:00:00Z", "2024-02-29T09:00:00Z", "2024-03-31T09:00:00Z"), rows.sortedBy { it.index }.map { it.plannedAt.toString() })
        assertTrue(rows.all { it.state == ScheduleExecutionState.PENDING && it.template.timeSource == TimeSource.SCHEDULE })
        assertEquals(3, database.ledgerDao().allDrafts().count { it.key.startsWith("schedule/") })
        assertEquals(0, ScheduledBillRepository(database).executeDue(through).created)
        assertEquals(3, repository.observeExecutions(plan.id).first().size)
    }

    @Test fun explicitAutomaticInstallmentsPostExactlyOnceAcrossCompetingExecutors() = runBlocking {
        val plan = save(plan(automatic = true, installments = true))
        val first = async { repository.executeDue(through) }
        val other = async { ScheduledBillRepository(database).executeDue(through) }
        assertEquals(3, first.await().created + other.await().created)
        assertEquals(3, database.ledgerDao().billCount())
        assertEquals(-10000L, database.ledgerDao().account(cash.id)!!.balanceMinor)
        val bills = database.ledgerDao().allBills().map(LedgerJson::decodeBill).sortedBy { it.occurredAt }
        assertEquals(listOf(3333L, 3333L, 3334L), bills.map { it.amount.minor })
        assertTrue(bills.all { it.source == BillSource.SCHEDULE && it.occurredAt < through })
        assertTrue(repository.observeExecutions(plan.id).first().all { it.state == ScheduleExecutionState.POSTED && it.billId != null })
        assertTrue(database.ledgerDao().allDrafts().isEmpty())
    }

    @Test fun pauseRetainsCursorAndFutureTemplateEditDoesNotRewriteGeneratedHistory() = runBlocking {
        val plan = save(plan(automatic = true))
        assertTrue(repository.setPaused(plan.id, true) is ScheduleSaveResult.Saved)
        assertEquals(0, repository.executeDue(through).created)
        assertTrue(repository.setPaused(plan.id, false) is ScheduleSaveResult.Saved)
        assertEquals(1, repository.executeDue(Instant.parse("2024-02-01T00:00:00Z")).created)
        val current = requireNotNull(repository.read(plan.id))
        save(current.copy(template = current.template.copy(amount = Money(20000, CurrencySpec.CNY))))
        assertEquals(2, repository.executeDue(through).created)
        assertEquals(listOf(10000L, 20000L, 20000L), database.ledgerDao().allBills().map(LedgerJson::decodeBill).sortedBy { it.occurredAt }.map { it.amount.minor })
        assertTrue(repository.save(current.copy(name = "Stale")) is ScheduleSaveResult.Conflict)
    }

    @Test fun invalidAutomaticPostingBecomesAnEditableDraftWithoutAFalseSuccess() = runBlocking {
        val plan = save(plan(automatic = true).copy(rule = plan().rule.copy(count = 1)))
        assertTrue(catalog.archiveAccount(cash.id) is CatalogResult.Saved)
        val result = repository.executeDue(through)
        assertEquals(1, result.pending)
        assertEquals(0, result.posted)
        assertEquals(0, database.ledgerDao().billCount())
        val row = repository.observeExecutions(plan.id).first().single()
        assertNotNull(row.failure)
        assertNotNull(DraftRepository(database).read("schedule/${row.id}"))
    }

    @Test fun confirmationAndDiscardAreAtomicWithTheirExecutionRecords() = runBlocking {
        val plan = save(plan())
        repository.executeDue(through)
        val rows = repository.observeExecutions(plan.id).first().sortedBy { it.index }
        assertEquals(3, rows.size)
        val drafts = DraftRepository(database)
        val first = requireNotNull(drafts.read("schedule/${rows[0].id}"))
        assertTrue(drafts.submit(first) is SaveResult.Saved)
        assertTrue(drafts.submit(first) is SaveResult.Saved)
        assertEquals(-10000L, database.ledgerDao().account(cash.id)!!.balanceMinor)
        drafts.remove("schedule/${rows[1].id}")
        val states = repository.observeExecutions(plan.id).first().associate { it.id to it.state }
        assertEquals(ScheduleExecutionState.POSTED, states[rows[0].id])
        assertEquals(ScheduleExecutionState.SKIPPED, states[rows[1].id])
        assertEquals(ScheduleExecutionState.PENDING, states[rows[2].id])
        assertEquals(0, repository.executeDue(through).created)
    }

    @Test fun automaticBalanceOverflowRetainsThePendingDraftAndItsCursor() = runBlocking {
        cash = cash.copy(openingBalance = Money(Long.MIN_VALUE, CurrencySpec.CNY), balance = Money(Long.MIN_VALUE, CurrencySpec.CNY))
        assertTrue(catalog.saveAccount(cash) is CatalogResult.Saved)
        val plan = save(plan(automatic = true).copy(rule = plan().rule.copy(count = 1)))
        assertEquals(1, repository.executeDue(through).pending)
        assertEquals(1, repository.read(plan.id)!!.nextIndex)
        assertEquals(1, repository.observeExecutions(plan.id).first().size)
        assertEquals(Long.MIN_VALUE, database.ledgerDao().account(cash.id)!!.balanceMinor)
        assertEquals(0, database.ledgerDao().billCount())
    }

    @Test fun failedWriteRollsBackBillsDraftsAndExecutionCursorTogether() = runBlocking {
        val plan = save(plan(automatic = true))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_schedule BEFORE INSERT ON bills BEGIN SELECT RAISE(ABORT, 'schedule test'); END")
        runCatching { repository.executeDue(through) }
        assertEquals(0, database.ledgerDao().billCount())
        assertEquals(0L, database.ledgerDao().account(cash.id)!!.balanceMinor)
        assertEquals(0, requireNotNull(repository.read(plan.id)).nextIndex)
        assertTrue(repository.observeExecutions(plan.id).first().isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_schedule")
        assertEquals(3, repository.executeDue(through).posted)
    }

    @Test fun replacementRestoresPlansExecutionsAndPendingDraftsTogether() = runBlocking {
        val plan = save(plan())
        repository.executeDue(through)
        val context: Context = ApplicationProvider.getApplicationContext()
        val archive = BackupArchive(database, context)
        val bytes = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        val execution = repository.observeExecutions(plan.id).first().first()
        assertTrue(DraftRepository(database).submit(requireNotNull(DraftRepository(database).read("schedule/${execution.id}"))) is SaveResult.Saved)
        save(requireNotNull(repository.read(plan.id)).copy(name = "Changed"))
        val preview = archive.inspect(bytes.inputStream())
        assertEquals(1, preview.summary.scheduledPlans)
        assertEquals(0, preview.summary.automaticPlans)
        assertEquals(3, preview.summary.pendingExecutions)
        assertTrue(preview.conflicts.any { it.kind.name == "SCHEDULE" })
        assertTrue(preview.conflicts.any { it.kind.name == "SCHEDULE_EXECUTION" })
        assertTrue(runCatching { archive.restore(preview.previewId, RestoreMode.MERGE) }.isFailure)
        archive.restore(preview.previewId, RestoreMode.REPLACE)
        assertEquals("Rent", repository.read(plan.id)!!.name)
        assertEquals(3, repository.read(plan.id)!!.nextIndex)
        assertTrue(repository.observeExecutions(plan.id).first().all { it.state == ScheduleExecutionState.PENDING })
        assertEquals(0, database.ledgerDao().billCount())
        assertEquals(3, database.ledgerDao().allDrafts().size)
        assertEquals(0, repository.executeDue(through).created)
    }

    @Test fun aFreshRestoreKeepsAutomaticInstallmentsIdempotent() = runBlocking {
        val plan = save(plan(automatic = true, installments = true))
        repository.executeDue(Instant.parse("2024-02-01T00:00:00Z"))
        val context: Context = ApplicationProvider.getApplicationContext()
        val bytes = ByteArrayOutputStream().also { BackupArchive(database, context).exportTo(it) }.toByteArray()
        val receiver = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val archive = BackupArchive(receiver, context)
            val preview = archive.inspect(bytes.inputStream())
            assertEquals(1, preview.summary.scheduledPlans)
            assertEquals(1, preview.summary.automaticPlans)
            assertEquals(0, preview.summary.pendingExecutions)
            archive.restore(preview.previewId, RestoreMode.REPLACE)
            val restored = ScheduledBillRepository(receiver)
            assertEquals(1, restored.observePlans(book.id).first().size)
            assertEquals(1, restored.read(plan.id)!!.nextIndex)
            assertEquals(2, restored.executeDue(through).posted)
            assertEquals(-10000L, receiver.ledgerDao().account(cash.id)!!.balanceMinor)
            assertEquals(0, restored.executeDue(through).created)
        } finally { receiver.close() }
    }

    @Test fun startedPlanCalendarAndInstallmentTotalCannotRewritePastOccurrences() = runBlocking {
        val plan = save(plan(installments = true))
        repository.executeDue(Instant.parse("2024-02-01T00:00:00Z"))
        val current = requireNotNull(repository.read(plan.id))
        assertTrue(repository.save(current.copy(rule = current.rule.copy(localTime = "10:00"))) is ScheduleSaveResult.Invalid)
        assertTrue(repository.save(current.copy(template = current.template.copy(amount = Money(20000, CurrencySpec.CNY)))) is ScheduleSaveResult.Invalid)
        assertEquals(current, repository.read(plan.id))
        assertEquals(1, repository.observeExecutions(plan.id).first().size)
    }

    @Test fun installmentPrincipalCannotExceedTheCurrentlyOutstandingLinkedSource() = runBlocking {
        val debt = AssetAccount(UUID.randomUUID().toString(), "Loan", kind = AccountKind.LIABILITY)
        assertTrue(catalog.saveAccount(debt) is CatalogResult.Saved)
        val ledger = RoomLedgerRepository(database)
        val source = ledger.save(TransactionDraft(book.id, kind = BillKind.DEBT_BORROW, amount = Money(10000, CurrencySpec.CNY),
            fromAccountId = debt.id, toAccountId = cash.id, occurredAt = Instant.parse("2023-12-01T09:00:00Z"), timeSource = TimeSource.USER), null) as SaveResult.Saved
        assertTrue(ledger.save(TransactionDraft(book.id, kind = BillKind.DEBT_REPAY, amount = Money(6000, CurrencySpec.CNY),
            fromAccountId = cash.id, toAccountId = debt.id, linkedBillId = source.billId,
            occurredAt = Instant.parse("2023-12-02T09:00:00Z"), timeSource = TimeSource.USER), null) is SaveResult.Saved)
        val base = plan(automatic = true, installments = true)
        val schedule = base.copy(template = base.template.copy(kind = BillKind.DEBT_REPAY, toAccountId = debt.id, linkedBillId = source.billId))
        assertTrue(repository.save(schedule) is ScheduleSaveResult.Invalid)
        assertTrue(repository.observePlans(book.id).first().isEmpty())
        assertTrue(repository.save(schedule.copy(template = schedule.template.copy(amount = Money(4000, CurrencySpec.CNY)))) is ScheduleSaveResult.Saved)
        assertEquals(3, repository.executeDue(through).posted)
        assertEquals(0L, database.ledgerDao().account(debt.id)!!.balanceMinor)
        assertEquals(0L, database.ledgerDao().account(cash.id)!!.balanceMinor)
    }

    @Test fun forgedArchiveCursorsMissingDraftsAndPostedReceiptsAreRejected() = runBlocking {
        val plan = save(plan())
        repository.executeDue(through)
        val context: Context = ApplicationProvider.getApplicationContext()
        val bytes = ByteArrayOutputStream().also { BackupArchive(database, context).exportTo(it) }.toByteArray()
        val data = ZipInputStream(bytes.inputStream()).use { zip ->
            var json: String? = null
            while (true) {
                val next = zip.nextEntry ?: break
                if (next.name == "data.json") json = zip.readBytes().decodeToString()
            }
            LedgerJson.format.decodeFromString<LedgerSnapshot>(requireNotNull(json))
        }
        val current = requireNotNull(repository.read(plan.id))
        val execution = data.scheduleExecutions.first().model()
        listOf(
            data.copy(schedules = listOf(current.copy(nextIndex = 0).entity())),
            data.copy(drafts = emptyList()),
            data.copy(scheduleExecutions = data.scheduleExecutions.drop(1) + execution.copy(plannedAt = execution.plannedAt.plusSeconds(1)).entity()),
            data.copy(drafts = data.drafts.filterNot { it.key == "schedule/${execution.id}" }, scheduleExecutions = data.scheduleExecutions.drop(1) +
                execution.copy(state = ScheduleExecutionState.POSTED, billId = UUID.randomUUID().toString()).entity()),
        ).forEach { invalid -> assertThrows(BackupException::class.java) { RestoreValidator.validate(invalid) } }
        assertEquals(3, repository.read(plan.id)!!.nextIndex)
        assertEquals(0, database.ledgerDao().billCount())
    }
}
