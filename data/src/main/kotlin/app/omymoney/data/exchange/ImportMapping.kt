@file:kotlinx.serialization.UseSerializers(app.omymoney.core.model.InstantSerializer::class)

package app.omymoney.data.exchange

import app.omymoney.core.model.*
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable enum class ImportField(val column: String) {
    SOURCE_ID("source_id"), TIME("occurred_at"), ZONE("zone"), KIND("kind"), AMOUNT("amount"),
    CURRENCY("currency"), SCALE("scale"), FROM("from_account"), TO("to_account"),
    CATEGORY("category"), NOTE("note"), TAGS("tags"), FEE("fee"),
    EXCLUDE_STATS("exclude_stats"), EXCLUDE_BUDGET("exclude_budget"), LINK("linked_source_id"),
    FX_ACCOUNT("exchange_account"), FX_CURRENCY("exchange_currency"), FX_SCALE("exchange_scale"),
    FX_AMOUNT("exchange_amount"), FX_RATE("exchange_rate"), FX_DATE("exchange_date"),
}
@Serializable data class ImportMapping(val columns: Map<ImportField, Int> = emptyMap(),
    val accounts: Map<String, String> = emptyMap(), val categories: Map<String, String> = emptyMap(),
    val currencies: Map<String, CurrencySpec> = emptyMap())
@Serializable data class ImportIssue(val field: String, val code: String)
@Serializable data class ImportRow(val number: Int, val raw: List<String>, val draft: TransactionDraft? = null,
    val issues: List<ImportIssue> = emptyList(), val duplicate: Boolean = false) {
    val selectable get() = draft != null && issues.isEmpty() && !duplicate
}
@Serializable data class ImportPreview(val previewId: String, val bookId: String, val columns: List<String>,
    val mapping: ImportMapping, val rows: List<ImportRow>, val expiresAt: Instant,
    val profile: String = "CUSTOM", val fatalError: String? = null)
data class ImportChoices(val rows: Set<Int>)
data class ImportResult(val imported: Int, val duplicates: Int)
@Serializable data class ImportReceipt(val id: String, val billId: String, val bookId: String,
    val fileHash: String, val format: ExchangeFormat, val row: Int, val columns: List<String>, val raw: List<String>, val importedAt: Instant)

fun ImportPreview.sourceValue(row: ImportRow, field: ImportField): String =
    if (profile == "QIANJI_MODELED") QianjiMapping.value(field, row.raw, mapping)
    else mapping.columns[field]?.let { row.raw.getOrNull(it) }.orEmpty()
