package app.omymoney.core.budget

import app.omymoney.core.model.*
import app.omymoney.core.ledger.EconomicFacts
import app.omymoney.core.ledger.EconomicKind
import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

@Serializable data class BudgetPeriod(val year: Int, val month: Int? = null) {
    init { require(year in 1..9998 && (month == null || month in 1..12)) }
    val start: LocalDate get() = LocalDate.of(year, month ?: 1, 1)
    val endExclusive: LocalDate get() = if (month == null) start.plusYears(1) else start.plusMonths(1)
    val key: String get() = if (month == null) "%04d".format(java.util.Locale.ROOT, year) else "%04d-%02d".format(java.util.Locale.ROOT, year, month)
    fun previous(): BudgetPeriod = if (month == null) BudgetPeriod(year - 1) else start.minusMonths(1).let { BudgetPeriod(it.year, it.monthValue) }
    companion object {
        fun parse(key: String): BudgetPeriod = (if (key.length == 4) BudgetPeriod(java.time.Year.parse(key).value)
            else java.time.YearMonth.parse(key).let { BudgetPeriod(it.year, it.monthValue) }).also { require(it.key == key) }
    }
}

@Serializable data class BudgetLimit(val categoryId: String, val limit: Money)
@Serializable data class BudgetPlan(val bookId: String, val period: BudgetPeriod, val total: Money,
                                   val categories: List<BudgetLimit> = emptyList(), val copiedFrom: BudgetPeriod? = null)

data class BudgetUsage(val limit: Money, val used: BigInteger?, val missingCurrencies: Set<CurrencySpec> = emptySet()) {
    val remaining get() = used?.let { BigInteger.valueOf(limit.minor) - it }
    val overspent get() = remaining?.negate()?.max(BigInteger.ZERO)
}
data class BudgetSummary(val total: BudgetUsage, val categories: Map<String, BudgetUsage>, val daysRemaining: Int,
                         val dailyAvailable: BigInteger?, val ratesUsed: List<ManualExchangeRate> = emptyList())

object BudgetCalculator {
    fun validate(budget: BudgetPlan, categories: List<Category>): List<FieldError> = buildList {
        if (budget.total.minor < 0) add(FieldError("total", "negative_budget", "Budget must not be negative"))
        if (budget.categories.map { it.categoryId }.distinct().size != budget.categories.size) {
            add(FieldError("categories", "duplicate_category_budget", "Each category can have only one limit"))
        }
        val byId = categories.associateBy { it.id }
        val limits = budget.categories.associateBy { it.categoryId }
        budget.categories.forEach { item ->
            val category = byId[item.categoryId]
            if (category == null || category.bookId != budget.bookId || category.kind != BillKind.EXPENSE) {
                add(FieldError(item.categoryId, "invalid_budget_category", "Choose an expense category in this book"))
            }
            if (item.limit.currency != budget.total.currency || item.limit.minor < 0) {
                add(FieldError(item.categoryId, "invalid_budget_limit", "Limit must be nonnegative and use the budget currency"))
            }
            val children = budget.categories.filter { byId[it.categoryId]?.parentId == item.categoryId }
            val sum = children.fold(BigInteger.ZERO) { sum, child -> sum + BigInteger.valueOf(child.limit.minor) }
            if (sum > BigInteger.valueOf(item.limit.minor)) add(FieldError(item.categoryId, "child_budget_exceeds_parent", "Child limits exceed their parent limit"))
        }
        val allocated = budget.categories.filter { byId[it.categoryId]?.parentId !in limits }
            .fold(BigInteger.ZERO) { sum, item -> sum + BigInteger.valueOf(item.limit.minor) }
        if (allocated > BigInteger.valueOf(budget.total.minor)) add(FieldError("total", "category_budget_exceeds_total", "Category limits exceed the total budget"))
    }
    fun calculate(period: BudgetPeriod, bills: List<Bill>, budgets: BudgetPlan, categories: List<Category> = emptyList(),
                  zone: ZoneId = ZoneOffset.UTC, today: LocalDate = LocalDate.now(zone), rates: List<ManualExchangeRate> = emptyList()): BudgetSummary {
        require(period == budgets.period)
        require(validate(budgets, categories).isEmpty())
        val facts = EconomicFacts.from(bills.filter { it.bookId == budgets.bookId }).filter {
            val date = it.bill.occurredAt.atZone(zone).toLocalDate()
            it.kind == EconomicKind.EXPENSE && !it.bill.excludeBudget && date >= period.start && date < period.endExclusive
        }.map { it to EconomicFacts.value(it, budgets.total.currency, rates) }
        fun usage(limit: Money, categoryId: String? = null): BudgetUsage {
            val included = if (categoryId == null) facts else {
                val ids = categories.filter { it.id == categoryId || it.parentId == categoryId }.map { it.id }.toSet()
                facts.filter { it.first.categoryId in ids }
            }
            val missing = included.mapNotNull { it.second.missingCurrency }.toSet()
            return BudgetUsage(limit, if (missing.isEmpty()) included.fold(BigInteger.ZERO) { sum, item -> sum + requireNotNull(item.second.minor) } else null, missing)
        }
        val total = usage(budgets.total)
        val remainingDays = ChronoUnit.DAYS.between(maxOf(today, period.start), period.endExclusive).coerceAtLeast(0).toInt()
        return BudgetSummary(total, budgets.categories.associate { it.categoryId to usage(it.limit, it.categoryId) }, remainingDays,
            total.remaining?.takeIf { remainingDays > 0 }?.max(BigInteger.ZERO)?.divide(BigInteger.valueOf(remainingDays.toLong())),
            facts.mapNotNull { it.second.rate }.distinct())
    }
}
