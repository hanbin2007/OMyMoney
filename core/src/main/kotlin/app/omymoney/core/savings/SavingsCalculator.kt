@file:kotlinx.serialization.UseSerializers(app.omymoney.core.model.LocalDateSerializer::class, app.omymoney.core.model.ZoneIdSerializer::class)

package app.omymoney.core.savings

import app.omymoney.core.model.*
import app.omymoney.core.ledger.ExchangeRules
import app.omymoney.core.schedule.ScheduleCalculator
import app.omymoney.core.schedule.ScheduleFrequency
import app.omymoney.core.schedule.ScheduleRule
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable enum class SavingsMode { FIXED, INCREASING, DECREASING, CUSTOM }
@Serializable data class SavingsRule(val mode: SavingsMode, val amount: Money, val start: LocalDate,
    val count: Int = 12, val incrementMinor: Long = 0, val frequency: ScheduleFrequency = ScheduleFrequency.MONTHLY,
    val interval: Int = 1, val end: LocalDate? = null)
@Serializable data class SavingsPlan(val id: String = UUID.randomUUID().toString(), val bookId: String,
    val name: String, val rule: SavingsRule, val zone: ZoneId, val note: String = "", val paused: Boolean = false,
    val sortOrder: Int = 0, val revision: Long = 0)
@Serializable data class SavingsDeposit(val id: String = UUID.randomUUID().toString(), val planId: String,
    val date: LocalDate, val amount: Money? = null, val linkedBillId: String? = null, val note: String = "", val revision: Long = 0)
data class SavingsStep(val index: Int, val date: LocalDate, val amount: Money)
enum class SavingsDepositProblem { MISSING_TRANSFER, CHANGED_TRANSFER, DIFFERENT_CURRENCY, DIFFERENT_BOOK }
data class ResolvedSavingsDeposit(val deposit: SavingsDeposit, val amount: Money?, val date: LocalDate,
    val problem: SavingsDepositProblem? = null)
data class SavingsProgress(val target: Money, val savedMinor: BigInteger, val remainingMinor: BigInteger,
    val excessMinor: BigInteger, val fraction: Float, val completed: Boolean, val records: List<ResolvedSavingsDeposit>)

object SavingsCalculator {
    fun validate(rule: SavingsRule) {
        require(rule.amount.minor > 0 && rule.incrementMinor >= 0 && rule.count in 1..1000)
        require(rule.start.year in 1..9998 && rule.interval in 1..365)
        require(rule.end == null || (rule.end >= rule.start && rule.end.year <= 9998))
        if (rule.mode != SavingsMode.CUSTOM) require(ScheduleCalculator.occurrence(calendar(rule), rule.count - 1) != null)
    }

    private fun calendar(rule: SavingsRule) = ScheduleRule(rule.start, zone = ZoneOffset.UTC, frequency = rule.frequency,
        interval = rule.interval, count = rule.count)

    fun steps(rule: SavingsRule): List<SavingsStep> {
        validate(rule)
        if (rule.mode == SavingsMode.CUSTOM) return emptyList()
        val initial = BigInteger.valueOf(rule.amount.minor)
        return List(rule.count) { index ->
            val change = BigInteger.valueOf(rule.incrementMinor).multiply(BigInteger.valueOf(index.toLong()))
            val minor = when (rule.mode) {
                SavingsMode.INCREASING -> initial + change
                SavingsMode.DECREASING -> (initial - change).max(BigInteger.ZERO)
                else -> initial
            }
            SavingsStep(index, requireNotNull(ScheduleCalculator.occurrence(calendar(rule), index)).date,
                Money(BigDecimal(minor).longValueExact(), rule.amount.currency))
        }
    }

    fun target(rule: SavingsRule): Money {
        validate(rule)
        if (rule.mode == SavingsMode.CUSTOM) return rule.amount
        return Money(steps(rule).fold(0L) { total, step -> Math.addExact(total, step.amount.minor) }, rule.amount.currency)
    }

    fun resolve(plan: SavingsPlan, deposit: SavingsDeposit, bills: Map<String, Bill>): ResolvedSavingsDeposit {
        require(deposit.planId == plan.id)
        if (deposit.linkedBillId == null) {
            val amount = requireNotNull(deposit.amount)
            require(amount.currency == plan.rule.amount.currency && amount.minor > 0)
            return ResolvedSavingsDeposit(deposit, amount, deposit.date)
        }
        require(deposit.amount == null)
        val bill = bills[deposit.linkedBillId]
        val problem = when {
            bill == null -> SavingsDepositProblem.MISSING_TRANSFER
            bill.bookId != plan.bookId -> SavingsDepositProblem.DIFFERENT_BOOK
            bill.kind != BillKind.TRANSFER || bill.toAccountId == null -> SavingsDepositProblem.CHANGED_TRANSFER
            ExchangeRules.accountAmount(bill.toDraft(), bill.toAccountId).currency != plan.rule.amount.currency -> SavingsDepositProblem.DIFFERENT_CURRENCY
            else -> null
        }
        if (problem != null) return ResolvedSavingsDeposit(deposit, null, deposit.date, problem)
        return ResolvedSavingsDeposit(deposit, ExchangeRules.accountAmount(requireNotNull(bill).toDraft(), requireNotNull(bill.toAccountId)),
            bill.occurredAt.atZone(plan.zone).toLocalDate())
    }

    fun progress(plan: SavingsPlan, deposits: List<SavingsDeposit>, bills: Map<String, Bill> = emptyMap()): SavingsProgress {
        require(deposits.map { it.id }.distinct().size == deposits.size)
        val linked = deposits.mapNotNull { it.linkedBillId }
        require(linked.distinct().size == linked.size)
        val target = target(plan.rule)
        val records = deposits.map { resolve(plan, it, bills) }
        val saved = records.fold(BigInteger.ZERO) { total, record -> total + BigInteger.valueOf(record.amount?.minor ?: 0) }
        val targetMinor = BigInteger.valueOf(target.minor)
        val fraction = if (saved >= targetMinor) 1f else BigDecimal(saved).divide(BigDecimal(targetMinor), 8, RoundingMode.DOWN)
            .toFloat().coerceAtMost(Math.nextDown(1f))
        return SavingsProgress(target, saved, (targetMinor - saved).max(BigInteger.ZERO), (saved - targetMinor).max(BigInteger.ZERO),
            fraction, saved >= targetMinor, records)
    }
}
