package app.omymoney.core.savings

import app.omymoney.core.model.*
import app.omymoney.core.schedule.ScheduleFrequency
import java.math.BigInteger
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SavingsCalculatorTest {
    private val cny = CurrencySpec.CNY
    private val start = LocalDate.parse("2024-01-31")
    private fun rule(mode: SavingsMode = SavingsMode.FIXED) = SavingsRule(mode, Money(10000, cny), start, count = 3, incrementMinor = 1000)
    private fun plan(rule: SavingsRule = rule()) = SavingsPlan(id = "plan", bookId = "book", name = "Travel", rule = rule, zone = ZoneOffset.UTC)
    private fun deposit(id: String, amount: Long) = SavingsDeposit(id, "plan", start, Money(amount, cny))
    private fun transfer(amount: Money = Money(10000, cny)) = Bill(id = "bill", revision = 1,
        savedAt = Instant.parse("2024-01-31T00:00:00Z"), submissionId = "receipt", bookId = "book", kind = BillKind.TRANSFER,
        amount = amount, occurredAt = Instant.parse("2024-01-31T00:00:00Z"), timeSource = TimeSource.USER,
        zoneId = ZoneOffset.UTC, fromAccountId = "cash", toAccountId = "bank")

    @Test fun fixedIncreasingDecreasingAndCustomTargetsUseExactMinorUnits() {
        assertEquals(30000L, SavingsCalculator.target(rule()).minor)
        assertEquals(33000L, SavingsCalculator.target(rule(SavingsMode.INCREASING)).minor)
        assertEquals(listOf(10000L, 9000L, 8000L), SavingsCalculator.steps(rule(SavingsMode.DECREASING)).map { it.amount.minor })
        assertEquals(27000L, SavingsCalculator.target(rule(SavingsMode.DECREASING)).minor)
        assertEquals(listOf(10000L, 0L, 0L), SavingsCalculator.steps(rule(SavingsMode.DECREASING).copy(incrementMinor = Long.MAX_VALUE)).map { it.amount.minor })
        assertEquals(10000L, SavingsCalculator.target(rule(SavingsMode.CUSTOM)).minor)
        assertTrue(SavingsCalculator.steps(rule(SavingsMode.CUSTOM)).isEmpty())
    }

    @Test fun calendarAnchorsDoNotDriftAndLongPlansAreBounded() {
        assertEquals(listOf("2024-01-31", "2024-02-29", "2024-03-31"), SavingsCalculator.steps(rule()).map { it.date.toString() })
        val yearly = rule().copy(start = LocalDate.parse("2024-02-29"), count = 5, frequency = ScheduleFrequency.YEARLY)
        assertEquals(LocalDate.parse("2028-02-29"), SavingsCalculator.steps(yearly).last().date)
        assertEquals(1000, SavingsCalculator.steps(rule().copy(count = 1000, frequency = ScheduleFrequency.DAILY)).size)
        assertTrue(runCatching { SavingsCalculator.target(rule().copy(count = 1001)) }.isFailure)
    }

    @Test fun editingAndRemovingRecordsRecomputesProgressAndEarlyCompletionIsClamped() {
        val p = plan(rule(SavingsMode.CUSTOM))
        val a = deposit("a", 6000); val b = deposit("b", 5000)
        val complete = SavingsCalculator.progress(p, listOf(a, b))
        assertEquals(BigInteger.valueOf(11000), complete.savedMinor)
        assertEquals(BigInteger.ZERO, complete.remainingMinor)
        assertEquals(BigInteger.valueOf(1000), complete.excessMinor)
        assertEquals(1f, complete.fraction)
        assertTrue(complete.completed)
        assertEquals(BigInteger.valueOf(2000), SavingsCalculator.progress(p, listOf(a, b.copy(amount = Money(2000, cny)))).remainingMinor)
        assertEquals(BigInteger.valueOf(4000), SavingsCalculator.progress(p, listOf(a)).remainingMinor)
        assertEquals(0f, SavingsCalculator.progress(p, emptyList()).fraction)
    }

    @Test fun multipleHugeDepositsDoNotOverflowTheProgressDisplay() {
        val p = plan(rule(SavingsMode.CUSTOM).copy(amount = Money(Long.MAX_VALUE, cny)))
        val result = SavingsCalculator.progress(p, listOf(deposit("a", Long.MAX_VALUE), deposit("b", Long.MAX_VALUE)))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), result.savedMinor)
        assertEquals(1f, result.fraction)
        assertTrue(result.completed)
        assertTrue(runCatching { SavingsCalculator.target(rule().copy(amount = Money(Long.MAX_VALUE, cny))) }.isFailure)
    }

    @Test fun oneRemainingMinorUnitCannotRoundTheIndicatorUpToCompleted() {
        val p = plan(rule(SavingsMode.CUSTOM).copy(amount = Money(Long.MAX_VALUE, cny)))
        val result = SavingsCalculator.progress(p, listOf(deposit("almost", Long.MAX_VALUE - 1)))
        assertEquals(BigInteger.ONE, result.remainingMinor)
        assertFalse(result.completed)
        assertTrue("An incomplete target must never display 100%", result.fraction < 1f)
    }

    @Test fun linkedTransfersFollowActualReceivedMoneyAndSubsequentEdits() {
        val usd = CurrencySpec("USD", 2)
        val bill = transfer(Money(1000, usd)).copy(exchange = ExchangeSnapshot("bank", Money(7200, cny), "7.2", start))
        val d = SavingsDeposit("linked", "plan", start, linkedBillId = bill.id)
        assertEquals(BigInteger.valueOf(7200), SavingsCalculator.progress(plan(), listOf(d), mapOf(bill.id to bill)).savedMinor)
        val changed = bill.copy(exchange = bill.exchange!!.copy(amount = Money(7100, cny), rate = "7.1"))
        assertEquals(BigInteger.valueOf(7100), SavingsCalculator.progress(plan(), listOf(d), mapOf(bill.id to changed)).savedMinor)
        assertEquals(BigInteger.ZERO, SavingsCalculator.progress(plan(), listOf(d)).savedMinor)
        assertEquals(SavingsDepositProblem.MISSING_TRANSFER, SavingsCalculator.progress(plan(), listOf(d)).records.single().problem)
    }

    @Test fun incompatibleReferencesNeverPretendToBeDeposits() {
        val bill = transfer(); val d = SavingsDeposit("linked", "plan", start, linkedBillId = bill.id)
        fun result(b: Bill) = SavingsCalculator.progress(plan(), listOf(d), mapOf(b.id to b)).records.single()
        assertEquals(SavingsDepositProblem.CHANGED_TRANSFER, result(bill.copy(kind = BillKind.EXPENSE)).problem)
        assertEquals(SavingsDepositProblem.DIFFERENT_BOOK, result(bill.copy(bookId = "elsewhere")).problem)
        assertEquals(SavingsDepositProblem.DIFFERENT_CURRENCY, result(bill.copy(amount = Money(1000, CurrencySpec("USD", 2)))).problem)
        assertTrue(runCatching { SavingsCalculator.progress(plan(), listOf(d, d.copy(id = "duplicate")), mapOf(bill.id to bill)) }.isFailure)
        assertTrue(runCatching { SavingsCalculator.progress(plan(), listOf(deposit("wrong", -1))) }.isFailure)
    }

    @Test fun fractionalCurrenciesAndInvalidRulesAreHandledWithoutRounding() {
        val kwd = rule(SavingsMode.INCREASING).copy(amount = Money(1234, CurrencySpec("KWD", 3)), incrementMinor = 1)
        assertEquals("3.705", SavingsCalculator.target(kwd).decimalText())
        assertEquals("3705", SavingsCalculator.target(kwd.copy(amount = Money(1234, CurrencySpec("JPY", 0)))).decimalText())
        listOf(rule().copy(amount = Money(0, cny)), rule().copy(incrementMinor = -1), rule().copy(interval = 0),
            rule(SavingsMode.CUSTOM).copy(end = start.minusDays(1)), rule().copy(start = LocalDate.parse("9998-12-31"))).forEach {
            assertTrue(runCatching { SavingsCalculator.target(it) }.isFailure)
        }
    }
}
