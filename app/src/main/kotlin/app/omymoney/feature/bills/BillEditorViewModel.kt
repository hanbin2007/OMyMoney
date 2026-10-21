package app.omymoney.feature.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import app.omymoney.core.ledger.PostingCalculator
import app.omymoney.core.ledger.DebtRules
import app.omymoney.core.ledger.ExchangeRules
import app.omymoney.core.model.*
import app.omymoney.data.CatalogRepository
import app.omymoney.data.DraftRepository
import app.omymoney.data.EditorDraft
import app.omymoney.data.AttachmentStore
import app.omymoney.data.AttachmentException
import app.omymoney.data.QueueEntry
import app.omymoney.data.QueueOutcome
import app.omymoney.data.QueueState
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

sealed interface EditorAction {
    data class Amount(val text: String) : EditorAction
    data class Fee(val text: String) : EditorAction
    data class ExchangeAmount(val text: String) : EditorAction
    data class ExchangeRate(val text: String) : EditorAction
    data class ExchangeDate(val text: String) : EditorAction
    data class Currency(val value: CurrencySpec) : EditorAction
    data class Kind(val value: BillKind) : EditorAction
    data class Category(val id: String?) : EditorAction
    data class FromAccount(val id: String?, val currency: CurrencySpec) : EditorAction
    data class ToAccount(val id: String?, val currency: CurrencySpec) : EditorAction
    data class Time(val instant: Instant) : EditorAction
    data class Note(val text: String) : EditorAction
    data class Counterparty(val text: String) : EditorAction
    data class Tags(val text: String) : EditorAction
    data class Attachments(val items: List<AttachmentRef>) : EditorAction
    data class ExcludeStats(val value: Boolean) : EditorAction
    data class ExcludeBudget(val value: Boolean) : EditorAction
}

data class EditorState(
    val record: EditorDraft? = null,
    val errors: List<FieldError> = emptyList(),
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val persistencePending: Boolean = false,
    val persistenceFailed: Boolean = false,
    val validationAttempt: Int = 0,
    val saved: SaveResult.Saved? = null,
    val closed: Boolean = false,
    val originalAccountIds: Set<String> = emptySet(),
    val originalCategoryId: String? = null,
    val importingAttachment: Boolean = false,
    val attachmentError: String? = null,
    val recognitionEntry: QueueEntry? = null,
    val accountCurrencies: Map<String, CurrencySpec> = emptyMap(),
) {
    val draft get() = record?.transaction
    val dirty get() = record?.dirty == true
    val revision get() = record?.expectedRevision
    val foreignCurrency get() = draft?.let(ExchangeRules::foreignAccount)?.let(accountCurrencies::get)?.takeIf { it != record?.currency }
    val feeCurrency get() = draft?.let(ExchangeRules::feeAccount)?.let(accountCurrencies::get) ?: record?.currency
}

class BillEditorViewModel(
    private val drafts: DraftRepository,
    private val catalog: CatalogRepository,
    private val attachmentStore: AttachmentStore,
    private val applicationScope: CoroutineScope,
    private val bookId: String,
    private val billId: String? = null,
    private val recognitionQueue: app.omymoney.data.RecognitionQueue? = null,
    val recognitionId: String? = null,
    private val linkedSourceId: String? = null,
    private val linkedKind: BillKind? = null,
    private val assetId: String? = null,
    private val assetKind: BillKind? = null,
    val scheduleExecutionId: String? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EditorState())
    val state = mutableState.asStateFlow()
    private val key = scheduleExecutionId?.let { "schedule/$it" } ?: recognitionId?.let { "recognition/$it" } ?: billId?.let { "edit/$it" }
        ?: linkedSourceId?.let { "linked/${linkedKind?.name}/$it" } ?: assetId?.let { "asset/${assetKind?.name}/$it/$bookId" } ?: "manual/$bookId"
    private var persistence: Job? = null
    private var writeVersion = 0L
    private var attachmentJob: Job? = null
    private var pendingAttachment: Uri? = null
    private var submission: Job? = null

    fun importAttachment(uri: Uri) {
        val current = mutableState.value
        if (current.record == null) { pendingAttachment = uri; return }
        if (current.saving || current.importingAttachment || current.saved != null || current.closed) return
        if (current.draft!!.attachments.size >= 10) {
            mutableState.value = current.copy(attachmentError = "count_limit")
            return
        }
        mutableState.value = current.copy(importingAttachment = true, attachmentError = null)
        attachmentJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            try {
                val ref = attachmentStore.`import`(uri)
                val existing = mutableState.value.draft?.attachments.orEmpty()
                onAction(EditorAction.Attachments(existing + ref))
            } catch (error: CancellationException) { throw error
            } catch (error: AttachmentException) { mutableState.value = mutableState.value.copy(attachmentError = error.code)
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(attachmentError = "unreadable")
            } finally { mutableState.value = mutableState.value.copy(importingAttachment = false) }
        }
    }
    fun cancelAttachmentImport() { attachmentJob?.cancel() }

    init {
        require((recognitionId == null) == (recognitionQueue == null))
        require(recognitionId == null || billId == null)
        require((linkedSourceId == null) == (linkedKind == null))
        require(linkedKind == null || linkedKind in app.omymoney.core.ledger.LinkedBillRules.settlementKinds + DebtRules.linkedKinds)
        require((assetId == null) == (assetKind == null))
        require(assetKind == null || assetKind in DebtRules.sourceKinds + BillKind.CREDIT_PAYMENT)
        require(scheduleExecutionId == null || (recognitionId == null && billId == null && linkedSourceId == null && assetId == null))
        load()
    }

    fun load() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            mutableState.value = EditorState()
            try {
                val entry = recognitionId?.let { requireNotNull(recognitionQueue!!.read(it)) }
                if (entry?.state == QueueState.SAVED) {
                    mutableState.value = EditorState(saved = SaveResult.Saved(requireNotNull(entry.billId), requireNotNull(entry.savedRevision)))
                    return@launch
                }
                if (entry != null) recognitionQueue!!.resolve(entry.id, QueueOutcome.Edit)
                val original = billId?.let { requireNotNull(drafts.billForEditing(it)) { "Bill is no longer available" } }
                val linkedSource = linkedSourceId?.let { requireNotNull(drafts.billForEditing(it)) }.also { source ->
                    if (source != null) require(source.transaction.bookId == bookId)
                }
                val pending = drafts.read(key)
                if (scheduleExecutionId != null) require(pending != null && pending.transaction.bookId == bookId) { "Scheduled draft is no longer available" }
                val record = pending ?: original ?: linkedSource?.let {
                    val source = it.transaction
                    val from = when (linkedKind) {
                        BillKind.DEBT_REPAY, BillKind.INTEREST_EXPENSE, BillKind.DEBT_COLLECT -> source.toAccountId
                        else -> null
                    }
                    val to = when (linkedKind) {
                        BillKind.INTEREST_EXPENSE -> null
                        else -> source.fromAccountId
                    }
                    EditorDraft(key, TransactionDraft(bookId, kind = linkedKind, linkedBillId = linkedSourceId,
                        fromAccountId = from, toAccountId = to, occurredAt = Instant.now(), timeSource = TimeSource.USER,
                        zoneId = source.zoneId), it.currency)
                } ?: assetId?.let { id ->
                    val accounts = catalog.observeAccounts().first()
                    val account = accounts.first { it.id == id }
                    val cash = accounts.firstOrNull { it.id != id && it.currency == account.currency && it.kind !in DebtRules.debtAccountKinds }
                    val book = catalog.observeBooks().first().first { it.id == bookId }
                    EditorDraft(key, TransactionDraft(bookId, kind = assetKind,
                        fromAccountId = if (assetKind == BillKind.DEBT_BORROW) id else cash?.id,
                        toAccountId = if (assetKind == BillKind.DEBT_BORROW) cash?.id else id,
                        occurredAt = Instant.now(), timeSource = TimeSource.USER, zoneId = book.zoneId), account.currency)
                } ?: entry?.let {
                    val currency = it.draft.amount?.currency ?: catalog.observeBooks(true).first().first { book -> book.id == it.draft.bookId }.currency
                    EditorDraft(key, it.draft, currency, it.draft.amount?.decimalText().orEmpty(), it.draft.tags.joinToString(", "))
                } ?: run {
                    val transaction = catalog.initialDraft(bookId)
                    val account = catalog.observeAccounts().first().firstOrNull { it.id == transaction.fromAccountId }
                    val book = catalog.observeBooks().first().first { it.id == bookId }
                    EditorDraft(key, transaction, account?.currency ?: book.currency)
                }
                mutableState.value = EditorState(record = record,
                    originalAccountIds = setOfNotNull(original?.transaction?.fromAccountId, original?.transaction?.toAccountId),
                    originalCategoryId = original?.transaction?.categoryId, recognitionEntry = entry,
                    accountCurrencies = catalog.observeAccounts(true).first().associate { it.id to it.currency })
                pendingAttachment?.let { uri -> pendingAttachment = null; importAttachment(uri) }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.value = EditorState(loadFailed = true) }
        }
    }

    fun onAction(action: EditorAction) {
        var current = mutableState.value
        val record = current.record ?: return
        if (current.saving || current.saved != null || current.closed) return
        val draft = record.transaction
        if ((action is EditorAction.FromAccount && draft.kind == BillKind.DEBT_COLLECT) ||
            (action is EditorAction.ToAccount && draft.kind == BillKind.DEBT_REPAY)) return
        val accountPair = when (action) {
            is EditorAction.FromAccount -> action.id?.let { it to action.currency }
            is EditorAction.ToAccount -> action.id?.let { it to action.currency }
            else -> null
        }
        if (accountPair != null) current = current.copy(accountCurrencies = current.accountCurrencies + accountPair)
        fun EditorDraft.resetExchange() = copy(exchangeAmountText = "", exchangeRateText = "",
            exchangeDateText = LocalDate.now(transaction.zoneId).toString(), transaction = transaction.copy(exchange = null),
            exchangeAmountCurrency = null, exchangeRateSource = null, exchangeRateTarget = null)
        fun changedSettlement(id: String?, currency: CurrencySpec): Boolean = id == ExchangeRules.foreignAccount(draft) &&
            listOfNotNull(record.exchangeAmountCurrency, record.exchangeRateTarget).any { it != currency }
        val changed = when (action) {
            is EditorAction.Fee -> record.copy(feeText = action.text, feeCurrency = current.feeCurrency, transaction = draft.copy(fee = null))
            is EditorAction.ExchangeAmount -> record.copy(exchangeAmountText = action.text, exchangeAmountCurrency = current.foreignCurrency)
            is EditorAction.ExchangeRate -> record.copy(exchangeRateText = action.text, exchangeRateSource = record.currency, exchangeRateTarget = current.foreignCurrency)
            is EditorAction.ExchangeDate -> record.copy(exchangeDateText = action.text)
            is EditorAction.Currency -> {
                if (action.value == record.currency) return
                if (draft.linkedBillId != null || draft.kind !in PostingCalculator.outgoingKinds + PostingCalculator.incomingKinds) return
                record.copy(currency = action.value, transaction = draft.copy(amount = null)).resetExchange()
            }
            is EditorAction.Amount -> record.copy(amountText = action.text, transaction = draft.copy(amount = null))
            is EditorAction.Kind -> {
                if (draft.kind == action.value || action.value !in (BASIC_KINDS + BillKind.REIMBURSEMENT) ||
                    (draft.kind != null && draft.kind !in BASIC_KINDS + BillKind.REIMBURSEMENT)) return
                val primary = draft.fromAccountId ?: draft.toAccountId
                record.copy(currency = if (action.value == BillKind.TRANSFER) current.accountCurrencies[primary] ?: record.currency else record.currency,
                    transaction = draft.copy(kind = action.value,
                    categoryId = draft.categoryId.takeIf { PostingCalculator.categoryKind(draft.kind) == PostingCalculator.categoryKind(action.value) },
                    fromAccountId = if (action.value == BillKind.INCOME) null else primary,
                    toAccountId = if (action.value == BillKind.INCOME) primary else null)).let {
                        if (it.currency != record.currency || ExchangeRules.foreignAccount(it.transaction) != ExchangeRules.foreignAccount(draft)) it.resetExchange() else it
                    }
            }
            is EditorAction.Category -> record.copy(transaction = draft.copy(categoryId = action.id))
            is EditorAction.FromAccount -> record.copy(currency = if (draft.kind in setOf(BillKind.TRANSFER, BillKind.CREDIT_PAYMENT, BillKind.DEBT_BORROW, BillKind.ADJUSTMENT) ||
                (record.amountText.isBlank() && draft.source == BillSource.MANUAL && draft.linkedBillId == null && draft.kind in PostingCalculator.outgoingKinds)) action.currency else record.currency,
                transaction = draft.copy(fromAccountId = action.id, amount = null)).let { if (action.id != draft.fromAccountId || changedSettlement(action.id, action.currency)) it.resetExchange() else it }
            is EditorAction.ToAccount -> record.copy(currency = if (draft.kind in setOf(BillKind.DEBT_LEND, BillKind.ADJUSTMENT) ||
                (record.amountText.isBlank() && draft.source == BillSource.MANUAL && draft.linkedBillId == null && draft.kind in PostingCalculator.incomingKinds)) action.currency else record.currency,
                transaction = draft.copy(toAccountId = action.id, amount = null)).let { if (action.id != draft.toAccountId || changedSettlement(action.id, action.currency)) it.resetExchange() else it }
            is EditorAction.Time -> record.copy(transaction = draft.copy(occurredAt = action.instant, timeSource = TimeSource.USER))
            is EditorAction.Note -> record.copy(transaction = draft.copy(note = action.text))
            is EditorAction.Counterparty -> record.copy(transaction = draft.copy(payment = (draft.payment ?: PaymentDetails()).copy(counterparty = action.text.take(256))))
            is EditorAction.Tags -> record.copy(tagsText = action.text)
            is EditorAction.Attachments -> record.copy(transaction = draft.copy(attachments = action.items))
            is EditorAction.ExcludeStats -> record.copy(transaction = draft.copy(excludeStats = action.value))
            is EditorAction.ExcludeBudget -> record.copy(transaction = draft.copy(excludeBudget = action.value))
        }.let {
            if (it.currency != record.currency) it.copy(amountText = "", transaction = it.transaction.copy(amount = null)) else it
        }.copy(dirty = true)
        val changedFields = when (action) {
            is EditorAction.Fee -> setOf("fee")
            is EditorAction.ExchangeAmount -> setOf("exchangeAmount", "exchange")
            is EditorAction.ExchangeRate -> setOf("exchangeRate", "exchange")
            is EditorAction.ExchangeDate -> setOf("exchangeDate", "exchange")
            is EditorAction.Currency -> setOf("amount", "exchange", "exchangeRate", "exchangeAmount", "exchangeDate", "fromAccountId", "toAccountId")
            is EditorAction.Amount -> setOf("amount")
            is EditorAction.Kind -> setOf("kind", "categoryId", "fromAccountId", "toAccountId", "amount")
            is EditorAction.Category -> setOf("categoryId")
            is EditorAction.FromAccount -> setOf("fromAccountId", "toAccountId", "amount")
            is EditorAction.ToAccount -> setOf("fromAccountId", "toAccountId", "amount")
            is EditorAction.Time -> setOf("occurredAt")
            is EditorAction.Note -> setOf("note")
            is EditorAction.Counterparty -> setOf("payment")
            is EditorAction.Tags -> setOf("tags")
            is EditorAction.Attachments -> setOf("attachments")
            is EditorAction.ExcludeStats -> setOf("excludeStats")
            is EditorAction.ExcludeBudget -> setOf("excludeBudget")
        }
        mutableState.value = current.copy(record = changed, errors = current.errors.filterNot { it.field in changedFields })
        persist(changed)
    }

    // Keep writes ordered and alive across navigation; explicit save/back waits for the last write.
    private fun persist(record: EditorDraft) {
        val previous = persistence
        val version = ++writeVersion
        mutableState.value = mutableState.value.copy(persistencePending = true)
        persistence = applicationScope.launch(Dispatchers.Main.immediate) {
            previous?.join()
            var failed = false
            try {
                if (recognitionId == null) drafts.write(record) else recognitionQueue!!.persistDraft(recognitionId, record)
            }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed = true }
            if (writeVersion == version) mutableState.value = mutableState.value.copy(
                persistencePending = false, persistenceFailed = failed)
        }
    }

    fun retryPersistence() { mutableState.value.record?.let(::persist) }

    fun applyCurrentRate() {
        val current = mutableState.value
        val record = current.record ?: return
        val foreign = current.foreignCurrency ?: return
        if (current.saving || current.saved != null || current.closed) return
        viewModelScope.launch(Dispatchers.Main.immediate) {
            try {
                val rate = catalog.observeExchangeRates().first().singleOrNull { it.source == record.currency && it.target == foreign }
                if (mutableState.value.record != record) return@launch
                if (rate == null) fail(listOf(FieldError("exchangeRate", "missing_rate", "No manual rate for this currency pair")))
                else {
                    onAction(EditorAction.ExchangeRate(rate.rate)); onAction(EditorAction.ExchangeDate(rate.asOf.toString()))
                    recalculateExchange()
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { fail(listOf(FieldError("exchangeRate", "storage", "Rate could not be loaded"))) }
        }
    }

    fun recalculateExchange() {
        val current = mutableState.value
        val record = current.record ?: return
        val target = current.foreignCurrency ?: return
        if (current.saving || current.saved != null || current.closed) return
        val amount = runCatching { Money.parse(record.amountText, record.currency) }.getOrNull()
        val rate = runCatching { ExchangeRules.parseRate(record.exchangeRateText) }.getOrNull()
        when {
            amount == null || amount.minor <= 0 -> fail(listOf(FieldError("amount", "positive_amount", "Enter a positive amount")))
            rate == null -> fail(listOf(FieldError("exchangeRate", "invalid_rate", "Enter a valid rate")))
            record.exchangeRateSource != record.currency || record.exchangeRateTarget != target ->
                fail(listOf(FieldError("exchangeRate", "exchange_currency_changed", "Confirm the rate for the new currency pair")))
            else -> try { onAction(EditorAction.ExchangeAmount(ExchangeRules.convert(amount, rate, target).decimalText())) }
                catch (_: ArithmeticException) { fail(listOf(FieldError("exchangeAmount", "overflow", "Value exceeds supported range"))) }
        }
    }

    fun submit() {
        val current = mutableState.value
        val record = current.record ?: return
        if (current.saving || current.importingAttachment || current.saved != null || current.closed) return
        val amount = runCatching { Money.parse(record.amountText, record.currency) }.getOrNull()
        val tags = record.tagsText.split(',', '，').map(String::trim).filter(String::isNotEmpty).distinct()
        val inputErrors = mutableListOf<FieldError>()
        val exchange = current.foreignCurrency?.let { currency ->
            val actual = runCatching { Money.parse(record.exchangeAmountText, currency) }.getOrNull()
            val rate = runCatching { ExchangeRules.parseRate(record.exchangeRateText) }.getOrNull()
            val date = runCatching { LocalDate.parse(record.exchangeDateText) }.getOrNull()
            if (rate == null) inputErrors += FieldError("exchangeRate", "invalid_rate", "Enter a positive decimal rate")
            if (actual == null || actual.minor <= 0) inputErrors += FieldError("exchangeAmount", "positive_amount", "Enter the actual foreign amount")
            if (date == null) inputErrors += FieldError("exchangeDate", "invalid_date", "Enter an ISO date")
            if (rate != null && (record.exchangeRateSource != record.currency || record.exchangeRateTarget != currency))
                inputErrors += FieldError("exchangeRate", "exchange_currency_changed", "Confirm the rate for the new currency pair")
            if (actual != null && record.exchangeAmountCurrency != currency)
                inputErrors += FieldError("exchangeAmount", "exchange_currency_changed", "Confirm the actual amount in the new currency")
            if (actual != null && rate != null && date != null) ExchangeSnapshot(requireNotNull(ExchangeRules.foreignAccount(record.transaction)), actual, record.exchangeRateText, date) else null
        }
        if (current.foreignCurrency == null && (record.exchangeAmountText.isNotBlank() || record.exchangeRateText.isNotBlank())) {
            inputErrors += FieldError(if (ExchangeRules.foreignAccount(record.transaction) == record.transaction.fromAccountId) "fromAccountId" else "toAccountId",
                "exchange_account_changed", "Reselect the changed settlement account")
        }
        val fee = record.feeText.takeIf(String::isNotBlank)?.let { text ->
            val currency = record.feeCurrency ?: requireNotNull(current.feeCurrency)
            val parsed = runCatching { Money.parse(text, currency) }.getOrNull()
            if (parsed == null || parsed.minor < 0) inputErrors += FieldError("fee", "invalid_fee", "Enter a nonnegative fee")
            else if (currency != current.feeCurrency) inputErrors += FieldError("fee", "fee_currency_changed", "Confirm the fee in the new cash account currency")
            parsed
        }
        val draft = record.transaction.copy(amount = amount, tags = tags, exchange = exchange, fee = fee)
        val validation = try { PostingCalculator.calculate(draft); emptyList() }
        catch (error: DraftValidationException) { error.errors }
        catch (_: ArithmeticException) { listOf(FieldError("amount", "overflow", "Value exceeds supported range")) }
        val errors = inputErrors + validation + buildList {
            if (tags.size > 20 || tags.any { it.length > 32 }) add(FieldError("tags", "invalid_tags", "Invalid tags"))
            if (draft.note.length > 4000) add(FieldError("note", "note_too_long", "Note is too long"))
        }
        if (errors.isNotEmpty()) {
            mutableState.value = current.copy(errors = errors, validationAttempt = current.validationAttempt + 1)
            return
        }
        mutableState.value = current.copy(saving = true, errors = emptyList())
        submission = (if (recognitionId == null) viewModelScope else applicationScope).launch(Dispatchers.Main.immediate) {
            persistence?.join()
            try {
                val result = if (recognitionId == null) drafts.submit(record.copy(transaction = draft))
                    else requireNotNull(recognitionQueue!!.resolve(recognitionId, QueueOutcome.Save(draft)).saveResult)
                when (result) {
                    is SaveResult.Saved -> mutableState.value = mutableState.value.copy(saving = false, saved = result)
                    is SaveResult.Invalid -> fail(result.fieldErrors)
                    is SaveResult.Conflict -> fail(listOf(FieldError("form", "conflict", "Bill changed elsewhere")))
                    is SaveResult.Failure -> fail(listOf(FieldError("form", result.code, "Save failed")))
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { fail(listOf(FieldError("form", "storage", "Save failed"))) }
        }
    }

    fun keepAndClose() {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(saving = true)
        viewModelScope.launch(Dispatchers.Main.immediate) {
            attachmentJob?.cancelAndJoin()
            persistence?.join()
            if (mutableState.value.persistenceFailed) {
                fail(listOf(FieldError("form", "draft_storage", "Draft not persisted")))
            } else try {
                recognitionId?.let { recognitionQueue!!.resolve(it, QueueOutcome.Cancel) }
                mutableState.value = mutableState.value.copy(saving = false, closed = true)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { fail(listOf(FieldError("form", "storage", "Draft not persisted"))) }
        }
    }

    fun discard() {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(saving = true)
        viewModelScope.launch(Dispatchers.Main.immediate) {
            attachmentJob?.cancelAndJoin()
            persistence?.join()
            try {
                if (recognitionId == null) drafts.remove(key) else recognitionQueue!!.resolve(recognitionId, QueueOutcome.Ignore)
                mutableState.value = mutableState.value.copy(saving = false, closed = true)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { fail(listOf(FieldError("form", "storage", "Discard failed"))) }
        }
    }

    private fun fail(errors: List<FieldError>) {
        mutableState.value = mutableState.value.copy(saving = false, errors = errors,
            validationAttempt = mutableState.value.validationAttempt + 1)
    }

    override fun onCleared() {
        if (recognitionId != null) applicationScope.launch {
            persistence?.join()
            submission?.join()
            try {
                if (recognitionQueue!!.read(recognitionId)?.state == QueueState.EDITING) recognitionQueue.resolve(recognitionId, QueueOutcome.Cancel)
            } catch (error: CancellationException) { throw error } catch (_: Exception) { /* The active entry remains recoverable. */ }
        }
        super.onCleared()
    }

    companion object { val BASIC_KINDS = listOf(BillKind.EXPENSE, BillKind.INCOME, BillKind.TRANSFER) }
}
