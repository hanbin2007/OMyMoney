package app.omymoney.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import app.omymoney.core.savings.*
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

sealed interface SavingsResult<out T> {
    data class Saved<T>(val value: T) : SavingsResult<T>
    data class Invalid(val code: String) : SavingsResult<Nothing>
    data object Conflict : SavingsResult<Nothing>
    data object Failure : SavingsResult<Nothing>
}

class SavingsRepository(private val database: LedgerDatabase) {
    private val dao = database.ledgerDao()
    fun observePlans(bookId: String): Flow<List<SavingsPlan>> = dao.observeSavingsPlans(bookId).map { rows -> rows.map(SavingsPlanEntity::model) }
    fun observeDeposits(planId: String): Flow<List<SavingsDeposit>> = dao.observeSavingsDeposits(planId).map { rows -> rows.map(SavingsDepositEntity::model) }
    fun observeBookDeposits(bookId: String): Flow<List<SavingsDeposit>> = dao.observeBookSavingsDeposits(bookId).map { rows -> rows.map(SavingsDepositEntity::model) }
    suspend fun readPlan(id: String): SavingsPlan? = dao.savingsPlan(id)?.model()
    suspend fun readDeposit(id: String): SavingsDeposit? = dao.savingsDeposit(id)?.model()

    suspend fun savePlan(plan: SavingsPlan): SavingsResult<SavingsPlan> = mutate {
        validateSavingsPlan(plan)
        val previous = readPlan(plan.id)
        if ((previous?.revision ?: 0) != plan.revision) return@mutate SavingsResult.Conflict
        if (previous != null && previous.bookId != plan.bookId) return@mutate SavingsResult.Invalid("book")
        val book = dao.book(plan.bookId) ?: return@mutate SavingsResult.Invalid("book")
        if (book.archived && previous == null) return@mutate SavingsResult.Invalid("book")
        if (previous != null && previous.rule.amount.currency != plan.rule.amount.currency && dao.savingsDeposits(plan.id).isNotEmpty()) {
            return@mutate SavingsResult.Invalid("currency_locked")
        }
        val order = previous?.sortOrder ?: Math.addExact(dao.savingsPlans(plan.bookId).maxOfOrNull { it.sortOrder } ?: -1, 1)
        val updated = plan.copy(name = plan.name.trim(), sortOrder = order, revision = Math.addExact(plan.revision, 1))
        dao.upsertSavingsPlan(updated.entity())
        SavingsResult.Saved(updated)
    }

    suspend fun saveDeposit(deposit: SavingsDeposit): SavingsResult<SavingsDeposit> = mutate {
        val plan = readPlan(deposit.planId) ?: return@mutate SavingsResult.Invalid("plan")
        validateSavingsDeposit(deposit, plan)
        val previous = readDeposit(deposit.id)
        if ((previous?.revision ?: 0) != deposit.revision) return@mutate SavingsResult.Conflict
        if (previous != null && previous.planId != deposit.planId) return@mutate SavingsResult.Invalid("plan")
        if (plan.paused && previous == null) return@mutate SavingsResult.Invalid("paused")
        val linkedBillId = deposit.linkedBillId
        if (linkedBillId != null) {
            val assigned = dao.savingsDepositForBill(linkedBillId)
            if (assigned != null && assigned.id != deposit.id) return@mutate SavingsResult.Invalid("duplicate_transfer")
            // A note correction may retain an invalid historical link; changing it requires a valid current transfer.
            if (previous?.linkedBillId != linkedBillId) {
                val bill = dao.bill(linkedBillId)?.takeIf { it.deletedAt == null }?.let(LedgerJson::decodeBill)
                val resolved = SavingsCalculator.resolve(plan, deposit, listOfNotNull(bill).associateBy { it.id })
                if (resolved.problem != null) return@mutate SavingsResult.Invalid("transfer")
            }
        }
        val updated = deposit.copy(revision = Math.addExact(deposit.revision, 1))
        dao.upsertSavingsDeposit(updated.entity())
        SavingsResult.Saved(updated)
    }

    suspend fun removeDeposit(id: String, revision: Long): SavingsResult<Unit> = mutate {
        val previous = readDeposit(id) ?: return@mutate SavingsResult.Invalid("not_found")
        if (previous.revision != revision) return@mutate SavingsResult.Conflict
        dao.removeSavingsDeposit(id)
        SavingsResult.Saved(Unit)
    }

    suspend fun removePlan(id: String, revision: Long): SavingsResult<Unit> = mutate {
        val previous = readPlan(id) ?: return@mutate SavingsResult.Invalid("not_found")
        if (previous.revision != revision) return@mutate SavingsResult.Conflict
        dao.removePlanSavingsDeposits(id)
        dao.removeSavingsPlan(id)
        SavingsResult.Saved(Unit)
    }

    suspend fun move(id: String, direction: Int): SavingsResult<Unit> = mutate {
        require(direction == -1 || direction == 1)
        val plan = readPlan(id) ?: return@mutate SavingsResult.Invalid("not_found")
        val plans = dao.savingsPlans(plan.bookId).map(SavingsPlanEntity::model).toMutableList()
        val index = plans.indexOfFirst { it.id == id }
        val destination = index + direction
        if (destination in plans.indices) {
            val other = plans[destination]; plans[destination] = plans[index]; plans[index] = other
            plans.forEachIndexed { position, row ->
                if (row.sortOrder != position) dao.upsertSavingsPlan(row.copy(sortOrder = position, revision = Math.addExact(row.revision, 1)).entity())
            }
        }
        SavingsResult.Saved(Unit)
    }

    private suspend fun <T> mutate(block: suspend () -> SavingsResult<T>): SavingsResult<T> = try {
        database.withTransaction { block() }
    } catch (_: ArithmeticException) { SavingsResult.Invalid("overflow") }
    catch (_: IllegalArgumentException) { SavingsResult.Invalid("invalid") }
    catch (_: SQLiteException) { SavingsResult.Failure }
    catch (_: SerializationException) { SavingsResult.Failure }
}

internal fun validateSavingsPlan(plan: SavingsPlan) {
    require(UUID.fromString(plan.id).toString() == plan.id && UUID.fromString(plan.bookId).toString() == plan.bookId)
    require(plan.name.isNotBlank() && plan.name.length <= 100 && plan.note.length <= 5000 && plan.revision >= 0 && plan.sortOrder >= 0)
    SavingsCalculator.target(plan.rule)
}

internal fun validateSavingsDeposit(deposit: SavingsDeposit, plan: SavingsPlan) {
    require(UUID.fromString(deposit.id).toString() == deposit.id && deposit.planId == plan.id)
    require(deposit.revision >= 0 && deposit.note.length <= 5000 && deposit.date.year in 1..9998)
    require((deposit.linkedBillId == null) != (deposit.amount == null))
    deposit.linkedBillId?.let { require(UUID.fromString(it).toString() == it) }
    deposit.amount?.let { require(it.minor > 0 && it.currency == plan.rule.amount.currency) }
}

internal fun SavingsPlan.entity() = SavingsPlanEntity(id, bookId, sortOrder, LedgerJson.format.encodeToString(this))
internal fun SavingsPlanEntity.model() = LedgerJson.format.decodeFromString<SavingsPlan>(payloadJson).also {
    require(it.id == id && it.bookId == bookId && it.sortOrder == sortOrder)
}
internal fun SavingsDeposit.entity() = SavingsDepositEntity(id, planId, linkedBillId, LedgerJson.format.encodeToString(this))
internal fun SavingsDepositEntity.model() = LedgerJson.format.decodeFromString<SavingsDeposit>(payloadJson).also {
    require(it.id == id && it.planId == planId && it.linkedBillId == linkedBillId)
}
