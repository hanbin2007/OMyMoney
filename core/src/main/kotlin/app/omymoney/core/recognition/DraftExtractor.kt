package app.omymoney.core.recognition

import app.omymoney.core.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID

object DraftExtractor {
    private val number = Regex("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")
    private val localTime = DateTimeFormatterBuilder().appendPattern("uuuu-M-d H:mm")
        .optionalStart().appendPattern(":ss").optionalEnd().toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

    fun extract(snapshot: UiSnapshot, context: RecognitionContext, ruleId: String, kind: BillKind?,
        state: PaymentState, amounts: List<FieldCandidate>, times: List<FieldCandidate> = emptyList(),
        merchant: FieldCandidate? = null, remark: FieldCandidate? = null,
        fees: List<FieldCandidate> = emptyList()): RecognitionResult {
        if (state == PaymentState.FAILED) return RecognitionResult.Unsupported("payment_failed")
        val issues = mutableListOf<FieldError>()
        val evidence = mutableListOf<FieldEvidence>()
        fun issue(field: String, code: String) { issues += FieldError(field, code, "$field: $code") }
        fun <T : Any> parse(field: String, candidates: List<FieldCandidate>, parser: (FieldCandidate) -> T?): T? {
            if (candidates.isEmpty()) { issue(field, "missing"); return null }
            if (candidates.any { it.nodeIndices.isEmpty() || it.nodeIndices.any { i -> i !in snapshot.nodes.indices } }) {
                issue(field, "invalid_evidence"); return null
            }
            candidates.forEach { evidence += EvidencePrivacy.redact(FieldEvidence(field, it.nodeIndices.toList(), it.rawText)) }
            val values = candidates.map(parser)
            if (values.any { it == null }) { issue(field, "invalid"); return null }
            val unique = values.distinct()
            if (unique.size != 1) { issue(field, "ambiguous"); return null }
            return unique.single()
        }
        val amount = parse("amount", amounts) { if (currencyMatches(it, snapshot, context.currency)) parseMoney(it.value, context.currency) else null }
        val fee = if (fees.isEmpty()) null else parse("fee", fees) { if (currencyMatches(it, snapshot, context.currency)) parseMoney(it.value, context.currency, allowZero = true) else null }
        val occurredAt = parse("occurredAt", times) { parseTime(it.value, context) }
        if (state != PaymentState.SUCCEEDED) issue("paymentState", state.name.lowercase(Locale.ROOT))
        if (kind == null) issue("kind", "unknown")
        if (context.bookId.isBlank()) issue("bookId", "unassigned")
        fun bounded(candidate: FieldCandidate?) = candidate?.takeIf { it.value.length <= 256 && it.nodeIndices.isNotEmpty() && it.nodeIndices.all(snapshot.nodes.indices::contains) }
        val merchantValue = bounded(merchant)
        merchantValue?.let { evidence += EvidencePrivacy.redact(FieldEvidence("merchant", it.nodeIndices.toList(), it.rawText)) }
        val remarkValue = bounded(remark)
        remarkValue?.let { evidence += EvidencePrivacy.redact(FieldEvidence("note", it.nodeIndices.toList(), it.rawText)) }
        val draft = TransactionDraft(bookId = context.bookId, submissionId = submissionId(snapshot, context, ruleId),
            kind = kind, amount = amount, fee = fee, note = EvidencePrivacy.text((remarkValue ?: merchantValue)?.value.orEmpty()), occurredAt = occurredAt,
            timeSource = if (occurredAt == null) TimeSource.UNKNOWN else TimeSource.PAGE, zoneId = context.zoneId,
            recognizedAt = snapshot.capturedAt, source = BillSource.ACCESSIBILITY, sourcePackage = snapshot.packageName,
            ruleId = ruleId, ruleVersion = 1,
            payment = merchantValue?.let { PaymentDetails(counterparty = EvidencePrivacy.text(it.value)) })
        return if (issues.isEmpty()) RecognitionResult.Matched(draft, evidence.toList(), ruleId)
        else RecognitionResult.NeedsReview(draft, issues.toList(), evidence.toList(), ruleId)
    }

    private fun currencyMatches(candidate: FieldCandidate, snapshot: UiSnapshot, currency: CurrencySpec): Boolean {
        // Capture groups may omit the symbol, so validate the original evidence too.
        val text = cleanText(candidate.rawText + " " + candidate.nodeIndices.joinToString(" ") {
            snapshot.nodes[it].text ?: snapshot.nodes[it].description.orEmpty()
        })
        val markers = mapOf(
            "CNY" to Regex("(?i)\\b(?:CNY|RMB)\\b|人民币|[¥￥]|(?<![港日美欧])元"),
            "USD" to Regex("(?i)\\bUSD\\b|US\\$|美元|(?<![A-Za-z])\\$"),
            "HKD" to Regex("(?i)\\bHKD\\b|HK\\$|港元"),
            "JPY" to Regex("(?i)\\bJPY\\b|日元|円"),
            "EUR" to Regex("(?i)\\bEUR\\b|€|欧元"),
            "GBP" to Regex("(?i)\\bGBP\\b|£|英镑"),
        )
        return markers.filterValues { it.containsMatchIn(text) }.keys.all { it == currency.code }
    }

    private fun parseMoney(raw: String, currency: CurrencySpec, allowZero: Boolean = false): Money? {
        var text = cleanText(raw)
        val leadingSign = text.firstOrNull() in listOf('+', '-')
        if (leadingSign) text = text.drop(1).trim()
        val prefixes = listOf(currency.code) + when (currency.code) {
            "CNY" -> listOf("人民币", "¥")
            "JPY" -> listOf("¥")
            "USD" -> listOf("$")
            "EUR" -> listOf("€")
            "GBP" -> listOf("£")
            "HKD" -> listOf("HK$")
            else -> emptyList()
        }
        prefixes.firstOrNull(text::startsWith)?.let { text = text.removePrefix(it).trim() }
        if (leadingSign && text.firstOrNull() in listOf('+', '-')) return null
        if (currency.code == "CNY") text = text.removeSuffix("元").trim()
        if (!number.matches(text)) return null
        text = text.removePrefix("+").removePrefix("-").replace(",", "")
        return runCatching { Money.parse(text, currency).takeIf { it.minor > 0 || allowZero && it.minor == 0L } }.getOrNull()
    }

    private fun parseTime(raw: String, context: RecognitionContext): Instant? {
        val text = cleanText(raw)
        runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
        val normalized = text.replace('年', '-').replace('月', '-').replace("日", " ")
            .replace('/', '-').replace('.', '-').replace(Regex("\\s+"), " ").trim()
        val local = runCatching { LocalDateTime.parse(normalized, localTime) }.getOrNull() ?: return null
        // A wall time in a timezone gap or overlap is not one unambiguous instant.
        val offsets = context.zoneId.rules.getValidOffsets(local)
        return offsets.singleOrNull()?.let(local::toInstant)
    }

    private fun submissionId(snapshot: UiSnapshot, context: RecognitionContext, ruleId: String): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeUTF(ruleId); output.writeUTF(context.bookId); output.writeUTF(context.currency.code)
            output.writeInt(context.currency.scale); output.writeUTF(context.zoneId.id)
            output.writeUTF(snapshot.packageName); output.writeInt(snapshot.windowId); output.writeUTF(snapshot.capturedAt.toString())
            output.writeBoolean(snapshot.windowClassName != null)
            snapshot.windowClassName?.let(output::writeUTF)
            snapshot.nodes.forEach { node ->
                listOf(node.text, node.description, node.viewId, node.className).forEach {
                    output.writeBoolean(it != null); if (it != null) output.writeUTF(it)
                }
                output.writeInt(node.parentIndex ?: -1)
            }
        }
        return UUID.nameUUIDFromBytes(bytes.toByteArray()).toString()
    }
}
