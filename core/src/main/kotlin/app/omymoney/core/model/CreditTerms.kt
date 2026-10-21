package app.omymoney.core.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

@Serializable enum class CreditDueMode { DAY_OF_MONTH, DAYS_AFTER_STATEMENT }
@Serializable data class CreditTerms(
    val statementDay: Int,
    val dueMode: CreditDueMode,
    val dueValue: Int,
    val creditLimit: Money? = null,
)
data class CreditCycle(val start: LocalDate, val statement: LocalDate, val due: LocalDate)

object CreditCycles {
    fun validate(terms: CreditTerms, currency: CurrencySpec): Boolean = terms.statementDay in 1..31 &&
        terms.dueValue in 1..(if (terms.dueMode == CreditDueMode.DAY_OF_MONTH) 31 else 90) &&
        (terms.creditLimit == null || (terms.creditLimit.currency == currency && terms.creditLimit.minor >= 0))

    fun latestStatement(terms: CreditTerms, today: LocalDate): CreditCycle {
        require(terms.statementDay in 1..31 && terms.dueValue in 1..(if (terms.dueMode == CreditDueMode.DAY_OF_MONTH) 31 else 90))
        fun day(month: YearMonth, value: Int) = month.atDay(minOf(value, month.lengthOfMonth()))
        val month = YearMonth.from(today).let { if (day(it, terms.statementDay) > today) it.minusMonths(1) else it }
        val statement = day(month, terms.statementDay)
        val due = if (terms.dueMode == CreditDueMode.DAYS_AFTER_STATEMENT) statement.plusDays(terms.dueValue.toLong())
            else day(month, terms.dueValue).let { if (it <= statement) day(month.plusMonths(1), terms.dueValue) else it }
        return CreditCycle(day(month.minusMonths(1), terms.statementDay).plusDays(1), statement, due)
    }
}
