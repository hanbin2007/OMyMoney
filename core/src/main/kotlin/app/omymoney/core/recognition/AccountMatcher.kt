package app.omymoney.core.recognition

import app.omymoney.core.model.AssetAccount
import app.omymoney.core.model.TransactionDraft
import app.omymoney.core.model.AccountKind
import java.text.Normalizer
import java.util.Locale

data class AccountSuggestion(val accountId: String, val score: Int, val reasons: List<String>, val differences: List<String>)
data class AccountSuggestions(val options: List<AccountSuggestion>, val ambiguous: Boolean)

object AccountMatcher {
    fun match(draft: TransactionDraft, accounts: List<AssetAccount>, evidence: List<FieldEvidence> = emptyList()): AccountSuggestions {
        val hints = evidence.filter { it.field == "accountHint" }.map { normalized(it.rawText.take(4096)) }
        val tails = hints.flatMap { Regex("(?<![0-9])[0-9]{4}(?![0-9])").findAll(it).map { match -> match.value }.toList() }.toSet()
        val scenario = when (draft.sourcePackage) {
            "com.tencent.mm" -> "微信"
            "com.eg.android.AlipayGphone" -> "支付宝"
            "com.xunmeng.pinduoduo" -> "多多"
            "com.jingdong.app.mall" -> "京东"
            "com.sankuai.meituan" -> "美团"
            "com.ss.android.ugc.aweme" -> "抖音"
            else -> null
        }
        val options = accounts.filter { !it.archived && it.kind !in app.omymoney.core.ledger.DebtRules.debtAccountKinds &&
            (draft.amount == null || it.currency == draft.amount.currency) }.mapNotNull { account ->
            val names = (listOf(account.name) + account.aliases).map(::normalized).filter { it.length >= 2 }
            val reasons = mutableListOf<String>()
            val differences = mutableListOf<String>()
            var score = 0
            if (names.any { name -> hints.any { name in it } }) { score += 60; reasons += "name" }
            else if (names.any { name -> hints.any { hint -> similarity(name, hint) >= 0.65 } }) { score += 25; reasons += "similar_name" }
            if (account.cardLastFour != null && tails.isNotEmpty()) {
                if (account.cardLastFour in tails) { score += 80; reasons += "card_tail" }
                if (tails.any { it != account.cardLastFour }) { score -= 40; differences += "card_tail" }
            }
            if (scenario != null && account.kind == AccountKind.WALLET && names.any { scenario in it }) { score += 10; reasons += "scenario" }
            if (reasons.isEmpty()) null else AccountSuggestion(account.id, score, reasons, differences)
        }.sortedWith(compareByDescending<AccountSuggestion> { it.score }.thenBy { it.accountId })
        return AccountSuggestions(options, options.size > 1 && options[0].score == options[1].score)
    }

    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), "")

    private fun similarity(name: String, hint: String): Double {
        fun pairs(value: String) = value.windowed(2).toSet()
        val first = pairs(name); val second = pairs(hint)
        return if (first.isEmpty() || second.isEmpty()) 0.0 else 2.0 * first.intersect(second).size / (first.size + second.size)
    }
}
