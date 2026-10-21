package app.omymoney.core.statistics

import app.omymoney.core.ledger.*
import app.omymoney.core.model.*
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class StatisticsRange(val bookId: String, val start: LocalDate, val endExclusive: LocalDate, val zone: ZoneId)
data class StatisticsFilter(val kind: EconomicKind? = null, val accountId: String? = null,
    val categoryId: String? = null, val tag: String = "", val search: String = "")
data class StatisticsAmount(val minor: BigInteger?, val missingCurrencies: Set<CurrencySpec> = emptySet())
data class StatisticsEntry(val fact: EconomicFact, val date: LocalDate, val valuation: FactValuation) {
    val key get() = "${fact.bill.id}/${fact.kind}/${fact.fee}"
}
data class StatisticsTotals(val income: StatisticsAmount, val expense: StatisticsAmount, val transfer: StatisticsAmount) {
    val net get() = if (income.minor == null || expense.minor == null) null else income.minor - expense.minor
    operator fun get(kind: EconomicKind) = when (kind) {
        EconomicKind.INCOME -> income; EconomicKind.EXPENSE -> expense; EconomicKind.TRANSFER -> transfer
    }
}
data class StatisticsDay(val date: LocalDate, val totals: StatisticsTotals)
data class StatisticsMonth(val month: YearMonth, val totals: StatisticsTotals)
data class StatisticsCategory(val kind: EconomicKind, val categoryId: String?, val amount: StatisticsAmount)
data class StatisticsSummary(val range: StatisticsRange, val currency: CurrencySpec, val totals: StatisticsTotals,
    val entries: List<StatisticsEntry>, val categories: List<StatisticsCategory>, val days: List<StatisticsDay>,
    val months: List<StatisticsMonth>, val ratesUsed: List<ManualExchangeRate>)

object StatisticsCalculator {
    fun summarize(bills: List<Bill>, range: StatisticsRange, baseCurrency: CurrencySpec,
        filter: StatisticsFilter = StatisticsFilter(), categories: List<Category> = emptyList(),
        rates: List<ManualExchangeRate> = emptyList()): StatisticsSummary {
        val dayCount = java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endExclusive)
        require(dayCount in 1..366) { "Statistics range must contain 1 to 366 days" }
        val categoryIds = filter.categoryId?.let { id ->
            categories.filter { it.bookId == range.bookId && it.parentId == id }.map { it.id }.toSet() + id
        }
        // Classify against complete history before excluding sources or earlier refunds.
        val entries = EconomicFacts.from(bills.filter { it.bookId == range.bookId }).mapNotNull { fact ->
            val bill = fact.bill
            val date = bill.occurredAt.atZone(range.zone).toLocalDate()
            if (bill.excludeStats || date < range.start || date >= range.endExclusive ||
                (filter.kind != null && filter.kind != fact.kind) ||
                (filter.accountId != null && filter.accountId != bill.fromAccountId && filter.accountId != bill.toAccountId) ||
                (categoryIds != null && fact.categoryId !in categoryIds) ||
                (filter.tag.isNotBlank() && filter.tag.trim() !in bill.tags) ||
                (filter.search.isNotBlank() && !bill.note.contains(filter.search.trim(), ignoreCase = true))) null
            else StatisticsEntry(fact, date, EconomicFacts.value(fact, baseCurrency, rates))
        }.sortedWith(compareByDescending<StatisticsEntry> { it.fact.bill.occurredAt }.thenBy { it.key })
        val byDate = entries.groupBy { it.date }
        val byMonth = entries.groupBy { YearMonth.from(it.date) }
        val days = (0 until dayCount).map { range.start.plusDays(it) }.map { StatisticsDay(it, totals(byDate[it].orEmpty())) }
        val months = days.map { YearMonth.from(it.date) }.distinct().map { StatisticsMonth(it, totals(byMonth[it].orEmpty())) }
        val grouped = entries.groupBy { it.fact.kind to it.fact.categoryId }.map { (key, values) ->
            StatisticsCategory(key.first, key.second, amount(values))
        }.sortedWith(compareBy<StatisticsCategory> { it.kind.ordinal }.thenByDescending { it.amount.minor?.abs() }.thenBy { it.categoryId })
        return StatisticsSummary(range, baseCurrency, totals(entries), entries, grouped, days, months,
            entries.mapNotNull { it.valuation.rate }.distinct())
    }

    private fun amount(entries: List<StatisticsEntry>): StatisticsAmount {
        val missing = entries.mapNotNull { it.valuation.missingCurrency }.toSet()
        return StatisticsAmount(if (missing.isEmpty()) entries.fold(BigInteger.ZERO) { sum, entry -> sum + requireNotNull(entry.valuation.minor) }
            else null, missing)
    }

    private fun totals(entries: List<StatisticsEntry>) = StatisticsTotals(
        amount(entries.filter { it.fact.kind == EconomicKind.INCOME }),
        amount(entries.filter { it.fact.kind == EconomicKind.EXPENSE }),
        amount(entries.filter { it.fact.kind == EconomicKind.TRANSFER }))
}
