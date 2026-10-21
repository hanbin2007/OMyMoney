@file:kotlinx.serialization.UseSerializers(app.omymoney.core.model.InstantSerializer::class)

package app.omymoney.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import app.omymoney.core.model.*
import app.omymoney.core.schedule.*
import app.omymoney.core.ledger.LinkedBillRules
import app.omymoney.core.ledger.DebtRules
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

@Serializable data class ScheduledPlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val template: TransactionDraft,
    val rule: ScheduleRule,
    val installments: Boolean = false,
    val automatic: Boolean = false,
    val paused: Boolean = false,
    val revision: Long = 0,
    val nextIndex: Int = 0,
)
@Serializable enum class ScheduleExecutionState { PENDING, POSTED, SKIPPED }
@Serializable data class ScheduleExecution(
    val id: String,
    val planId: String,
    val index: Int,
    val plannedAt: Instant,
    val generatedAt: Instant,
    val template: TransactionDraft,
    val state: ScheduleExecutionState,
    val billId: String? = null,
    val failure: String? = null,
)
sealed interface ScheduleSaveResult {
    data class Saved(val plan: ScheduledPlan) : ScheduleSaveResult
    data class Invalid(val code: String) : ScheduleSaveResult
    data object Conflict : ScheduleSaveResult
    data object Failure : ScheduleSaveResult
}
data class ScheduleRunSummary(val created: Int = 0, val posted: Int = 0, val pending: Int = 0, val moreDue: Boolean = false)

class ScheduledBillRepository(private val database: LedgerDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val dao = database.ledgerDao()
    private val ledger = RoomLedgerRepository(database, clock)
    fun observePlans(bookId: String): Flow<List<ScheduledPlan>> = dao.observeSchedules(bookId).map { rows -> rows.map(ScheduleEntity::model) }
    fun observeExecutions(planId: String): Flow<List<ScheduleExecution>> = dao.observeScheduleExecutions(planId).map { rows -> rows.map(ScheduleExecutionEntity::model) }
    suspend fun read(id: String): ScheduledPlan? = dao.schedule(id)?.model()

    suspend fun save(plan: ScheduledPlan): ScheduleSaveResult = try {
        database.withTransaction {
            validateSchedule(plan)
            val previous = read(plan.id)
            if ((previous?.revision ?: 0) != plan.revision) return@withTransaction ScheduleSaveResult.Conflict
            if (previous != null && previous.template.bookId != plan.template.bookId) return@withTransaction ScheduleSaveResult.Invalid("schedule_book")
            if (previous != null && previous.nextIndex > 0 && (previous.rule != plan.rule || previous.installments != plan.installments ||
                (previous.installments && previous.template.amount != plan.template.amount))) {
                return@withTransaction ScheduleSaveResult.Invalid("schedule_started")
            }
            val normalized = plan.copy(name = plan.name.trim(), revision = Math.addExact(plan.revision, 1), nextIndex = previous?.nextIndex ?: 0,
                template = plan.template.copy(id = null, submissionId = plan.id, source = BillSource.SCHEDULE,
                    occurredAt = null, timeSource = TimeSource.UNKNOWN, zoneId = plan.rule.zone, recognizedAt = null,
                    sourcePackage = null, ruleId = null, ruleVersion = null))
            val first = requireNotNull(ScheduleCalculator.occurrence(plan.rule, 0))
            if (normalized.template != previous?.template || normalized.rule != previous?.rule) {
                val next = ScheduleCalculator.occurrence(plan.rule, normalized.nextIndex) ?: first
                val draft = scheduledDraft(normalized, next)
                ledger.validateNewDraft(draft)
                if (normalized.installments) {
                    val last = requireNotNull(ScheduleCalculator.occurrence(plan.rule, requireNotNull(plan.rule.count) - 1))
                    ledger.validateNewDraft(scheduledDraft(normalized, last))
                    if (draft.kind in LinkedBillRules.settlementKinds + DebtRules.principalKinds) {
                        val remaining = ScheduleCalculator.installments(requireNotNull(plan.template.amount), requireNotNull(plan.rule.count))
                            .drop(normalized.nextIndex).sumOf { it.minor }
                        if (remaining > 0) ledger.validateNewDraft(draft.copy(amount = Money(remaining, requireNotNull(draft.amount).currency), fee = null))
                    }
                }
            }
            dao.upsertSchedule(normalized.entity())
            ScheduleSaveResult.Saved(normalized)
        }
    } catch (error: DraftValidationException) { ScheduleSaveResult.Invalid(error.errors.first().code) }
    catch (_: ArithmeticException) { ScheduleSaveResult.Invalid("overflow") }
    catch (_: IllegalArgumentException) { ScheduleSaveResult.Invalid("schedule_invalid") }
    catch (_: SQLiteException) { ScheduleSaveResult.Failure }
    catch (_: SerializationException) { ScheduleSaveResult.Failure }

    suspend fun executeDue(through: Instant, planId: String? = null): ScheduleRunSummary = database.withTransaction {
        var created = 0
        var posted = 0
        var pending = 0
        var moreDue = false
        val plans = if (planId == null) dao.allSchedules().map(ScheduleEntity::model) else listOfNotNull(read(planId))
        plans.filterNot { it.paused }.forEach { plan ->
            var nextIndex = plan.nextIndex
            val batch = if (created < 512) ScheduleCalculator.dueOccurrences(plan.rule, through, nextIndex, 512 - created) else emptyList()
            batch.forEach { occurrence ->
                val draft = scheduledDraft(plan, occurrence)
                val id = scheduleExecutionId(plan.id, occurrence.plannedAt)
                val previous = dao.scheduleExecution(id)?.model()
                check(previous == null || previous.index == occurrence.index) { "Two calendar occurrences resolve to the same instant" }
                if (previous == null) {
                    // Expected validation failures must not poison the surrounding Room transaction.
                    val valid = if (plan.automatic) try { ledger.validateNewDraft(draft, checkBalance = true); true }
                        catch (_: DraftValidationException) { false } catch (_: ArithmeticException) { false } else false
                    val result = if (valid) ledger.save(draft, null) else null
                    if (result is SaveResult.Failure) throw SQLiteException("Scheduled posting failed: ${result.code}")
                    check(result == null || result is SaveResult.Saved) { "Prevalidated scheduled posting unexpectedly failed" }
                    val saved = result as? SaveResult.Saved
                    val execution = ScheduleExecution(id, plan.id, occurrence.index, occurrence.plannedAt, clock.instant(), draft,
                        if (saved == null) ScheduleExecutionState.PENDING else ScheduleExecutionState.POSTED, saved?.billId,
                        if (plan.automatic && saved == null) "posting_invalid" else null)
                    dao.upsertScheduleExecution(execution.entity())
                    if (saved == null) {
                        val amount = requireNotNull(draft.amount)
                        val editor = EditorDraft("schedule/$id", draft, amount.currency, amount.decimalText(), draft.tags.joinToString(", "))
                        dao.upsertDraft(DraftEntity(editor.key, draft.bookId, LedgerJson.format.encodeToString(editor)))
                        pending++
                    } else posted++
                    created++
                }
                nextIndex = occurrence.index + 1
            }
            if (nextIndex != plan.nextIndex) dao.upsertSchedule(plan.copy(nextIndex = nextIndex).entity())
            moreDue = moreDue || (ScheduleCalculator.occurrence(plan.rule, nextIndex)?.plannedAt?.let { it <= through } == true)
        }
        ScheduleRunSummary(created, posted, pending, moreDue)
    }

    suspend fun resumeAndExecute(id: String, through: Instant): ScheduleRunSummary = database.withTransaction {
        val plan = requireNotNull(read(id)) { "Schedule not found" }
        if (plan.paused) dao.upsertSchedule(plan.copy(paused = false, revision = Math.addExact(plan.revision, 1)).entity())
        executeDue(through, id)
    }

    suspend fun setPaused(id: String, paused: Boolean): ScheduleSaveResult = try {
        database.withTransaction {
            val plan = read(id) ?: return@withTransaction ScheduleSaveResult.Invalid("not_found")
            val updated = plan.copy(paused = paused, revision = Math.addExact(plan.revision, 1))
            dao.upsertSchedule(updated.entity())
            ScheduleSaveResult.Saved(updated)
        }
    } catch (_: SQLiteException) { ScheduleSaveResult.Failure }
}

internal fun validateSchedule(plan: ScheduledPlan) {
    require(UUID.fromString(plan.id).toString() == plan.id && plan.name.isNotBlank() && plan.name.length <= 100)
    require(plan.revision >= 0 && plan.nextIndex in 0..ScheduleCalculator.MAX_OCCURRENCES)
    ScheduleCalculator.validate(plan.rule)
    if (plan.installments) {
        val count = requireNotNull(plan.rule.count)
        ScheduleCalculator.installments(requireNotNull(plan.template.amount), count)
        require(ScheduleCalculator.occurrence(plan.rule, count - 1) != null) { "End date truncates installments" }
        require(plan.template.exchange == null) { "Installments must use one currency" }
    }
    require(LedgerJson.format.encodeToString(plan).toByteArray().size <= 1_048_576)
}

internal fun scheduleExecutionId(planId: String, at: Instant): String = UUID.nameUUIDFromBytes("$planId/$at".toByteArray(Charsets.UTF_8)).toString()

fun scheduledDraft(plan: ScheduledPlan, occurrence: Occurrence): TransactionDraft {
    val amount = if (plan.installments) ScheduleCalculator.installments(requireNotNull(plan.template.amount), requireNotNull(plan.rule.count))[occurrence.index]
        else plan.template.amount
    return plan.template.copy(id = null, submissionId = scheduleExecutionId(plan.id, occurrence.plannedAt), amount = amount,
        occurredAt = occurrence.plannedAt, timeSource = TimeSource.SCHEDULE, source = BillSource.SCHEDULE, zoneId = plan.rule.zone)
}

internal fun ScheduledPlan.entity() = ScheduleEntity(id, template.bookId, template.fromAccountId, template.toAccountId, template.categoryId, LedgerJson.format.encodeToString(this))
internal fun ScheduleEntity.model() = LedgerJson.format.decodeFromString<ScheduledPlan>(payloadJson).also {
    require(it.id == id && it.template.bookId == bookId && it.template.fromAccountId == fromAccountId &&
        it.template.toAccountId == toAccountId && it.template.categoryId == categoryId) { "Schedule identity mismatch" }
}
internal fun ScheduleExecution.entity() = ScheduleExecutionEntity(id, planId, plannedAt.toString(), state.name, LedgerJson.format.encodeToString(this))
internal fun ScheduleExecutionEntity.model() = LedgerJson.format.decodeFromString<ScheduleExecution>(payloadJson).also {
    require(it.id == id && it.planId == planId && it.plannedAt.toString() == plannedAt && it.state.name == state) { "Execution identity mismatch" }
}
