package app.omymoney.core.ledger

import app.omymoney.core.model.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CreditCycleTest {
    @Test fun monthlyDaysClampWithoutRollingIntoAnExtraMonthIncludingLeapYears() {
        val terms = CreditTerms(31, CreditDueMode.DAY_OF_MONTH, 5)
        assertEquals(CreditCycle(LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-28"), LocalDate.parse("2026-03-05")),
            CreditCycles.latestStatement(terms, LocalDate.parse("2026-03-01")))
        assertEquals(LocalDate.parse("2024-02-29"), CreditCycles.latestStatement(terms, LocalDate.parse("2024-02-29")).statement)
        assertEquals(LocalDate.parse("2025-12-31"), CreditCycles.latestStatement(terms, LocalDate.parse("2026-01-01")).statement)
    }
    @Test fun daysAfterStatementAreCalendarDaysAndInvalidTermsAreRejected() {
        val terms = CreditTerms(28, CreditDueMode.DAYS_AFTER_STATEMENT, 20, Money(100000, CurrencySpec.CNY))
        assertEquals(LocalDate.parse("2026-03-20"), CreditCycles.latestStatement(terms, LocalDate.parse("2026-03-01")).due)
        assertTrue(CreditCycles.validate(terms, CurrencySpec.CNY))
        listOf(terms.copy(statementDay = 0), terms.copy(statementDay = 32), terms.copy(dueValue = 0), terms.copy(dueValue = 91),
            terms.copy(dueMode = CreditDueMode.DAY_OF_MONTH, dueValue = 32), terms.copy(creditLimit = Money(-1, CurrencySpec.CNY)),
            terms.copy(creditLimit = Money(100, CurrencySpec("USD", 2)))).forEach { assertFalse(CreditCycles.validate(it, CurrencySpec.CNY)) }
    }
}
