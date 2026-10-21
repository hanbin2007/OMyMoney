package app.omymoney.core.ledger

import app.omymoney.core.model.*
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

object ExchangeRules {
    fun parseRate(text: String): BigDecimal {
        require(text.length in 1..40 && text.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) { "Invalid rate" }
        return BigDecimal(text).also { require(it.signum() > 0 && it.scale() <= 18 && it.precision() <= 36) { "Invalid rate" } }
    }

    fun convert(amount: Money, rate: BigDecimal, target: CurrencySpec): Money {
        parseRate(rate.toPlainString())
        val converted = BigDecimal.valueOf(amount.minor, amount.currency.scale).multiply(rate)
            .setScale(target.scale, RoundingMode.HALF_EVEN).movePointRight(target.scale).longValueExact()
        return Money(converted, target)
    }

    fun difference(nominal: Money, snapshot: ExchangeSnapshot): Money =
        snapshot.amount - convert(nominal, parseRate(snapshot.rate), snapshot.amount.currency)

    fun value(balances: List<Money>, target: CurrencySpec, rates: List<ManualExchangeRate>): AssetValuation {
        val selected = rates.filter { it.target == target }
        require(selected.distinctBy { it.source }.size == selected.size) { "Duplicate rate" }
        val missing = mutableSetOf<CurrencySpec>()
        val used = mutableListOf<ManualExchangeRate>()
        var total = BigInteger.ZERO
        balances.groupBy { it.currency }.forEach { (currency, rows) ->
            val sum = rows.fold(BigInteger.ZERO) { value, money -> value + BigInteger.valueOf(money.minor) }
            if (sum == BigInteger.ZERO) return@forEach
            if (currency == target) total += sum
            else {
                val rate = selected.singleOrNull { it.source == currency }
                if (rate == null) missing += currency
                else {
                    used += rate
                    total += BigDecimal(sum, currency.scale).multiply(parseRate(rate.rate))
                        .setScale(target.scale, RoundingMode.HALF_EVEN).unscaledValue()
                }
            }
        }
        return AssetValuation(if (missing.isEmpty()) Money(BigDecimal(total).longValueExact(), target) else null, missing, used)
    }

    fun foreignAccount(draft: TransactionDraft): String? = when (draft.kind) {
        BillKind.TRANSFER, BillKind.CREDIT_PAYMENT, BillKind.DEBT_BORROW, BillKind.DEBT_COLLECT -> draft.toAccountId
        BillKind.DEBT_LEND, BillKind.DEBT_REPAY -> draft.fromAccountId
        in PostingCalculator.outgoingKinds -> draft.fromAccountId
        in PostingCalculator.incomingKinds -> draft.toAccountId
        else -> null
    }

    fun feeAccount(draft: TransactionDraft): String? = when (draft.kind) {
        BillKind.DEBT_BORROW, BillKind.DEBT_COLLECT -> draft.toAccountId
        in PostingCalculator.incomingKinds -> draft.toAccountId
        BillKind.ADJUSTMENT -> null
        else -> draft.fromAccountId
    }

    fun accountAmount(draft: TransactionDraft, accountId: String): Money =
        draft.exchange?.takeIf { it.accountId == accountId }?.amount ?: requireNotNull(draft.amount)

    fun validate(draft: TransactionDraft): List<FieldError> = buildList {
        draft.exchange?.let { exchange ->
            if (exchange.accountId != foreignAccount(draft) || exchange.amount.minor <= 0 ||
                exchange.amount.currency.code == draft.amount?.currency?.code ||
                runCatching { parseRate(exchange.rate) }.isFailure) {
                add(FieldError("exchange", "invalid_exchange", "A valid foreign amount, rate and settlement account are required"))
            }
        }
        draft.fee?.let { fee ->
            val account = feeAccount(draft)
            val currency = if (account == null && PostingCalculator.allowsUnassignedAccounts(draft)) draft.amount?.currency
                else account?.let { accountAmount(draft, it).currency }
            if (currency == null || fee.minor < 0 || fee.currency != currency) {
                add(FieldError("fee", "invalid_fee", "Fee must be nonnegative and use the cash account currency"))
            }
        }
    }
}
