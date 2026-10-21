package app.omymoney.feature.savings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omymoney.core.model.*
import app.omymoney.core.savings.*
import app.omymoney.core.schedule.ScheduleFrequency
import app.omymoney.data.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

data class SavingsIssue(val field: String, val code: String)
data class SavingsEditorState<T>(val form: T? = null, val saving: Boolean = false, val loadFailed: Boolean = false,
    val issue: SavingsIssue? = null, val attempt: Int = 0, val savedId: String? = null)
private class InvalidSavings(val issue: SavingsIssue) : IllegalArgumentException()
private fun invalid(field: String, code: String = field): Nothing = throw InvalidSavings(SavingsIssue(field, code))
private fun positiveMoney(text: String, currency: CurrencySpec, field: String): Money =
    runCatching { Money.parse(text, currency) }.getOrNull()?.takeIf { it.minor > 0 } ?: invalid(field, "amount")
private fun date(text: String, field: String): LocalDate = runCatching { LocalDate.parse(text) }.getOrNull()
    ?.takeIf { it.year in 1..9998 } ?: invalid(field, "date")

@Serializable data class SavingsPlanForm(val base: SavingsPlan, val currencyLocked: Boolean = false,
    val name: String = base.name, val currency: CurrencySpec = base.rule.amount.currency, val mode: SavingsMode = base.rule.mode,
    val amount: String = if (base.revision == 0L) "" else base.rule.amount.decimalText(),
    val increment: String = Money(base.rule.incrementMinor, base.rule.amount.currency).decimalText(),
    val count: String = base.rule.count.toString(), val frequency: ScheduleFrequency = base.rule.frequency,
    val interval: String = base.rule.interval.toString(), val start: String = base.rule.start.toString(),
    val end: String = base.rule.end?.toString().orEmpty(), val note: String = base.note, val dirty: Boolean = false)

class SavingsPlanEditorViewModel(private val store: LedgerStore, private val bookId: String, private val planId: String?,
    private val savedState: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(SavingsEditorState<SavingsPlanForm>())
    val state = mutable.asStateFlow()
    val preview = MutableStateFlow<SavingsPlan?>(null)
    init { load() }
    fun load() { viewModelScope.launch {
        mutable.value = SavingsEditorState()
        try {
            val form = savedState.get<String>("savings_form")?.let { LedgerJson.format.decodeFromString<SavingsPlanForm>(it) } ?: run {
                val book = store.catalog.observeBooks().first().first { it.id == bookId }
                val plan = if (planId == null) SavingsPlan(bookId = bookId, name = "", zone = book.zoneId,
                    rule = SavingsRule(SavingsMode.FIXED, Money(1, book.currency), LocalDate.now(book.zoneId)))
                    else requireNotNull(store.savings.readPlan(planId))
                require(plan.bookId == bookId)
                SavingsPlanForm(plan, currencyLocked = store.savings.observeDeposits(plan.id).first().isNotEmpty())
            }
            mutable.value = SavingsEditorState(form = form); persist(form)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { mutable.value = SavingsEditorState(loadFailed = true) }
    } }
    fun update(change: (SavingsPlanForm) -> SavingsPlanForm) {
        val state = mutable.value; val previous = state.form ?: return
        if (state.saving || state.savedId != null) return
        val changed = change(previous); if (changed == previous) return
        val form = changed.copy(dirty = true)
        persist(form); mutable.value = state.copy(form = form, issue = null)
    }
    fun chooseCurrency(currency: CurrencySpec) = update {
        if (it.currency == currency || it.currencyLocked) it else it.copy(currency = currency, amount = "", increment = "0")
    }
    fun showPreview() { preview.value = candidate() }
    fun closePreview() { preview.value = null }
    fun save() {
        if (mutable.value.saving || mutable.value.savedId != null) return
        val plan = candidate() ?: return
        mutable.value = mutable.value.copy(saving = true, issue = null)
        viewModelScope.launch {
            try { when (val result = store.savings.savePlan(plan)) {
                is SavingsResult.Saved -> mutable.value = mutable.value.copy(saving = false, savedId = result.value.id)
                is SavingsResult.Invalid -> fail("general", result.code)
                SavingsResult.Conflict -> fail("general", "conflict")
                SavingsResult.Failure -> fail("general", "storage")
            } } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail("general", "storage") }
        }
    }
    private fun persist(form: SavingsPlanForm) { savedState["savings_form"] = LedgerJson.format.encodeToString(form) }
    private fun fail(field: String, code: String) { mutable.value = mutable.value.copy(saving = false, issue = SavingsIssue(field, code), attempt = mutable.value.attempt + 1) }
    private fun candidate(): SavingsPlan? = try {
        mutable.value.form?.let { form ->
            if (form.name.isBlank() || form.name.length > 100) invalid("name")
            val amount = positiveMoney(form.amount, form.currency, "amount")
            val start = date(form.start, "start")
            val custom = form.mode == SavingsMode.CUSTOM
            val increment = if (form.mode in listOf(SavingsMode.INCREASING, SavingsMode.DECREASING))
                runCatching { Money.parse(form.increment, form.currency) }.getOrNull()?.takeIf { it.minor >= 0 }?.minor ?: invalid("increment", "increment") else 0
            val count = if (custom) 1 else form.count.toIntOrNull()?.takeIf { it in 1..1000 } ?: invalid("count")
            val interval = if (custom) 1 else form.interval.toIntOrNull()?.takeIf { it in 1..365 } ?: invalid("interval")
            val end = if (!custom || form.end.isBlank()) null else date(form.end, "end").takeIf { it >= start } ?: invalid("end", "end")
            if (form.note.length > 5000) invalid("note")
            val rule = SavingsRule(form.mode, amount, start, count, increment, form.frequency, interval, end)
            try { SavingsCalculator.target(rule) } catch (_: ArithmeticException) { invalid("amount", "overflow") }
                catch (_: IllegalArgumentException) { invalid("start", "range") }
            form.base.copy(name = form.name.trim(), rule = rule, note = form.note)
        }
    } catch (error: InvalidSavings) { fail(error.issue.field, error.issue.code); null }
}

@Serializable data class SavingsDepositForm(val plan: SavingsPlan, val base: SavingsDeposit,
    val linked: Boolean = base.linkedBillId != null, val linkedId: String? = base.linkedBillId,
    val amount: String = base.amount?.decimalText().orEmpty(), val date: String = base.date.toString(),
    val linkedDate: String = base.date.toString(),
    val note: String = base.note, val dirty: Boolean = false)

class SavingsDepositEditorViewModel(private val store: LedgerStore, private val bookId: String, private val planId: String,
    private val depositId: String?, private val savedState: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(SavingsEditorState<SavingsDepositForm>())
    val state = mutable.asStateFlow()
    init { load() }
    fun load() { viewModelScope.launch {
        mutable.value = SavingsEditorState()
        try {
            val form = savedState.get<String>("deposit_form")?.let { LedgerJson.format.decodeFromString<SavingsDepositForm>(it) } ?: run {
                val plan = requireNotNull(store.savings.readPlan(planId)); require(plan.bookId == bookId)
                val deposit = if (depositId == null) SavingsDeposit(planId = plan.id, date = LocalDate.now(plan.zone))
                    else requireNotNull(store.savings.readDeposit(depositId))
                require(deposit.planId == planId)
                SavingsDepositForm(plan, deposit)
            }
            mutable.value = SavingsEditorState(form = form); persist(form)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { mutable.value = SavingsEditorState(loadFailed = true) }
    } }
    fun update(change: (SavingsDepositForm) -> SavingsDepositForm) {
        val state = mutable.value; val previous = state.form ?: return
        if (state.saving || state.savedId != null) return
        val changed = change(previous); if (changed == previous) return
        val form = changed.copy(dirty = true)
        persist(form); mutable.value = state.copy(form = form, issue = null)
    }
    fun chooseTransfer(bill: Bill) = update { it.copy(linkedId = bill.id, linkedDate = bill.occurredAt.atZone(it.plan.zone).toLocalDate().toString()) }
    fun save() {
        if (mutable.value.saving || mutable.value.savedId != null) return
        val deposit = try { mutable.value.form?.let { form ->
            val amount = if (form.linked) null else positiveMoney(form.amount, form.plan.rule.amount.currency, "amount")
            if (form.linked && form.linkedId == null) invalid("transfer")
            if (form.note.length > 5000) invalid("note")
            val recordedDate = if (form.linked) date(form.linkedDate, "transfer") else date(form.date, "date")
            form.base.copy(amount = amount, linkedBillId = form.linkedId.takeIf { form.linked }, date = recordedDate, note = form.note)
        } } catch (error: InvalidSavings) { fail(error.issue.field, error.issue.code); null } ?: return
        mutable.value = mutable.value.copy(saving = true, issue = null)
        viewModelScope.launch {
            try { when (val result = store.savings.saveDeposit(deposit)) {
                is SavingsResult.Saved -> mutable.value = mutable.value.copy(saving = false, savedId = result.value.id)
                is SavingsResult.Invalid -> fail(if (result.code in listOf("transfer", "duplicate_transfer")) "transfer" else "general", result.code)
                SavingsResult.Conflict -> fail("general", "conflict")
                SavingsResult.Failure -> fail("general", "storage")
            } } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail("general", "storage") }
        }
    }
    private fun fail(field: String, code: String) { mutable.value = mutable.value.copy(saving = false, issue = SavingsIssue(field, code), attempt = mutable.value.attempt + 1) }
    private fun persist(form: SavingsDepositForm) { savedState["deposit_form"] = LedgerJson.format.encodeToString(form) }
}
