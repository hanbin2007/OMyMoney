package app.omymoney.data

import app.omymoney.core.budget.*
import app.omymoney.core.model.*
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

internal fun BudgetEntity.model(): BudgetPlan? = payloadJson?.let { LedgerJson.format.decodeFromString<BudgetPlan>(it) }
internal fun BudgetPlan.entity() = BudgetEntity(bookId, period.key, LedgerJson.format.encodeToString(this))

class BudgetRepository(private val database: LedgerDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val dao = database.ledgerDao()
    fun observe(bookId: String, period: BudgetPeriod): Flow<BudgetPlan?> = dao.observeBudget(bookId, period.key).map { it?.model() }
    suspend fun save(plan: BudgetPlan): CatalogResult<BudgetPlan> = mutate { saveValidated(plan) }
    suspend fun delete(bookId: String, period: BudgetPeriod): CatalogResult<Unit> = mutate {
        book(bookId)
        dao.upsertBudget(BudgetEntity(bookId, period.key, null))
    }
    suspend fun copyPreviousIfEligible(bookId: String, period: BudgetPeriod): CatalogResult<BudgetPlan?> = mutate {
        val book = book(bookId)
        val existing = dao.budget(bookId, period.key)
        val today = LocalDate.now(clock.withZone(book.zoneId))
        val current = BudgetPeriod(today.year, today.monthValue.takeIf { period.month != null })
        when {
            existing != null -> existing.model()
            period != current -> null
            else -> copySource(book, period)?.let { saveValidated(it) }
        }
    }
    suspend fun copyPrevious(bookId: String, period: BudgetPeriod): CatalogResult<BudgetPlan> = mutate {
        val copied = copySource(book(bookId), period) ?: invalid("period", "no_previous_budget")
        saveValidated(copied)
    }

    private suspend fun copySource(book: Book, period: BudgetPeriod): BudgetPlan? {
        if (period.year == 1 && (period.month == null || period.month == 1)) return null
        val previous = dao.budget(book.id, period.previous().key)?.model() ?: return null
        if (previous.total.currency != book.currency) return null
        val active = dao.categories(book.id).filterNot { it.archived }.map { it.id }.toSet()
        return previous.copy(period = period, copiedFrom = previous.period, categories = previous.categories.filter { it.categoryId in active })
    }
    private suspend fun book(id: String): Book = dao.book(id)?.model()?.takeUnless { it.archived }
        ?: invalid("bookId", "unavailable_book")
    private suspend fun saveValidated(plan: BudgetPlan): BudgetPlan {
        val book = book(plan.bookId)
        if (plan.total.currency != book.currency) invalid("currency", "currency_mismatch")
        val categories = dao.categories(book.id).map(CategoryEntity::model)
        val errors = BudgetCalculator.validate(plan, categories)
        if (errors.isNotEmpty()) throw DraftValidationException(errors)
        val previous = dao.budget(book.id, plan.period.key)?.model()
        val priorIds = previous?.categories.orEmpty().map { it.categoryId }.toSet()
        if (plan.categories.any { item -> item.categoryId !in priorIds && categories.first { it.id == item.categoryId }.archived }) {
            invalid("categories", "archived_category")
        }
        val normalized = plan.copy(categories = plan.categories.sortedBy { it.categoryId })
        dao.upsertBudget(normalized.entity())
        return normalized
    }
    private suspend fun <T> mutate(block: suspend () -> T): CatalogResult<T> = try {
        CatalogResult.Saved(database.withTransaction { block() })
    } catch (error: DraftValidationException) { CatalogResult.Invalid(error.errors)
    } catch (_: SQLiteException) { CatalogResult.Failure("storage") }
    private fun invalid(field: String, code: String): Nothing = throw DraftValidationException(listOf(FieldError(field, code, code)))
}
