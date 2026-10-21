package app.omymoney.core.ledger

import app.omymoney.core.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ExchangeRulesTest {
    private val usd = CurrencySpec("USD", 2)
    private val cny = CurrencySpec.CNY
    private val date = LocalDate.of(2026, 10, 11)
    private val transfer = TransactionDraft(bookId = "book", kind = BillKind.TRANSFER,
        amount = Money(1000, usd), fromAccountId = "usd", toAccountId = "cny",
        occurredAt = Instant.parse("2026-10-11T00:00:00Z"), timeSource = TimeSource.USER)

    @Test fun savedActualAmountIsIndependentOfLaterRatesAndHasExplainableDifference() {
        val rate = BigDecimal("7.2")
        val received = ExchangeRules.convert(transfer.amount!!, rate, cny)
        assertEquals(Money(7200, cny), received)
        val draft = transfer.copy(exchange = ExchangeSnapshot("cny", received, "7.2", date))
        assertEquals(listOf(Posting("usd", Money(-1000, usd)), Posting("cny", received)), PostingCalculator.calculate(draft))
        assertEquals(Money(8000, cny), ExchangeRules.convert(transfer.amount!!, BigDecimal("8"), cny))
        assertEquals(received, PostingCalculator.calculate(draft).last().amount)
        val actual = draft.copy(exchange = draft.exchange!!.copy(amount = Money(7199, cny)))
        assertEquals(Money(-1, cny), ExchangeRules.difference(actual.amount!!, actual.exchange!!))
        assertEquals(Money(7199, cny), PostingCalculator.calculate(actual).last().amount)
    }

    @Test fun conversionRoundsHalfEvenOnceAtTheDestinationPrecision() {
        val jpy = CurrencySpec("JPY", 0)
        val kwd = CurrencySpec("KWD", 3)
        assertEquals(Money(2, jpy), ExchangeRules.convert(Money(100, usd), BigDecimal("2.5"), jpy))
        assertEquals(Money(4, jpy), ExchangeRules.convert(Money(100, usd), BigDecimal("3.5"), jpy))
        assertEquals(Money(1234, kwd), ExchangeRules.convert(Money(100, usd), BigDecimal("1.2345"), kwd))
        assertEquals(Money(-1236, kwd), ExchangeRules.convert(Money(-100, usd), BigDecimal("1.2355"), kwd))
        assertThrows(ArithmeticException::class.java) { ExchangeRules.convert(Money(Long.MAX_VALUE, usd), BigDecimal.TEN, cny) }
        listOf("0", "-1", "1e100", "NaN", "1.0000000000000000001").forEach {
            assertThrows(IllegalArgumentException::class.java) { ExchangeRules.parseRate(it) }
        }
    }

    @Test fun missingQuoteNeverBecomesAnInventedAggregate() {
        val balances = listOf(Money(1000, usd), Money(300, cny))
        val missing = ExchangeRules.value(balances, cny, emptyList())
        assertNull(missing.total)
        assertEquals(setOf(usd), missing.missingCurrencies)
        val rate = ManualExchangeRate(usd, cny, "7.2", date)
        assertEquals(Money(7500, cny), ExchangeRules.value(balances, cny, listOf(rate)).total)
        assertEquals(listOf(rate), ExchangeRules.value(balances, cny, listOf(rate)).ratesUsed)
        assertEquals(Money(300, cny), ExchangeRules.value(listOf(Money(0, usd), Money(300, cny)), cny, emptyList()).total)
    }

    @Test fun feesAreOneCashDebitAndNeverReduceDebtPrincipal() {
        assertEquals(listOf(Posting("usd", Money(-1010, usd)), Posting("cny", Money(7200, cny))),
            PostingCalculator.calculate(transfer.copy(fee = Money(10, usd), exchange = ExchangeSnapshot("cny", Money(7200, cny), "7.2", date))))
        val borrow = transfer.copy(kind = BillKind.DEBT_BORROW, fromAccountId = "liability", toAccountId = "cash",
            exchange = ExchangeSnapshot("cash", Money(7200, cny), "7.2", date), fee = Money(100, cny))
        assertEquals(listOf(Posting("liability", Money(-1000, usd)), Posting("cash", Money(7100, cny))), PostingCalculator.calculate(borrow))
        val expense = transfer.copy(kind = BillKind.EXPENSE, toAccountId = null, amount = Money(Long.MAX_VALUE, usd), fee = Money(1, usd))
        assertEquals(Long.MIN_VALUE, PostingCalculator.calculate(expense).single().amount.minor)
    }

    @Test fun malformedExchangeAndFeesFailBeforePosting() {
        val snapshot = ExchangeSnapshot("cny", Money(7200, cny), "7.2", date)
        listOf(transfer.copy(exchange = snapshot.copy(accountId = "absent")),
            transfer.copy(exchange = snapshot.copy(amount = Money(1, usd))),
            transfer.copy(exchange = snapshot.copy(amount = Money(0, cny))),
            transfer.copy(exchange = snapshot.copy(rate = "0")),
            transfer.copy(exchange = snapshot.copy(accountId = "usd")),
            transfer.copy(fee = Money(-1, usd)), transfer.copy(fee = Money(1, cny)),
            transfer.copy(kind = BillKind.ADJUSTMENT, toAccountId = null, note = "reason", fee = Money(1, usd)),
            transfer.copy(kind = BillKind.DEBT_REPAY, exchange = snapshot),
        ).forEach { assertThrows(DraftValidationException::class.java) { PostingCalculator.calculate(it) } }
    }
}
