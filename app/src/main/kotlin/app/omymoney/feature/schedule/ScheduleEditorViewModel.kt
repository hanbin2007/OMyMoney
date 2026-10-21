package app.omymoney.feature.schedule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omymoney.core.ledger.DebtRules
import app.omymoney.core.ledger.LinkedBillRules
import app.omymoney.core.ledger.PostingCalculator
import app.omymoney.core.model.*
import app.omymoney.core.schedule.*
import app.omymoney.data.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class ScheduleForm(
    val base: ScheduledPlan,
    val currency: CurrencySpec,
    val name: String = base.name,
    val installments: Boolean = base.installments,
    val automatic: Boolean = base.automatic,
    val kind: BillKind = base.template.kind ?: BillKind.EXPENSE,
    val fromId: String? = base.template.fromAccountId,
    val toId: String? = base.template.toAccountId,
    val categoryId: String? = base.template.categoryId,
    val linkedId: String? = base.template.linkedBillId,
    val amount: String = base.template.amount?.decimalText().orEmpty(),
    val fee: String = base.template.fee?.decimalText().orEmpty(),
    val note: String = base.template.note,
    val tags: String = base.template.tags.joinToString(", "),
    val excludeStats: Boolean = base.template.excludeStats,
    val excludeBudget: Boolean = base.template.excludeBudget,
    val start: String = base.rule.startDate.toString(),
    val time: String = base.rule.localTime,
    val zone: String = base.rule.zone.id,
    val frequency: ScheduleFrequency = base.rule.frequency,
    val interval: String = base.rule.interval.toString(),
    val count: String = base.rule.count?.toString().orEmpty(),
    val end: String = base.rule.endDate?.toString().orEmpty(),
    val dirty: Boolean = false,
) {
    val calendarLocked get() = base.nextIndex > 0
    val amountLocked get() = calendarLocked && base.installments
    val linked get() = kind in LinkedBillRules.settlementKinds + DebtRules.linkedKinds
}

data class ScheduleIssue(val field: String, val code: String)
data class ScheduleEditorState(val form: ScheduleForm? = null, val loadFailed: Boolean = false, val saving: Boolean = false,
    val issue: ScheduleIssue? = null, val attempt: Int = 0, val preview: ScheduledPlan? = null, val saved: ScheduledPlan? = null)
private class InvalidSchedule(val issue: ScheduleIssue) : IllegalArgumentException()

class ScheduleEditorViewModel(private val store: LedgerStore, private val bookId: String, private val planId: String?,
    private val savedState: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(ScheduleEditorState())
    val state = mutable.asStateFlow()
    init { load() }

    fun load() {
        viewModelScope.launch {
            mutable.value = ScheduleEditorState()
            try {
                val restored = savedState.get<String>("schedule_form")?.let { LedgerJson.format.decodeFromString<ScheduleForm>(it) }
                val form = restored ?: run {
                    val book = store.catalog.observeBooks().first().first { it.id == bookId }
                    val plan = if (planId == null) ScheduledPlan(name = "", template = store.catalog.initialDraft(bookId),
                        rule = ScheduleRule(LocalDate.now(book.zoneId), zone = book.zoneId)) else requireNotNull(store.schedules.read(planId))
                    require(plan.template.bookId == bookId)
                    val accounts = store.catalog.observeAccounts(true).first()
                    val currency = plan.template.amount?.currency ?: accounts.firstOrNull {
                        it.id == (plan.template.fromAccountId ?: plan.template.toAccountId)
                    }?.currency ?: book.currency
                    ScheduleForm(plan, currency)
                }
                mutable.value = ScheduleEditorState(form = form)
                persist(form)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.value = ScheduleEditorState(loadFailed = true) }
        }
    }

    fun update(transform: (ScheduleForm) -> ScheduleForm) {
        val state = mutable.value
        if (state.saving || state.saved != null) return
        val previous = state.form ?: return
        val next = transform(previous)
        if (next == previous) return
        val form = next.copy(dirty = true)
        persist(form)
        mutable.value = state.copy(form = form, issue = null)
    }

    fun chooseKind(kind: BillKind) = update { form ->
        if (kind == form.kind) return@update form
        val outgoing = kind in PostingCalculator.outgoingKinds
        val incoming = kind in PostingCalculator.incomingKinds
        form.copy(kind = kind, fromId = if (incoming) null else form.fromId ?: form.toId.takeIf { outgoing },
            toId = if (outgoing) null else form.toId ?: form.fromId.takeIf { incoming }, linkedId = null,
            categoryId = form.categoryId.takeIf { PostingCalculator.categoryKind(kind) == PostingCalculator.categoryKind(form.kind) })
    }

    fun chooseAccount(from: Boolean, account: AssetAccount) = update { form ->
        val currencyChanges = form.currency != account.currency
        form.copy(fromId = if (from) account.id else form.fromId.takeUnless { it == account.id || currencyChanges },
            toId = if (!from) account.id else form.toId.takeUnless { it == account.id || currencyChanges }, currency = account.currency,
            amount = if (currencyChanges) "" else form.amount, fee = if (currencyChanges) "" else form.fee)
    }

    fun chooseSource(bill: Bill) = update { form ->
        if (bill.id == form.linkedId) return@update form
        val from = when (form.kind) {
            BillKind.DEBT_REPAY, BillKind.DEBT_COLLECT, BillKind.INTEREST_EXPENSE -> bill.toAccountId
            else -> null
        }
        val to = if (form.kind == BillKind.INTEREST_EXPENSE) null else bill.fromAccountId
        form.copy(linkedId = bill.id, fromId = from, toId = to, currency = bill.amount.currency,
            amount = form.amount.takeIf { form.currency == bill.amount.currency }.orEmpty(),
            fee = form.fee.takeIf { form.currency == bill.amount.currency }.orEmpty())
    }

    fun preview() { candidate()?.let { mutable.value = mutable.value.copy(preview = it) } }
    fun closePreview() { mutable.value = mutable.value.copy(preview = null) }

    fun save() {
        if (mutable.value.saving || mutable.value.saved != null) return
        val plan = candidate() ?: return
        mutable.value = mutable.value.copy(saving = true, issue = null)
        viewModelScope.launch {
            try {
                when (val result = store.schedules.save(plan)) {
                    is ScheduleSaveResult.Saved -> mutable.value = mutable.value.copy(saving = false, saved = result.plan)
                    is ScheduleSaveResult.Invalid -> fail("general", result.code)
                    ScheduleSaveResult.Conflict -> fail("general", "conflict")
                    ScheduleSaveResult.Failure -> fail("general", "storage")
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail("general", "storage") }
        }
    }

    private fun fail(field: String, code: String) {
        mutable.value = mutable.value.copy(saving = false, issue = ScheduleIssue(field, code), attempt = mutable.value.attempt + 1)
    }
    private fun persist(form: ScheduleForm) { savedState["schedule_form"] = LedgerJson.format.encodeToString(form) }
    private fun candidate(): ScheduledPlan? = try {
        mutable.value.form?.let(::buildPlan)
    } catch (error: InvalidSchedule) {
        mutable.value = mutable.value.copy(issue = error.issue, attempt = mutable.value.attempt + 1); null
    }

    private fun buildPlan(form: ScheduleForm): ScheduledPlan {
        fun invalid(field: String, code: String): Nothing = throw InvalidSchedule(ScheduleIssue(field, code))
        if (form.name.isBlank() || form.name.length > 100) invalid("name", "name")
        val amount = runCatching { Money.parse(form.amount, form.currency) }.getOrNull()?.takeIf { it.minor > 0 } ?: invalid("amount", "amount")
        val fee = if (form.fee.isBlank()) null else runCatching { Money.parse(form.fee, form.currency) }.getOrNull()
            ?.takeIf { it.minor >= 0 } ?: invalid("fee", "amount")
        val start = runCatching { LocalDate.parse(form.start) }.getOrNull()?.takeIf { it.year in 1..9998 } ?: invalid("start", "date")
        val time = runCatching { LocalTime.parse(form.time) }.getOrNull()?.takeIf { it.nano == 0 } ?: invalid("time", "time")
        val zone = runCatching { ZoneId.of(form.zone) }.getOrNull() ?: invalid("zone", "zone")
        val interval = form.interval.toIntOrNull()?.takeIf { it in 1..365 } ?: invalid("interval", "interval")
        val count = if (form.count.isBlank() && !form.installments) null else form.count.toIntOrNull()
            ?.takeIf { it in 1..if (form.installments) 1000 else ScheduleCalculator.MAX_OCCURRENCES } ?: invalid("count", "count")
        if (form.installments && amount.minor < requireNotNull(count)) invalid("amount", "small_installment")
        val end = if (form.end.isBlank()) null else runCatching { LocalDate.parse(form.end) }.getOrNull()
            ?.takeIf { it >= start && it.year <= 9998 } ?: invalid("end", "end")
        val rule = ScheduleRule(start, time.toString(), zone, form.frequency, interval, count, end)
        if (form.installments && ScheduleCalculator.occurrence(rule, requireNotNull(count) - 1) == null) invalid("end", "end_installments")
        if (form.kind in PostingCalculator.outgoingKinds + PostingCalculator.transferKinds && form.fromId == null) invalid("from", "account")
        if (form.kind in PostingCalculator.incomingKinds + PostingCalculator.transferKinds && form.toId == null) invalid("to", "account")
        if (form.kind in PostingCalculator.transferKinds && form.fromId == form.toId) invalid("to", "same_account")
        if (form.linked && form.linkedId == null) invalid("linked", "linked")
        return form.base.copy(name = form.name.trim(), installments = form.installments, automatic = form.automatic, rule = rule,
            template = form.base.template.copy(kind = form.kind, amount = amount, fee = fee?.takeIf { it.minor > 0 },
                fromAccountId = form.fromId, toAccountId = form.toId, categoryId = form.categoryId, linkedBillId = form.linkedId,
                note = form.note, tags = form.tags.split(',', '，').map(String::trim).filter(String::isNotEmpty).distinct(),
                excludeStats = form.excludeStats, excludeBudget = form.excludeBudget))
    }
}
