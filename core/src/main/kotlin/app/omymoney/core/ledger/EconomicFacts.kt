package app.omymoney.core.ledger

import app.omymoney.core.model.*
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

enum class EconomicKind { INCOME, EXPENSE, TRANSFER }
data class EconomicFact(val bill: Bill, val kind: EconomicKind, val amount: Money,
                        val categoryId: String? = bill.categoryId, val actual: Money? = null, val fee: Boolean = false)
data class FactValuation(val minor: BigInteger?, val missingCurrency: CurrencySpec? = null, val rate: ManualExchangeRate? = null)

object EconomicFacts {
    fun from(bills: List<Bill>): List<EconomicFact> {
        val distinct = bills.distinctBy { it.id }
        val sources = distinct.filter { it.kind == BillKind.EXPENSE }.associateBy { it.id }
        val refunds = distinct.filter { it.kind == BillKind.REFUND }.groupBy { it.linkedBillId }
        val effects = refunds.flatMap { (id, items) ->
            val source = sources[id] ?: error("Refund source is required for economic classification")
            LinkedBillRules.refundEffects(source, items).entries.map { it.key to it.value }
        }.toMap()
        return buildList {
            distinct.forEach { bill ->
                val actual = bill.exchange?.amount
                when (bill.kind) {
                    BillKind.EXPENSE, BillKind.INTEREST_EXPENSE -> add(EconomicFact(bill, EconomicKind.EXPENSE, bill.amount, actual = actual))
                    BillKind.INCOME, BillKind.INTEREST_INCOME -> add(EconomicFact(bill, EconomicKind.INCOME, bill.amount, actual = actual))
                    BillKind.TRANSFER, BillKind.CREDIT_PAYMENT -> add(EconomicFact(bill, EconomicKind.TRANSFER, bill.amount, actual = actual))
                    BillKind.REFUND -> {
                        val effect = effects.getValue(bill.id)
                        val settledReduction = actual?.let { Money(BigDecimal.valueOf(it.minor)
                            .multiply(BigDecimal.valueOf(effect.expenseReduction.minor))
                            .divide(BigDecimal.valueOf(bill.amount.minor), 0, RoundingMode.HALF_EVEN).longValueExact(), it.currency) }
                        if (effect.expenseReduction.minor > 0) add(EconomicFact(bill, EconomicKind.EXPENSE,
                            -effect.expenseReduction, sources.getValue(requireNotNull(bill.linkedBillId)).categoryId, settledReduction?.let { -it }))
                        if (effect.income.minor > 0) add(EconomicFact(bill, EconomicKind.INCOME, effect.income,
                            categoryId = null, actual = actual?.let { it - requireNotNull(settledReduction) }))
                    }
                    else -> Unit
                }
                bill.fee?.takeIf { it.minor > 0 }?.let { fee ->
                    val category = when (bill.kind) {
                        BillKind.EXPENSE, BillKind.INTEREST_EXPENSE -> bill.categoryId
                        BillKind.REFUND -> sources[bill.linkedBillId]?.categoryId
                        else -> null
                    }
                    add(EconomicFact(bill, EconomicKind.EXPENSE, fee, categoryId = category, fee = true))
                }
            }
        }
    }

    fun value(fact: EconomicFact, currency: CurrencySpec, rates: List<ManualExchangeRate>): FactValuation {
        if (fact.amount.currency == currency) return FactValuation(BigInteger.valueOf(fact.amount.minor))
        fact.actual?.takeIf { it.currency == currency }?.let { return FactValuation(BigInteger.valueOf(it.minor)) }
        if (fact.amount.minor == 0L) return FactValuation(BigInteger.ZERO)
        val quote = rates.singleOrNull { it.source == fact.amount.currency && it.target == currency }
            ?: return FactValuation(null, fact.amount.currency)
        val minor = BigDecimal.valueOf(fact.amount.minor, fact.amount.currency.scale)
            .multiply(ExchangeRules.parseRate(quote.rate)).setScale(currency.scale, RoundingMode.HALF_EVEN).unscaledValue()
        return FactValuation(minor, rate = quote)
    }
}
