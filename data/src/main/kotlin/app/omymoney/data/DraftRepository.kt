package app.omymoney.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import app.omymoney.core.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class EditorDraft(
    val key: String,
    val transaction: TransactionDraft,
    val currency: CurrencySpec = CurrencySpec.CNY,
    val amountText: String = "",
    val tagsText: String = "",
    val expectedRevision: Long? = null,
    val dirty: Boolean = false,
    val feeText: String = transaction.fee?.decimalText().orEmpty(),
    val feeCurrency: CurrencySpec? = transaction.fee?.currency,
    val exchangeAmountText: String = transaction.exchange?.amount?.decimalText().orEmpty(),
    val exchangeRateText: String = transaction.exchange?.rate.orEmpty(),
    val exchangeDateText: String = (transaction.exchange?.asOf ?: java.time.LocalDate.now(transaction.zoneId)).toString(),
    val exchangeAmountCurrency: CurrencySpec? = transaction.exchange?.amount?.currency,
    val exchangeRateSource: CurrencySpec? = transaction.exchange?.let { currency },
    val exchangeRateTarget: CurrencySpec? = transaction.exchange?.amount?.currency,
)

class DraftRepository(private val database: LedgerDatabase) {
    private val dao = database.ledgerDao()
    private val ledger = RoomLedgerRepository(database)

    suspend fun read(key: String): EditorDraft? = dao.draft(key)?.let { entity ->
        LedgerJson.format.decodeFromString<EditorDraft>(entity.payloadJson).also {
            require(it.key == key && it.transaction.bookId == entity.bookId) { "Draft identity mismatch" }
        }
    }

    suspend fun write(draft: EditorDraft) {
        require(draft.key.isNotBlank())
        val json = LedgerJson.format.encodeToString(draft)
        require(json.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "Draft exceeds the storage limit" }
        database.withTransaction {
            if (draft.key.startsWith("schedule/")) {
                val execution = dao.scheduleExecution(draft.key.removePrefix("schedule/"))?.model()
                require(execution?.state == ScheduleExecutionState.PENDING && draft.transaction.submissionId == execution.id &&
                    draft.transaction.bookId == execution.template.bookId) { "Schedule draft is no longer pending" }
            }
            dao.upsertDraft(DraftEntity(draft.key, draft.transaction.bookId, json))
        }
    }

    suspend fun remove(key: String) = database.withTransaction {
        if (key.startsWith("schedule/")) {
            dao.scheduleExecution(key.removePrefix("schedule/"))?.model()?.takeIf { it.state == ScheduleExecutionState.PENDING }?.let {
                dao.upsertScheduleExecution(it.copy(state = ScheduleExecutionState.SKIPPED).entity())
            }
        }
        dao.removeDraft(key)
    }

    suspend fun billForEditing(id: String): EditorDraft? = dao.bill(id)?.takeIf { it.deletedAt == null }?.let {
        val bill = LedgerJson.decodeBill(it)
        EditorDraft("edit/$id", bill.toDraft(), bill.amount.currency, bill.amount.decimalText(),
            bill.tags.joinToString(", "), bill.revision)
    }

    suspend fun submit(draft: EditorDraft): SaveResult = try {
        database.withTransaction {
            val execution = if (draft.key.startsWith("schedule/")) {
                val row = dao.scheduleExecution(draft.key.removePrefix("schedule/"))?.model()
                if (row == null || row.state == ScheduleExecutionState.SKIPPED || draft.transaction.submissionId != row.id ||
                    draft.transaction.bookId != row.template.bookId || draft.transaction.id != null || draft.transaction.source != BillSource.SCHEDULE) {
                    return@withTransaction SaveResult.Failure("schedule_unavailable")
                }
                row
            } else null
            val result = ledger.save(draft.transaction, draft.expectedRevision)
            if (result is SaveResult.Saved) {
                if (execution != null) dao.upsertScheduleExecution(execution.copy(state = ScheduleExecutionState.POSTED,
                    template = draft.transaction, billId = result.billId, failure = null).entity())
                dao.removeDraft(draft.key)
            }
            result
        }
    } catch (_: SQLiteException) {
        SaveResult.Failure("storage")
    }
}
