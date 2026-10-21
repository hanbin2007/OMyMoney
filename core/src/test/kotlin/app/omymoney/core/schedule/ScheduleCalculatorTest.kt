package app.omymoney.core.schedule

import app.omymoney.core.model.CurrencySpec
import app.omymoney.core.model.Money
import java.time.*
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ScheduleCalculatorTest {
    private val utc = ZoneOffset.UTC
    private fun through(date: String) = LocalDate.parse(date).plusDays(1).atStartOfDay(utc).toInstant().minusNanos(1)

    @Test fun monthlyAnchorReturnsTo31AfterShortMonths() {
        val rule = ScheduleRule(LocalDate.parse("2024-01-31"), zone = utc)
        val occurrences = ScheduleCalculator.dueOccurrences(rule, through("2024-04-30"))
        assertEquals(listOf("2024-01-31", "2024-02-29", "2024-03-31", "2024-04-30"), occurrences.map { it.date.toString() })
        assertEquals(listOf(0, 1, 2, 3), occurrences.map { it.index })
    }

    @Test fun yearlyLeapAnchorReturnsTo29() {
        val rule = ScheduleRule(LocalDate.parse("2024-02-29"), zone = utc, frequency = ScheduleFrequency.YEARLY)
        assertEquals(listOf(29, 28, 28, 28, 29), ScheduleCalculator.dueOccurrences(rule, through("2028-03-01")).map { it.date.dayOfMonth })
    }

    @Test fun dstGapAndOverlapProduceOneStableOccurrence() {
        val zone = ZoneId.of("America/New_York")
        val gap = ScheduleRule(LocalDate.parse("2024-03-09"), "02:30", zone, ScheduleFrequency.DAILY, count = 3)
        assertEquals(listOf("2024-03-09T07:30:00Z", "2024-03-10T07:30:00Z", "2024-03-11T06:30:00Z"),
            ScheduleCalculator.dueOccurrences(gap, through("2024-03-12")).map { it.plannedAt.toString() })
        val overlap = gap.copy(startDate = LocalDate.parse("2024-11-03"), localTime = "01:30", count = 1)
        val first = ScheduleCalculator.dueOccurrences(overlap, Instant.parse("2024-11-03T05:30:00Z"))
        assertEquals(listOf("2024-11-03T05:30:00Z"), first.map { it.plannedAt.toString() })
        assertEquals(first, ScheduleCalculator.dueOccurrences(overlap, Instant.parse("2024-11-03T06:30:00Z")))
    }

    @Test fun changingDeviceTimezoneDoesNotMoveThePinnedPlan() {
        val old = TimeZone.getDefault()
        try {
            val rule = ScheduleRule(LocalDate.parse("2024-01-01"), "09:00", ZoneId.of("Asia/Hong_Kong"), count = 1)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val first = ScheduleCalculator.dueOccurrences(rule, through("2024-01-02"))
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            assertEquals(listOf("2024-01-01T01:00:00Z"), first.map { it.plannedAt.toString() })
            assertEquals(first, ScheduleCalculator.dueOccurrences(rule, through("2024-01-02")))
        } finally { TimeZone.setDefault(old) }
    }

    @Test fun boundariesIntervalsAndPaginationAreExplicit() {
        val rule = ScheduleRule(LocalDate.parse("2024-01-01"), zone = utc, frequency = ScheduleFrequency.WEEKLY, interval = 2,
            endDate = LocalDate.parse("2024-01-29"))
        assertEquals(listOf("2024-01-01", "2024-01-15", "2024-01-29"), ScheduleCalculator.dueOccurrences(rule, through("2025-01-01")).map { it.date.toString() })
        assertEquals(2, ScheduleCalculator.dueOccurrences(rule.copy(count = 2), through("2025-01-01")).size)
        assertTrue(ScheduleCalculator.dueOccurrences(rule, Instant.parse("2024-01-01T08:59:59Z")).isEmpty())
        val daily = rule.copy(frequency = ScheduleFrequency.DAILY, interval = 1, endDate = null)
        val batch = ScheduleCalculator.dueOccurrences(daily, through("2026-01-01"))
        assertEquals(512, batch.size)
        val next = ScheduleCalculator.dueOccurrences(daily, through("2026-01-01"), fromIndex = 512, limit = 3)
        assertEquals(listOf(512, 513, 514), next.map { it.index })
        assertTrue(batch.map { it.plannedAt }.intersect(next.map { it.plannedAt }.toSet()).isEmpty())
    }

    @Test fun installmentsUseTheLastPaymentForTheExactRemainder() {
        assertEquals(listOf(3333L, 3333L, 3334L), ScheduleCalculator.installments(Money(10000, CurrencySpec.CNY), 3).map { it.minor })
        assertEquals(listOf(333L, 333L, 334L), ScheduleCalculator.installments(Money(1000, CurrencySpec("JPY", 0)), 3).map { it.minor })
        val large = ScheduleCalculator.installments(Money(Long.MAX_VALUE, CurrencySpec.CNY), 999)
        assertEquals(Long.MAX_VALUE.toBigInteger(), large.fold(java.math.BigInteger.ZERO) { sum, it -> sum + it.minor.toBigInteger() })
    }

    @Test fun invalidRulesAndZeroInstallmentsAreRejected() {
        val rule = ScheduleRule(LocalDate.parse("2024-01-01"), zone = utc)
        listOf(rule.copy(interval = 0), rule.copy(count = 0), rule.copy(localTime = "25:00"),
            rule.copy(endDate = LocalDate.parse("2023-12-31"))).forEach {
            assertThrows(IllegalArgumentException::class.java) { ScheduleCalculator.dueOccurrences(it, through("2024-02-01")) }
        }
        assertThrows(IllegalArgumentException::class.java) { ScheduleCalculator.installments(Money(2, CurrencySpec.CNY), 3) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleCalculator.installments(Money(100, CurrencySpec.CNY), 0) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleCalculator.dueOccurrences(rule, through("2024-02-01"), fromIndex = -1) }
    }
}
