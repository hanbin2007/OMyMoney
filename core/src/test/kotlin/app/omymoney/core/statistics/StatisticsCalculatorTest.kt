package app.omymoney.core.statistics

import app.omymoney.core.ledger.EconomicKind
import app.omymoney.core.model.*
import java.math.BigInteger
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class StatisticsCalculatorTest {
    private val cny = CurrencySpec.CNY
    private val usd = CurrencySpec("USD", 2)
    private val range = StatisticsRange("book", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), ZoneOffset.UTC)
    private fun bill(id: String, minor: Long, kind: BillKind = BillKind.EXPENSE,
        time: String = "2026-10-11T12:00:00Z", currency: CurrencySpec = cny) = Bill(
        id, 1, Instant.EPOCH, "submission-$id", "book", kind, Money(minor, currency), Instant.parse(time),
        TimeSource.USER, ZoneOffset.UTC, fromAccountId = "cash", toAccountId = if (kind == BillKind.TRANSFER) "bank" else null,
        categoryId = "food", tags = listOf("trip"), note = "Lunch")

    @Test fun filtersApplyOnceToValuedEntriesAndEveryViewAgrees() {
        val bills = listOf(bill("one", 1200), bill("two", 800).copy(categoryId = "lunch"),
            bill("not-tag", 999).copy(tags = emptyList()), bill("not-account", 999).copy(fromAccountId = "other"),
            bill("not-note", 999).copy(note = "Dinner"), bill("not-category", 999).copy(categoryId = "travel"),
            bill("not-kind", 999, BillKind.INCOME), bill("other-book", 999).copy(bookId = "other"))
        val categories = listOf(Category("food", "book", "Food", BillKind.EXPENSE), Category("lunch", "book", "Lunch", BillKind.EXPENSE, "food"))
        val result = StatisticsCalculator.summarize(bills, range, cny,
            StatisticsFilter(EconomicKind.EXPENSE, "cash", "food", "trip", "lUnCh"), categories)
        assertEquals(BigInteger.valueOf(2000), result.totals.expense.minor)
        assertEquals(setOf("one", "two"), result.entries.map { it.fact.bill.id }.toSet())
        assertEquals(listOf(BigInteger.valueOf(1200), BigInteger.valueOf(800)), result.categories.map { it.amount.minor })
        assertEquals(BigInteger.valueOf(2000), result.days.single { it.date.dayOfMonth == 11 }.totals.expense.minor)
        assertEquals(BigInteger.valueOf(2000), result.months.single().totals.expense.minor)
        assertEquals(31, result.days.size)
    }

    @Test fun statisticsExclusionDoesNotUseBudgetExclusionAndTransfersNeverBecomeIncome() {
        val result = StatisticsCalculator.summarize(listOf(bill("included", 100).copy(excludeBudget = true),
            bill("excluded", 500).copy(excludeStats = true), bill("transfer", 2000, BillKind.TRANSFER).copy(fee = Money(20, cny)),
            bill("income", 300, BillKind.INCOME), bill("principal", 5000, BillKind.DEBT_BORROW)), range, cny)
        assertEquals(BigInteger.valueOf(120), result.totals.expense.minor)
        assertEquals(BigInteger.valueOf(300), result.totals.income.minor)
        assertEquals(BigInteger.valueOf(2000), result.totals.transfer.minor)
        assertEquals(BigInteger.valueOf(180), result.totals.net)
        assertEquals(4, result.entries.size)
    }

    @Test fun timezoneHalfOpenBoundariesAndAnnualMonthsAreStable() {
        val year = range.copy(start = LocalDate.of(2024, 1, 1), endExclusive = LocalDate.of(2025, 1, 1), zone = ZoneId.of("America/New_York"))
        val result = StatisticsCalculator.summarize(listOf(bill("before", 999, time = "2024-01-01T04:59:59Z"),
            bill("first", 100, time = "2024-01-01T05:00:00Z"), bill("last", 200, time = "2025-01-01T04:59:59Z"),
            bill("after", 999, time = "2025-01-01T05:00:00Z")), year, cny)
        assertEquals(BigInteger.valueOf(300), result.totals.expense.minor)
        assertEquals(366, result.days.size); assertEquals(12, result.months.size)
        assertEquals(BigInteger.valueOf(100), result.months.first().totals.expense.minor)
        assertEquals(BigInteger.valueOf(200), result.months.last().totals.expense.minor)
    }

    @Test fun refundSourceAndPriorRefundsSurviveFiltersWhileCurrentRefundCanBeNegative() {
        val source = bill("source", 10000, time = "2026-09-01T00:00:00Z").copy(tags = emptyList())
        val earlier = bill("earlier", 8000, BillKind.REFUND, "2026-09-02T00:00:00Z").copy(linkedBillId = source.id)
        val current = bill("current", 5000, BillKind.REFUND).copy(linkedBillId = source.id, categoryId = null)
        val result = StatisticsCalculator.summarize(listOf(source, earlier, current), range, cny, StatisticsFilter(tag = "trip"))
        assertEquals(BigInteger.valueOf(-2000), result.totals.expense.minor)
        assertEquals(BigInteger.valueOf(3000), result.totals.income.minor)
        assertEquals(BigInteger.valueOf(5000), result.totals.net)
        assertEquals("food", result.categories.single { it.kind == EconomicKind.EXPENSE }.categoryId)
        assertEquals(2, result.entries.size); assertEquals(2, result.entries.map { it.key }.distinct().size)
    }

    @Test fun missingRatesAreUnknownOnlyInAffectedGroupsAndNeverMixedWithNominalMinorUnits() {
        val result = StatisticsCalculator.summarize(listOf(bill("known", 100), bill("unknown", 100, currency = usd),
            bill("income", 500, BillKind.INCOME, "2026-10-12T00:00:00Z")), range, cny)
        assertNull(result.totals.expense.minor)
        assertEquals(setOf(usd), result.totals.expense.missingCurrencies)
        assertEquals(BigInteger.valueOf(500), result.totals.income.minor); assertNull(result.totals.net)
        assertNull(result.categories.single { it.kind == EconomicKind.EXPENSE }.amount.minor)
        assertNull(result.days.single { it.date.dayOfMonth == 11 }.totals.expense.minor)
        assertEquals(BigInteger.ZERO, result.days.single { it.date.dayOfMonth == 12 }.totals.expense.minor)
    }

    @Test fun eachValuationRoundsOnceAndAllGroupSumsReuseIt() {
        val quote = ManualExchangeRate(usd, cny, "0.5", LocalDate.of(2026, 10, 1))
        val result = StatisticsCalculator.summarize(listOf(bill("one", 1, currency = usd), bill("two", 3, currency = usd),
            bill("actual", 100, currency = usd).copy(exchange = ExchangeSnapshot("cash", Money(99, cny), "0.99", quote.asOf))),
            range, cny, rates = listOf(quote))
        assertEquals(setOf(BigInteger.ZERO, BigInteger.valueOf(2), BigInteger.valueOf(99)), result.entries.map { it.valuation.minor }.toSet())
        assertEquals(BigInteger.valueOf(101), result.totals.expense.minor)
        assertEquals(BigInteger.valueOf(101), result.categories.single().amount.minor)
        assertEquals(BigInteger.valueOf(101), result.days.single { it.date.dayOfMonth == 11 }.totals.expense.minor)
        assertEquals(listOf(quote), result.ratesUsed)
    }

    @Test fun emptyAndLargerThanLongValuesRemainExact() {
        val empty = StatisticsCalculator.summarize(emptyList(), range, cny)
        assertEquals(BigInteger.ZERO, empty.totals.net)
        assertTrue(empty.entries.isEmpty()); assertTrue(empty.categories.isEmpty()); assertEquals(31, empty.days.size)
        val large = StatisticsCalculator.summarize(listOf(bill("one", Long.MAX_VALUE), bill("two", Long.MAX_VALUE)), range, cny)
        assertEquals(BigInteger("18446744073709551614"), large.totals.expense.minor)
    }

    @Test fun invalidAndUnboundedRangesAreRejected() {
        listOf(range.copy(endExclusive = range.start), range.copy(endExclusive = range.start.plusYears(2))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { StatisticsCalculator.summarize(emptyList(), invalid, cny) }
        }
    }
}
