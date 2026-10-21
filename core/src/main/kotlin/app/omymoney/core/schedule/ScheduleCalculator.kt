@file:kotlinx.serialization.UseSerializers(app.omymoney.core.model.LocalDateSerializer::class, app.omymoney.core.model.ZoneIdSerializer::class)

package app.omymoney.core.schedule

import app.omymoney.core.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.DateTimeException
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable enum class ScheduleFrequency { DAILY, WEEKLY, MONTHLY, YEARLY }
@Serializable data class ScheduleRule(
    val startDate: LocalDate,
    val localTime: String = "09:00",
    val zone: ZoneId,
    val frequency: ScheduleFrequency = ScheduleFrequency.MONTHLY,
    val interval: Int = 1,
    val count: Int? = null,
    val endDate: LocalDate? = null,
)
data class Occurrence(val index: Int, val date: LocalDate, val plannedAt: Instant)

object ScheduleCalculator {
    const val MAX_OCCURRENCES = 4_000_000

    fun validate(rule: ScheduleRule) {
        require(rule.startDate.year in 1..9998 && rule.interval in 1..365)
        require(rule.count == null || rule.count in 1..MAX_OCCURRENCES)
        require(rule.endDate == null || (rule.endDate >= rule.startDate && rule.endDate.year <= 9998))
        require(runCatching { LocalTime.parse(rule.localTime).nano == 0 }.getOrDefault(false)) { "Invalid local time" }
    }

    fun occurrence(rule: ScheduleRule, index: Int): Occurrence? {
        validate(rule)
        require(index in 0..MAX_OCCURRENCES)
        if (index == MAX_OCCURRENCES || (rule.count != null && index >= rule.count)) return null
        val distance = index.toLong() * rule.interval
        // Always advance from the original anchor, never from February's clipped date.
        val date = try {
            when (rule.frequency) {
                ScheduleFrequency.DAILY -> rule.startDate.plusDays(distance)
                ScheduleFrequency.WEEKLY -> rule.startDate.plusWeeks(distance)
                ScheduleFrequency.MONTHLY -> rule.startDate.plusMonths(distance)
                ScheduleFrequency.YEARLY -> rule.startDate.plusYears(distance)
            }
        } catch (_: DateTimeException) { return null }
        if (date.year > 9998 || (rule.endDate != null && date > rule.endDate)) return null
        // java.time shifts gaps forward and selects the earlier offset in overlaps.
        val instant = date.atTime(LocalTime.parse(rule.localTime)).atZone(rule.zone).toInstant()
        return Occurrence(index, date, instant)
    }

    fun dueOccurrences(rule: ScheduleRule, through: Instant, fromIndex: Int = 0, limit: Int = 512): List<Occurrence> {
        validate(rule)
        require(fromIndex in 0..MAX_OCCURRENCES && limit in 1..512)
        return buildList {
            var index = fromIndex
            while (size < limit) {
                val next = occurrence(rule, index) ?: break
                if (next.plannedAt > through) break
                add(next)
                index++
            }
        }
    }

    fun installments(total: Money, count: Int): List<Money> {
        require(count in 1..1000 && total.minor >= count) { "Each installment must contain at least one minor unit" }
        val ordinary = total.minor / count
        return List(count) { index -> Money(if (index == count - 1) total.minor - ordinary * (count - 1) else ordinary, total.currency) }
    }
}
