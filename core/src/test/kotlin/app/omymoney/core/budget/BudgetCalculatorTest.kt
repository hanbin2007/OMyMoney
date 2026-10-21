package app.omymoney.core.budget

import app.omymoney.core.model.*
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class BudgetCalculatorTest {
    private val cny = CurrencySpec.CNY
    private val usd = CurrencySpec("USD", 2)
    private val period = BudgetPeriod(2026, 10)
    private val plan = BudgetPlan("book", period, Money(100000, cny))
    private fun bill(id: String, minor: Long, kind: BillKind = BillKind.EXPENSE,
                     time: String = "2026-10-11T12:00:00Z", currency: CurrencySpec = cny): Bill = Bill(
        id, 1, Instant.EPOCH, "submission-$id", "book", kind, Money(minor, currency), Instant.parse(time),
        TimeSource.USER, ZoneOffset.UTC, fromAccountId = "cash", toAccountId = if (kind == BillKind.TRANSFER) "other" else null,
    )
    private fun calculate(bills: List<Bill>, budget: BudgetPlan = plan) = BudgetCalculator.calculate(
        budget.period, bills, budget, today = LocalDate.of(2026, 10, 11))

    @Test fun consumptionExclusionsAndRemainingDailyShareOneCalculation() {
        val result = calculate(listOf(bill("expense", 12000).copy(excludeStats = true),
            bill("excluded", 5000).copy(excludeBudget = true), bill("other-book", 5000).copy(bookId = "other")))
        assertEquals(BigInteger.valueOf(12000), result.total.used)
        assertEquals(BigInteger.valueOf(88000), result.total.remaining)
        assertEquals(21, result.daysRemaining)
        assertEquals(BigInteger.valueOf(4190), result.dailyAvailable)
        assertEquals(BigInteger.valueOf(10000), calculate(listOf(bill("overspent", 110000))).total.overspent)
    }

    @Test fun categoryAllocationsCannotSilentlyRaiseTotalOrDoubleCountChildren() {
        val categories = listOf(Category("food", "book", "Food", BillKind.EXPENSE),
            Category("lunch", "book", "Lunch", BillKind.EXPENSE, "food"),
            Category("travel", "book", "Travel", BillKind.EXPENSE))
        val limited = plan.copy(categories = listOf(BudgetLimit("food", Money(60000, cny)),
            BudgetLimit("lunch", Money(20000, cny)), BudgetLimit("travel", Money(40000, cny))))
        assertTrue(BudgetCalculator.validate(limited, categories).isEmpty())
        val result = BudgetCalculator.calculate(period, listOf(bill("lunch", 12000).copy(categoryId = "lunch")), limited, categories)
        assertEquals(BigInteger.valueOf(12000), result.categories.getValue("food").used)
        assertEquals(BigInteger.valueOf(12000), result.categories.getValue("lunch").used)
        assertEquals(BigInteger.valueOf(12000), result.total.used)
        assertTrue(BudgetCalculator.validate(limited.copy(total = Money(99999, cny)), categories).any { it.code == "category_budget_exceeds_total" })
        assertTrue(BudgetCalculator.validate(limited.copy(categories = limited.categories.map {
            if (it.categoryId == "lunch") it.copy(limit = Money(60001, cny)) else it
        }), categories).any { it.code == "child_budget_exceeds_parent" })
    }

    @Test fun periodsRespectYearLeapDayTimezoneAndInclusiveRemainingDays() {
        assertEquals(BudgetPeriod(2025, 12), BudgetPeriod(2026, 1).previous())
        assertEquals(BudgetPeriod(2025), BudgetPeriod(2026).previous())
        val feb = BudgetPeriod(2024, 2)
        assertEquals(LocalDate.of(2024, 3, 1), feb.endExclusive)
        val budget = plan.copy(period = feb)
        val result = BudgetCalculator.calculate(feb, listOf(
            bill("last-day", 100, time = "2024-03-01T04:59:59Z"), bill("next-month", 200, time = "2024-03-01T05:00:00Z")),
            budget, zone = ZoneId.of("America/New_York"), today = LocalDate.of(2024, 2, 29))
        assertEquals(BigInteger.valueOf(100), result.total.used)
        assertEquals(1, result.daysRemaining)
        val past = BudgetCalculator.calculate(feb, emptyList(), budget, today = LocalDate.of(2024, 3, 1))
        assertEquals(0, past.daysRemaining)
        assertNull(past.dailyAvailable)
        assertEquals(366, BudgetCalculator.calculate(BudgetPeriod(2024), emptyList(), plan.copy(period = BudgetPeriod(2024)),
            today = LocalDate.of(2023, 12, 31)).daysRemaining)
    }

    @Test fun refundAllocationUsesWholeHistoryButEffectStaysOnRefundDateAndFeesRemainExpenses() {
        val source = bill("source", 10000, time = "2026-09-10T12:00:00Z").copy(categoryId = "food")
        val first = bill("first", 8000, BillKind.REFUND, "2026-09-11T12:00:00Z").copy(linkedBillId = source.id)
        val refund = bill("refund", 5000, BillKind.REFUND).copy(linkedBillId = source.id, fee = Money(100, cny))
        val principal = bill("principal", 100000, BillKind.DEBT_BORROW).copy(fee = Money(200, cny))
        val reimbursement = bill("advance", 5000, BillKind.REIMBURSEMENT)
        val transfer = bill("transfer", 20000, BillKind.TRANSFER).copy(fee = Money(50, cny))
        assertEquals(BigInteger.valueOf(-1650), calculate(listOf(source, first, refund, principal, reimbursement, transfer)).total.used)
    }

    @Test fun savedActualCurrencyIsUsedWithoutGlobalRepricingAndAbsentQuotesNeverBecomeOne() {
        val foreign = bill("foreign", 1000, currency = usd).copy(exchange = ExchangeSnapshot("cash", Money(7199, cny), "7.2", LocalDate.of(2026, 10, 10)))
        val rate = ManualExchangeRate(usd, cny, "8", LocalDate.of(2026, 10, 11))
        assertEquals(BigInteger.valueOf(7199), BudgetCalculator.calculate(period, listOf(foreign), plan, rates = listOf(rate)).total.used)
        val missing = calculate(listOf(bill("unquoted", 1000, currency = usd)))
        assertNull(missing.total.used); assertNull(missing.total.remaining); assertNull(missing.dailyAvailable)
        assertEquals(setOf(usd), missing.total.missingCurrencies)
        val quoted = BudgetCalculator.calculate(period, listOf(bill("quoted", 1000, currency = usd)), plan, rates = listOf(rate))
        assertEquals(BigInteger.valueOf(8000), quoted.total.used)
        assertEquals(listOf(rate), quoted.ratesUsed)
    }

    @Test fun partialRefundActualSettlementIsSplitWithoutLosingRoundingRemainder() {
        val source = bill("source", 100, time = "2026-09-01T00:00:00Z", currency = usd)
        val refund = bill("refund", 300, BillKind.REFUND, currency = usd).copy(linkedBillId = source.id,
            exchange = ExchangeSnapshot("cash", Money(100, cny), "0.33333333", LocalDate.of(2026, 10, 11)))
        assertEquals(BigInteger.valueOf(-33), calculate(listOf(source, refund)).total.used)
    }

    @Test fun derivedUsageDoesNotOverflowEvenWhenManyValidBillsExceedLong() {
        val summary = calculate(listOf(bill("one", Long.MAX_VALUE), bill("two", Long.MAX_VALUE)))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2)), summary.total.used)
        assertEquals(BigInteger.ZERO, summary.dailyAvailable)
    }
}
