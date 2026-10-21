package app.omymoney.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    private val cny = CurrencySpec("CNY", 2)

    @Test fun decimalAdditionIsExact() {
        val sum = Money.parse("0.10", cny) + Money.parse("0.20", cny)
        assertEquals(30L, sum.minor)
        assertEquals("0.30", sum.decimalText())
    }

    @Test fun fractionalPrecisionCannotBeSilentlyDiscarded() {
        assertThrows(IllegalArgumentException::class.java) { Money.parse("1.001", cny) }
    }

    @Test fun currenciesCannotBeMixed() {
        assertThrows(IllegalArgumentException::class.java) {
            Money(100, cny) + Money(100, CurrencySpec("USD", 2))
        }
    }

    @Test fun arithmeticAndParsingRejectOverflow() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE, cny) + Money(1, cny) }
        assertThrows(ArithmeticException::class.java) { Money(Long.MIN_VALUE, cny) - Money(1, cny) }
        assertThrows(ArithmeticException::class.java) { -Money(Long.MIN_VALUE, cny) }
        assertThrows(IllegalArgumentException::class.java) { Money.parse("92233720368547758.08", cny) }
    }

    @Test fun currencyPrecisionIsRespected() {
        assertEquals(123L, Money.parse("123", CurrencySpec("JPY", 0)).minor)
        assertEquals(1234L, Money.parse("1.234", CurrencySpec("KWD", 3)).minor)
        assertEquals("-12.30", Money(-1230, cny).decimalText())
    }

    @Test fun subtractionAndNegationKeepSignedPostings() {
        assertEquals(8770L, (Money(10000, cny) - Money(1230, cny)).minor)
        assertEquals(-1230L, (-Money(1230, cny)).minor)
    }

    @Test fun supportsDecimalKeyboardInputWithoutAcceptingExponentOrGrouping() {
        assertEquals(50L, Money.parse(".50", cny).minor)
        assertEquals(100L, Money.parse("1.", cny).minor)
        assertEquals(-50L, Money.parse(" -0.50 ", cny).minor)
        listOf("", ".", "NaN", "1e2", "1,000", "12 30", "--1", "1.2.3").forEach { text ->
            assertThrows(text, IllegalArgumentException::class.java) { Money.parse(text, cny) }
        }
    }

    @Test fun invalidCurrencyDefinitionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CurrencySpec("cny", 2) }
        assertThrows(IllegalArgumentException::class.java) { CurrencySpec("CNY", -1) }
        assertThrows(IllegalArgumentException::class.java) { CurrencySpec("CNY", 7) }
    }
}
