package app.omymoney.core.model

import java.math.BigDecimal
import kotlinx.serialization.Serializable

@Serializable
data class CurrencySpec(val code: String, val scale: Int) {
    init {
        require(code.matches(Regex("[A-Z]{3}"))) { "Currency code must be three uppercase letters" }
        require(scale in 0..6) { "Currency scale must be between zero and six" }
    }
    companion object { val CNY = CurrencySpec("CNY", 2) }
}

@Serializable
data class Money(val minor: Long, val currency: CurrencySpec) {
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Currency mismatch" }
        return copy(minor = Math.addExact(minor, other.minor))
    }
    operator fun minus(other: Money): Money {
        require(currency == other.currency) { "Currency mismatch" }
        return copy(minor = Math.subtractExact(minor, other.minor))
    }
    operator fun unaryMinus(): Money = copy(minor = Math.negateExact(minor))
    fun decimalText(): String = BigDecimal.valueOf(minor, currency.scale).toPlainString()
    companion object {
        private val decimalPattern = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")

        fun parse(text: String, currency: CurrencySpec): Money {
            val normalized = text.trim()
            require(normalized.length in 1..64 && decimalPattern.matches(normalized)) { "Invalid decimal amount" }
            val decimal = BigDecimal(normalized)
            require(decimal.scale() <= currency.scale) { "Amount exceeds currency precision" }
            val minor = try {
                decimal.movePointRight(currency.scale).longValueExact()
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("Amount exceeds supported range", error)
            }
            return Money(minor, currency)
        }
    }
}
