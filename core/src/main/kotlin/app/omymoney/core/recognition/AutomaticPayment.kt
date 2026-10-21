package app.omymoney.core.recognition

import app.omymoney.core.model.*
import app.omymoney.core.ledger.DebtRules
import app.omymoney.core.ledger.PostingCalculator

object AutomaticPayment {
    fun prepare(result: RecognitionResult, accounts: List<AssetAccount>): TransactionDraft? {
        val draft = when (result) {
            is RecognitionResult.Matched -> result.draft
            is RecognitionResult.NeedsReview -> {
                if (result.issues.any { it.field != "occurredAt" || it.code != "missing" }) return null
                result.partialDraft
            }
            is RecognitionResult.Unsupported -> return null
        }
        if (!PostingCalculator.allowsUnassignedAccounts(draft) || draft.amount == null || draft.amount.minor <= 0) return null
        val observed = draft.recognizedAt ?: return null
        val evidence = when (result) {
            is RecognitionResult.Matched -> result.evidence
            is RecognitionResult.NeedsReview -> result.evidence
            else -> emptyList()
        }
        val incoming = draft.kind == BillKind.INCOME
        fun hints(from: Boolean) = evidence.filter { it.field == (if (from) "fromAccountHint" else "toAccountHint") ||
            it.field == "accountHint" && draft.kind in setOf(BillKind.EXPENSE, BillKind.INCOME) && from != incoming }
        fun hint(from: Boolean) = hints(from).map { EvidencePrivacy.text(it.rawText).trim() }
            .filter { it.isNotBlank() && it.length <= 256 }.distinct().singleOrNull()
        fun account(from: Boolean): String? {
            val eligible = accounts.filter { DebtRules.acceptsAccount(draft.kind, from, it) }
            val evidenceForSide = hints(from).map { it.copy(field = "accountHint") }
            if (evidenceForSide.isEmpty()) return null
            val hasTail = evidenceForSide.any { Regex("(?<![0-9])[0-9]{4}(?![0-9])").containsMatchIn(it.rawText) }
            if (!hasTail) {
                val values = evidenceForSide.map { normalized(it.rawText) }.distinct()
                val value = values.singleOrNull() ?: return null
                return eligible.filter { !it.archived && it.currency == draft.amount.currency &&
                    (listOf(it.name) + it.aliases).any { name -> normalized(name) == value } }.singleOrNull()?.id
            }
            return AccountMatcher.match(draft, eligible, evidenceForSide).options.filter {
                val account = eligible.single { account -> account.id == it.accountId }
                val names = (listOf(account.name) + account.aliases).map(::fundingName)
                val observedNames = evidenceForSide.map { evidence -> fundingName(evidence.rawText) }.distinct()
                it.differences.isEmpty() && it.score >= 80 && "card_tail" in it.reasons &&
                    observedNames.size == 1 && observedNames.single().isNotBlank() && observedNames.single() in names
            }.singleOrNull()?.accountId
        }
        val from = if (incoming) null else draft.fromAccountId ?: account(true)
        val to = if (draft.kind == BillKind.EXPENSE) null else draft.toAccountId ?: account(false)
        // Two hints resolving to the same asset cannot become a fictitious transfer.
        val sameAccount = from != null && from == to
        return draft.copy(occurredAt = draft.occurredAt ?: observed,
            timeSource = if (draft.occurredAt == null) TimeSource.CAPTURED else draft.timeSource,
            fromAccountId = from.takeUnless { sameAccount }, toAccountId = to.takeUnless { sameAccount },
            payment = PaymentDetails(draft.payment?.counterparty, hint(true), hint(false)))
    }
    private fun normalized(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), "")
    private fun fundingName(value: String) = normalized(value).replace(Regex("(?:尾号)?\\**\\(?[0-9]{4}\\)?"), "")
}
