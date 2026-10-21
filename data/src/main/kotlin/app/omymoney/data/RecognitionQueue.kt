@file:kotlinx.serialization.UseSerializers(app.omymoney.core.model.InstantSerializer::class)
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.omymoney.data

import androidx.room.withTransaction
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.core.recognition.rules.MeituanTakeoutRules
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import app.omymoney.core.diagnostics.*

@Serializable enum class QueueState { RECOGNIZED, PENDING, EDITING, IGNORED, FAILED, SAVED, EXPIRED }
@Serializable enum class AutomaticReviewReason { AMBIGUOUS_IDENTITY, ORDER_CONFLICT, CROSS_SOURCE_IDENTITY }
@Serializable data class QueueEvent(val state: QueueState, val at: Instant, val reason: String? = null)
@Serializable data class QueueEntry(
    val id: String, val result: RecognitionResult, val draft: TransactionDraft, val state: QueueState,
    val createdAt: Instant, val updatedAt: Instant, val decision: DedupDecision, val events: List<QueueEvent>,
    val billId: String? = null, val savedRevision: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val automaticRevision: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val automaticReviewReason: AutomaticReviewReason? = null,
)
sealed interface QueueOutcome {
    data object Edit : QueueOutcome
    data object Cancel : QueueOutcome
    data object Ignore : QueueOutcome
    data object Reopen : QueueOutcome
    data class Save(val draft: TransactionDraft) : QueueOutcome
}
data class QueueResolution(val entry: QueueEntry, val saveResult: SaveResult? = null)
class QueueException(val code: String) : Exception(code)
data class QueueLimits(val active: Int = 1000, val total: Int = 10000, val expireAfter: Duration = Duration.ofDays(30))

class RecognitionQueue(private val database: LedgerDatabase, private val clock: Clock = Clock.systemUTC(),
    private val limits: QueueLimits = QueueLimits(), private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) {
    private val dao = database.ledgerDao()
    private val ledger = RoomLedgerRepository(database, clock)
    init { require(limits.active in 1..1000 && limits.total in limits.active..10000 && !limits.expireAfter.isNegative && !limits.expireAfter.isZero) }

    suspend fun recordCapture(result: RecognitionResult, windowKey: String): QueueEntry = traced("record_capture") { database.withTransaction {
        require(windowKey.length in 1..256)
        val clean = result.sanitized()
        val packageName = clean.draft().sourcePackage
        require(packageName in RuleRegistry.supportedPackages && clean.draft().source == BillSource.ACCESSIBILITY)
        val key = CAPTURE_WINDOW_PREFIX + packageName
        val windows = decodeCaptureWindows(dao.preference(key))
        val history = entries()
        val byId = history.associateBy { it.id }
        val identity = Deduplicator.compare(DedupCandidate(clean.draft().submissionId, clean.draft(), clean.evidence()),
            history.map { DedupCandidate(it.id, it.result.draft(), it.result.evidence()) })
        val exact = identity.takeIf { it.kind == DedupKind.EXACT_DUPLICATE }?.matches?.firstNotNullOfOrNull { byId[it.id] }
        val possibleDetails = mutableListOf<QueueEntry>()
        for (entry in history) if (entry.automaticRevision != null && continuesToCaptureDetails(entry.continuationResult(), clean)) {
            val bill = entry.billId?.let { dao.bill(it) }
            if (bill != null && bill.deletedAt == null) possibleDetails += entry
        }
        // Nearby equal payments are not identities. Ambiguous details stay reviewable, never another automatic bill.
        val activeIds = windows.slots.map { it.entryId }.toSet()
        val activeDetails = possibleDetails.filter { it.id in activeIds }
        val ambiguousDetail = activeDetails.isNotEmpty() && possibleDetails.size > 1 && exact?.state != QueueState.SAVED
        val slot = windows.slots.lastOrNull { it.windowKey == windowKey && it.ruleId == clean.draft().ruleId }
        val inWindow = slot?.entryId?.let { byId[it] }?.takeIf { sameCapturePayment(it.result, clean) }
        val previous = exact ?: if (ambiguousDetail) null else activeDetails.singleOrNull() ?: inWindow
        var merged = previous?.let { mergeCaptureResult(it.result, clean) } ?: clean
        var entry = previous ?: enqueue(merged)
        if (previous == null && entry.result.draft().submissionId != merged.draft().submissionId) {
            merged = mergeCaptureResult(entry.result, clean)
        }
        val candidate = AutomaticPayment.prepare(merged, dao.accounts().map(AccountEntity::model))
        val orderConflicts = (identity.matches + entry.decision.matches).filter { "order_conflict" in it.reasons }
        val reviewReason = entry.automaticReviewReason ?: when {
            orderConflicts.isNotEmpty() -> AutomaticReviewReason.ORDER_CONFLICT
            ambiguousDetail -> AutomaticReviewReason.AMBIGUOUS_IDENTITY
            (identity.matches + entry.decision.matches).any { match ->
                byId[match.id]?.draft?.source == BillSource.NOTIFICATION
            } -> AutomaticReviewReason.CROSS_SOURCE_IDENTITY
            previous == null && (identity.matches + entry.decision.matches).any { match ->
                byId[match.id]?.let { other ->
                    other.state == QueueState.SAVED && other.draft.source == BillSource.ACCESSIBILITY &&
                        other.draft.sourcePackage != packageName &&
                        MeituanTakeoutRules.PACKAGE in setOf(packageName, other.draft.sourcePackage) &&
                        other.billId?.let { dao.bill(it)?.let { row -> row.deletedAt == null } } == true
                } == true
            } -> AutomaticReviewReason.CROSS_SOURCE_IDENTITY
            else -> null
        }
        if (reviewReason != null) {
            val matches = (entry.decision.matches + orderConflicts + if (ambiguousDetail) possibleDetails.map {
                DedupMatch(it.id, listOf("capture_identity_ambiguous"), Deduplicator.differences(
                    DedupCandidate(entry.id, clean.draft(), clean.evidence()), DedupCandidate(it.id, it.draft, it.result.evidence())))
            } else emptyList()).distinctBy { it.id }
            entry = entry.copy(automaticReviewReason = reviewReason, decision = DedupDecision(DedupKind.SIMILAR, matches))
            write(entry)
        }
        val canRecord = candidate != null && entry.automaticReviewReason == null
        if (previous != null && entry.state in setOf(QueueState.PENDING, QueueState.FAILED) && dao.draft("recognition/${entry.id}") == null) {
            entry = entry.copy(result = merged, draft = merged.draft())
            write(entry)
        }
        if (canRecord && entry.state in setOf(QueueState.PENDING, QueueState.FAILED) && dao.draft("recognition/${entry.id}") == null) {
            val resolved = resolve(entry.id, QueueOutcome.Save(requireNotNull(candidate))).entry
            entry = if (resolved.state == QueueState.SAVED) resolved.copy(automaticRevision = resolved.savedRevision) else resolved
            write(entry)
        } else if (canRecord && entry.state == QueueState.SAVED && entry.automaticRevision != null) {
            // Enrichment is an ordinary revisioned edit; a user edit or deletion always wins.
            val row = entry.billId?.let { dao.bill(it) }
            if (row != null && row.deletedAt == null && row.revision == entry.automaticRevision) {
                val bill = LedgerJson.decodeBill(row)
                val before = bill.toDraft(bill.submissionId)
                val enriched = enrichAutomaticDraft(before, requireNotNull(candidate))
                if (enriched != before) {
                    val saved = ledger.save(enriched.copy(submissionId = UUID.randomUUID().toString()), bill.revision)
                    if (saved is SaveResult.Saved) entry = entry.copy(automaticRevision = saved.revision)
                }
            }
            entry = entry.copy(result = merged)
            write(entry)
        }
        val nextSlots = windows.slots.filterNot { it.windowKey == windowKey && it.ruleId == clean.draft().ruleId } +
            CaptureSlot(windowKey, clean.draft().ruleId, entry.id)
        val nextWindow = LedgerJson.format.encodeToString(CaptureWindows(nextSlots.takeLast(16)))
        if (dao.preference(key) != nextWindow) dao.upsertPreference(PreferenceEntity(key, nextWindow))
        entry
    } }

    suspend fun closeCaptureWindow(packageName: String, windowKey: String? = null) = database.withTransaction {
        require(packageName in RuleRegistry.supportedPackages)
        val key = CAPTURE_WINDOW_PREFIX + packageName
        if (windowKey == null) dao.removePreference(key)
        else {
            val remaining = decodeCaptureWindows(dao.preference(key)).slots.filterNot { it.windowKey == windowKey }
            if (remaining.isEmpty()) dao.removePreference(key)
            else dao.upsertPreference(PreferenceEntity(key, LedgerJson.format.encodeToString(CaptureWindows(remaining))))
        }
    }

    suspend fun enqueue(result: RecognitionResult): QueueEntry = traced("enqueue") { database.withTransaction {
        val clean = result.sanitized()
        val draft = clean.draft()
        validateDraftIdentity(draft)
        if (dao.book(draft.bookId) == null) throw QueueException("missing_book")
        val entries = entries()
        val originalHistory = entries.map { DedupCandidate(it.id, it.result.draft(), it.result.evidence()) }
        val identity = Deduplicator.compare(DedupCandidate(draft.submissionId, draft, clean.evidence()), originalHistory)
        if (identity.kind == DedupKind.EXACT_DUPLICATE) {
            entries.firstOrNull { entry -> identity.matches.any { it.id == entry.id } }?.let { return@withTransaction it }
        }
        val nearBills = draft.occurredAt?.let { time ->
            dao.billsNear(draft.bookId, time.minusSeconds(60).epochSecond, time.plusSeconds(60).epochSecond)
        }.orEmpty().associateBy { it.id }
        val history = entries.mapNotNull { entry ->
            val current = if (entry.state == QueueState.SAVED) {
                val row = if (draft.occurredAt != null) nearBills[entry.billId] else {
                    val captured = entry.draft.recognizedAt
                    if (draft.recognizedAt == null || captured == null || Duration.between(captured, draft.recognizedAt).abs().seconds > 3600) null
                    else entry.billId?.let { dao.bill(it) }
                }
                row?.takeIf { it.deletedAt == null }?.let(LedgerJson::decodeBill)?.let { it.toDraft(it.submissionId) }
            } else entry.draft
            current?.let { DedupCandidate(entry.id, it, entry.result.evidence()) }
        }.toMutableList()
        val recordedIds = entries.mapNotNull { it.billId }.toSet()
        history += nearBills.values.filter { it.id !in recordedIds }.map { row ->
            val bill = LedgerJson.decodeBill(row)
            DedupCandidate("bill/${bill.id}", bill.toDraft(bill.submissionId))
        }
        val decision = Deduplicator.compare(DedupCandidate(draft.submissionId, draft, clean.evidence()), history)
        if (decision.kind == DedupKind.EXACT_DUPLICATE) {
            val existing = entries.firstOrNull { entry -> decision.matches.any { it.id == entry.id } }
            if (existing != null) return@withTransaction existing
        }
        checkCapacity(entries)
        val now = clock.instant()
        val entry = QueueEntry(UUID.randomUUID().toString(), clean, draft, QueueState.PENDING, now, now, decision,
            listOf(QueueEvent(QueueState.RECOGNIZED, now), QueueEvent(QueueState.PENDING, now)))
        write(entry)
        entry
    } }

    suspend fun read(id: String): QueueEntry? = dao.queueEntry(id)?.model()
    suspend fun entries(): List<QueueEntry> = dao.allQueueEntries().map { it.model() }
    fun observe(): Flow<List<QueueEntry>> = dao.observeQueue().map { rows -> rows.map { it.model() } }
    fun observePendingCount(): Flow<Int> = observe().map { rows -> rows.count { it.state in ACTIVE_STATES } }

    suspend fun persistDraft(entryId: String, record: EditorDraft) = database.withTransaction {
        val entry = read(entryId) ?: throw QueueException("not_found")
        if (entry.state !in ACTIVE_STATES) throw QueueException("not_editable")
        val original = entry.result.draft()
        if (record.key != "recognition/$entryId" || record.transaction.id != null || record.transaction.submissionId != original.submissionId) {
            throw QueueException("invalid_draft")
        }
        validateDraftIdentity(record.transaction)
        val draft = record.transaction.copy(amount = runCatching { Money.parse(record.amountText, record.currency) }.getOrNull(),
            tags = record.tagsText.split(',', '，').map(String::trim).filter(String::isNotBlank).distinct())
        DraftRepository(database).write(record)
        // Text edits do not append a state event for every keystroke.
        write(entry.copy(draft = draft))
    }

    suspend fun resolve(entryId: String, outcome: QueueOutcome): QueueResolution = traced("resolve_${outcome.javaClass.simpleName}") { database.withTransaction {
        val current = read(entryId) ?: throw QueueException("not_found")
        if (current.state == QueueState.SAVED && outcome is QueueOutcome.Save) {
            return@withTransaction QueueResolution(current, SaveResult.Saved(requireNotNull(current.billId), requireNotNull(current.savedRevision)))
        }
        if (current.state == QueueState.SAVED) throw QueueException("already_saved")
        if (current.state in setOf(QueueState.IGNORED, QueueState.EXPIRED) && outcome != QueueOutcome.Reopen) throw QueueException("reopen_required")
        var save: SaveResult? = null
        var next = current
        val state = when (outcome) {
            QueueOutcome.Edit -> QueueState.EDITING
            QueueOutcome.Cancel, QueueOutcome.Reopen -> {
                if (current.state !in ACTIVE_STATES && entries().count { it.state in ACTIVE_STATES } >= limits.active) throw QueueException("active_limit")
                QueueState.PENDING
            }
            QueueOutcome.Ignore -> QueueState.IGNORED
            is QueueOutcome.Save -> {
                // Queue submissions always create a new bill and keep one stable idempotency key.
                val draft = outcome.draft.copy(id = null, submissionId = current.result.draft().submissionId)
                validateDraftIdentity(draft)
                if (outcome.draft.id != null || dao.book(draft.bookId) == null) throw QueueException("invalid_draft")
                save = ledger.save(draft, null)
                next = next.copy(draft = draft)
                if (save is SaveResult.Saved) {
                    next = next.copy(billId = save.billId, savedRevision = save.revision)
                    dao.removeDraft("recognition/${current.id}")
                    QueueState.SAVED
                } else QueueState.FAILED
            }
        }
        val reason = when (save) {
            is SaveResult.Invalid -> "invalid_fields"
            is SaveResult.Conflict -> "conflict"
            is SaveResult.Failure -> save.code
            else -> null
        }
        next = next.transition(state, reason)
        write(next)
        QueueResolution(next, save)
    } }

    suspend fun expire(): Int = database.withTransaction {
        val boundary = clock.instant().minus(limits.expireAfter)
        val expired = entries().filter { it.state in setOf(QueueState.PENDING, QueueState.FAILED) && !it.updatedAt.isAfter(boundary) }
        expired.forEach { write(it.transition(QueueState.EXPIRED, "review_expired")) }
        expired.size
    }

    private fun checkCapacity(entries: List<QueueEntry>) {
        if (entries.size >= limits.total) throw QueueException("history_limit")
        if (entries.count { it.state in ACTIVE_STATES } >= limits.active) throw QueueException("active_limit")
    }

    private fun QueueEntry.transition(state: QueueState, reason: String?): QueueEntry {
        if (state == this.state && reason == null) return this
        if (events.size >= 512) throw QueueException("event_limit")
        val now = clock.instant()
        return copy(state = state, updatedAt = now, events = events + QueueEvent(state, now, reason))
    }

    private suspend fun write(entry: QueueEntry) {
        val entity = entry.entity()
        if (entity.payloadJson.toByteArray().size > MAX_ENTRY_BYTES) throw QueueException("entry_limit")
        dao.upsertQueueEntry(entity)
    }

    private suspend fun <T> traced(operation: String, block: suspend () -> T): T = try {
        block().also { result ->
            diagnostics.recordSafely("queue_operation_returned") { buildJsonObject {
                put("operation", operation); put("outerTransactionPending", database.inTransaction())
                val entry = when (result) { is QueueEntry -> result; is QueueResolution -> result.entry; else -> null }
                entry?.let {
                    put("entryId", it.id); put("state", it.state.name); put("dedup", it.decision.kind.name)
                    put("reason", it.events.lastOrNull()?.reason)
                    put("automaticReviewReason", it.automaticReviewReason?.name)
                }
            } }
        }
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { diagnostics.recordSafely("queue_error", DiagnosticRedactor.failure(error)); throw error }

    companion object {
        const val CAPTURE_WINDOW_PREFIX = "capture_window/"
        const val MAX_ENTRY_BYTES = 262144
        val ACTIVE_STATES = setOf(QueueState.RECOGNIZED, QueueState.PENDING, QueueState.EDITING, QueueState.FAILED)
        internal fun validateDraftIdentity(draft: TransactionDraft) {
            listOfNotNull(draft.id, draft.submissionId, draft.bookId, draft.fromAccountId, draft.toAccountId, draft.categoryId, draft.linkedBillId).forEach {
                if (runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false).not()) throw QueueException("invalid_identity")
            }
        }
    }
}

fun RecognitionResult.draft(): TransactionDraft = when (this) {
    is RecognitionResult.Matched -> draft
    is RecognitionResult.NeedsReview -> partialDraft
    is RecognitionResult.Unsupported -> throw QueueException("unsupported")
}
fun RecognitionResult.evidence(): List<FieldEvidence> = when (this) {
    is RecognitionResult.Matched -> evidence
    is RecognitionResult.NeedsReview -> evidence
    is RecognitionResult.Unsupported -> emptyList()
}
private fun RecognitionResult.sanitized(): RecognitionResult {
    val draft = draft().let { original ->
        fun safe(value: String?): String? = value?.let { require(it.length <= 256); EvidencePrivacy.text(it) }
        original.copy(note = EvidencePrivacy.text(original.note), payment = original.payment?.let {
            PaymentDetails(safe(it.counterparty), safe(it.fromAccountHint), safe(it.toAccountHint))
        })
    }
    val evidence = evidence()
    if (evidence.size > 256 || evidence.any { it.field.length > 64 || it.rawText.length > 4096 || it.nodeIndices.size > 1024 }) throw QueueException("evidence_limit")
    val safe = evidence.map { if (it.field in ORDER_ID_FIELDS) it else EvidencePrivacy.redact(it) }
    return when (this) {
        is RecognitionResult.Matched -> copy(draft = draft, evidence = safe)
        is RecognitionResult.NeedsReview -> copy(partialDraft = draft, evidence = safe)
        is RecognitionResult.Unsupported -> throw QueueException("unsupported")
    }
}
internal fun QueueEntry.entity() = QueueEntity(id, state.name, createdAt.epochSecond, LedgerJson.format.encodeToString(this))
internal fun QueueEntity.model(): QueueEntry = LedgerJson.format.decodeFromString<QueueEntry>(payloadJson).also {
    require(it.id == id && it.state.name == state && it.createdAt.epochSecond == createdAtSeconds) { "Queue identity mismatch" }
}
