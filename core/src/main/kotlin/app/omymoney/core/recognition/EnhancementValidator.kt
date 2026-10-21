package app.omymoney.core.recognition

import app.omymoney.core.model.*
import kotlinx.serialization.json.*

object EnhancementValidator {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
    private val number = Regex("[+-]?[0-9]+(?:\\.[0-9]+)?")

    fun decode(json: String): EnhancementResult = try {
        require(json.length in 1..65_536)
        val decoded = this.json.decodeFromString<EnhancementResult.Suggestions>(json)
        require(decoded.fields.size <= EnhancementField.entries.size)
        val raw = this.json.parseToJsonElement(json).jsonObject.getValue("fields").jsonArray
        raw.forEach { field ->
            val objectValue = field.jsonObject
            require(objectValue.getValue("quote").jsonPrimitive.isString)
            val value = objectValue.getValue("value").jsonObject
            // Serialization accepts numeric tokens for String, but this boundary must not.
            for (key in listOf("id", "text")) value[key]?.let { require(it.jsonPrimitive.isString) }
        }
        decoded
    } catch (_: Exception) {
        EnhancementResult.Failed(EnhancementFailure.INVALID_RESPONSE)
    }

    fun apply(request: EnhancementRequest, current: EnhancementState, result: EnhancementResult): ValidatedEnhancement {
        if (result is EnhancementResult.Unavailable) return ValidatedEnhancement(current.draft, EnhancementStatus.UNAVAILABLE)
        if (result is EnhancementResult.Failed) return ValidatedEnhancement(current.draft, EnhancementStatus.FAILED)
        result as EnhancementResult.Suggestions
        val latest = EnhancementRequest.snapshot(current.draft)
        val original = request.draft
        if (result.draftVersion != request.draftVersion || current.version != request.draftVersion ||
            latest.bookId != original.bookId || latest.id != original.id || latest.submissionId != original.submissionId ||
            latest.kind != original.kind) return ValidatedEnhancement(current.draft, EnhancementStatus.STALE)
        if (result.fields.size > EnhancementField.entries.size) return ValidatedEnhancement(current.draft, EnhancementStatus.FAILED)
        val repeated = result.fields.groupingBy { it.field }.eachCount().filterValues { it > 1 }.keys
        val accepted = mutableSetOf<EnhancementField>()
        val rejected = mutableSetOf<EnhancementField>()
        var draft = current.draft
        for (suggestion in result.fields) {
            val field = suggestion.field
            val evidence = request.evidence.getOrNull(suggestion.evidenceIndex)
            val eligible = field !in repeated && field !in current.manuallyEdited && field !in request.manuallyEdited &&
                value(original, field) == value(latest, field) && evidence != null &&
                suggestion.quote.length in 1..2048 && evidence.rawText.contains(suggestion.quote)
            val grounded = eligible && when (field) {
                EnhancementField.AMOUNT -> {
                    val money = (suggestion.value as? EnhancementValue.Amount)?.money
                    money != null && money.minor > 0 && money == original.amount && evidence!!.field == "amount" &&
                        runCatching { Money.parse(suggestion.quote, money.currency) }.getOrNull() == money &&
                        request.evidence.filter { it.field == "amount" }.all { proof ->
                            number.findAll(proof.rawText).map { runCatching { Money.parse(it.value, money.currency) }.getOrNull() }
                                .toList() == listOf(money)
                        }
                }
                EnhancementField.FROM_ACCOUNT, EnhancementField.TO_ACCOUNT -> {
                    val id = (suggestion.value as? EnhancementValue.CandidateId)?.id
                    val from = field == EnhancementField.FROM_ACCOUNT
                    val compatible = if (from) original.kind in setOf(BillKind.EXPENSE, BillKind.TRANSFER)
                        else original.kind in setOf(BillKind.INCOME, BillKind.TRANSFER)
                    compatible && id in request.allowedAccountIds && id in current.allowedAccountIds &&
                        evidence!!.field in setOf("accountHint", if (from) "fromAccountHint" else "toAccountHint")
                }
                EnhancementField.CATEGORY -> {
                    val id = (suggestion.value as? EnhancementValue.CandidateId)?.id
                    original.kind in setOf(BillKind.EXPENSE, BillKind.INCOME) && id in request.allowedCategoryIds &&
                        id in current.allowedCategoryIds && evidence!!.field in setOf("merchant", "note", "categoryHint")
                }
                EnhancementField.NOTE -> (suggestion.value as? EnhancementValue.Text)?.text == suggestion.quote &&
                    evidence!!.field in setOf("merchant", "note")
            }
            if (!grounded) { rejected += field; continue }
            draft = when (field) {
                EnhancementField.AMOUNT -> draft.copy(amount = (suggestion.value as EnhancementValue.Amount).money)
                EnhancementField.FROM_ACCOUNT -> draft.copy(fromAccountId = (suggestion.value as EnhancementValue.CandidateId).id)
                EnhancementField.TO_ACCOUNT -> draft.copy(toAccountId = (suggestion.value as EnhancementValue.CandidateId).id)
                EnhancementField.CATEGORY -> draft.copy(categoryId = (suggestion.value as EnhancementValue.CandidateId).id)
                EnhancementField.NOTE -> draft.copy(note = (suggestion.value as EnhancementValue.Text).text)
            }
            accepted += field
        }
        if (draft.fromAccountId != null && draft.fromAccountId == draft.toAccountId) {
            val accountFields = setOf(EnhancementField.FROM_ACCOUNT, EnhancementField.TO_ACCOUNT)
            rejected += accepted.intersect(accountFields)
            accepted -= accountFields
            draft = draft.copy(fromAccountId = current.draft.fromAccountId, toAccountId = current.draft.toAccountId)
        }
        return ValidatedEnhancement(draft, EnhancementStatus.VALIDATED, accepted.toSet(), rejected.toSet())
    }

    private fun value(draft: EnhancementDraft, field: EnhancementField): Any? = when (field) {
        EnhancementField.AMOUNT -> draft.amount
        EnhancementField.FROM_ACCOUNT -> draft.fromAccountId
        EnhancementField.TO_ACCOUNT -> draft.toAccountId
        EnhancementField.CATEGORY -> draft.categoryId
        EnhancementField.NOTE -> draft.note
    }
}
