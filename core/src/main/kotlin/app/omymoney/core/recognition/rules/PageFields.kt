package app.omymoney.core.recognition.rules

import app.omymoney.core.recognition.*

/** Text and description share semantics, but a field never traverses unrelated parents. */
internal class PageFields(val snapshot: UiSnapshot) {
    val raw = snapshot.nodes.map { it.text?.takeIf(String::isNotBlank) ?: it.description.orEmpty() }
    val text = raw.map(::cleanText)
    private val aliases = snapshot.nodes.mapIndexed { index, node ->
        listOfNotNull(node.text, node.description).filter(String::isNotBlank)
            .map { FieldCandidate(cleanText(it), listOf(index), it) }.distinctBy { it.value }
    }
    private val values = aliases.flatten()
    val allText = values.map { it.value }
    fun has(value: String) = cleanText(value) in allText
    fun any(pattern: Regex) = allText.any(pattern::matches)
    fun starts(value: String) = allText.any { it.startsWith(cleanText(value)) }
    fun candidate(index: Int, value: String = text[index]) = FieldCandidate(value, listOf(index), raw[index])
    fun matching(pattern: Regex, group: Int = 0): List<FieldCandidate> = values.mapNotNull { candidate ->
        pattern.matchEntire(candidate.value)?.let { candidate.copy(value = it.groupValues[group]) }
    }
    fun captures(pattern: Regex, group: Int = 1): List<FieldCandidate> = values.flatMap { candidate ->
        pattern.findAll(candidate.value).map { candidate.copy(value = it.groupValues[group]) }.toList()
    }
    private fun label(value: String) = cleanText(value).removeSuffix(":")
    private data class LabelHit(val label: String, val indices: List<Int>, val inline: FieldCandidate? = null)
    private val inlinePatterns = mutableMapOf<String, Regex>()
    private val hitCache = mutableMapOf<List<String>, List<LabelHit>>()
    private fun labelHits(labels: Array<out String>): List<LabelHit> {
        val names = labels.map(::label).distinct().sortedByDescending(String::length)
        return hitCache.getOrPut(names) { text.indices.flatMap { start ->
            val hits = aliases[start].mapNotNull { value ->
                names.firstNotNullOfOrNull { name ->
                    when {
                        value.value == name || value.value == "$name:" -> LabelHit(name, listOf(start))
                        else -> inline(name).matchEntire(value.value)?.let { LabelHit(name, listOf(start), value.copy(value = it.groupValues[1])) }
                    }
                }
            }
            val fragmented = names.firstNotNullOfOrNull { name ->
                var hit: LabelHit? = null
                if (name.length in 2..16) {
                    var joined = ""
                    val indices = mutableListOf<Int>()
                    for (index in start until minOf(start + 16, text.size)) {
                        if (snapshot.nodes[index].parentIndex != snapshot.nodes[start].parentIndex) break
                        val part = aliases[index].map { it.value.removeSuffix(":") }.distinct()
                            .filter { it.isNotEmpty() && name.startsWith(joined + it) }.singleOrNull() ?: break
                        joined += part; indices += index
                        if (joined == name) {
                            if (indices.size > 1) hit = LabelHit(name, indices.toList())
                            break
                        }
                    }
                }
                hit
            }
            // Longest-label selection is per alias; a description cannot erase text evidence.
            hits.filterNot { it.inline == null && fragmented != null && fragmented.label.startsWith(it.label) } + listOfNotNull(fragmented)
        }.distinct() }
    }
    fun hasLabel(vararg labels: String) = labelHits(labels).isNotEmpty()
    fun fields(vararg labels: String): List<FieldCandidate> = labelHits(labels).flatMap { hit ->
        hit.inline?.let { return@flatMap listOf(it) }
        val indices = hit.indices.toMutableList()
        val colon = indices.last() + 1
        if (text.getOrNull(colon) == ":" && snapshot.nodes[colon].parentIndex == snapshot.nodes[indices.last()].parentIndex) indices += colon
        valuesAfter(indices.last()).map { value -> value.copy(nodeIndices = (indices + value.nodeIndices).distinct()) }
    }.distinctBy { it.value to it.nodeIndices }

    private fun descendants(root: Int, limit: Int = 65): List<Int> {
        fun inside(index: Int): Boolean {
            var parent = snapshot.nodes[index].parentIndex
            repeat(RuleEngine.MAX_NODES) {
                if (parent == root) return true
                parent = parent?.let { snapshot.nodes.getOrNull(it)?.parentIndex } ?: return false
            }
            return false
        }
        return ((root + 1) until minOf(root + 1 + limit, text.size)).takeWhile(::inside)
    }
    private fun currencyPair(first: Int, second: Int): List<FieldCandidate> {
        if (second !in text.indices || snapshot.nodes[first].parentIndex != snapshot.nodes[second].parentIndex) return emptyList()
        return aliases[first].filter { it.value in CURRENCY_SYMBOLS }.flatMap { symbol ->
            aliases[second].filter { NUMBER.matches(it.value) }.map { amount ->
                FieldCandidate(symbol.value + amount.value, listOf(first, second), "${symbol.rawText} ${amount.rawText}")
            }
        }
    }
    private fun nodeValues(index: Int): List<FieldCandidate> {
        // A blank layout may wrap one value, but two independent values are not a field.
        val children = descendants(index)
        if (children.size > 64) return emptyList()
        val content = (listOf(index) + children).filter { aliases[it].isNotEmpty() }
        if (content.size == 1) {
            val position = content.single()
            val pairs = currencyPair(position, position + 1)
            return if (pairs.isEmpty()) aliases[position] else pairs + aliases[position].filterNot { it.value in CURRENCY_SYMBOLS }
        }
        val all = content.flatMap { aliases[it] }
        if (all.isNotEmpty() && all.map { it.value }.distinct().size == 1) {
            return listOf(all.first().copy(nodeIndices = content))
        }
        if (content.size == 2) {
            val pairs = currencyPair(content[0], content[1])
            if (pairs.isNotEmpty()) return pairs + all.filter { STATUS_MONEY.matches(it.value) }
        }
        return emptyList()
    }
    private fun valuesAfter(index: Int): List<FieldCandidate> {
        val next = index + 1
        if (next !in text.indices || snapshot.nodes[next].parentIndex != snapshot.nodes[index].parentIndex) return listOf(candidate(index, ""))
        return nodeValues(next).ifEmpty { listOf(candidate(index, "")) }
    }
    private fun inline(label: String) = inlinePatterns.getOrPut(label) { Regex("${Regex.escape(label)}\\s*[:：]?\\s*(.+)") }
    fun after(index: Int): FieldCandidate {
        val value = valuesAfter(index).singleOrNull() ?: candidate(index, "")
        return value.copy(nodeIndices = (listOf(index) + value.nodeIndices).distinct())
    }
    fun before(value: String, pattern: Regex): List<FieldCandidate> = text.indices.flatMap { index ->
        if (aliases[index].none { it.value == value } || index == 0 || snapshot.nodes[index - 1].parentIndex != snapshot.nodes[index].parentIndex) emptyList()
        else aliases[index - 1].filter { pattern.matches(it.value) }.map { it.copy(nodeIndices = listOf(index - 1, index)) }
    }
    fun icon(value: String) = typed(value, "android.widget.ImageView")
    fun typed(value: String, className: String) = values.any { it.value == cleanText(value) && snapshot.nodes[it.nodeIndices.single()].className == className }
    fun type(className: String) = snapshot.nodes.any { it.className == className }

    private val narrativeValues by lazy {
        // Scope remains narrative even if multiple child values make extraction ambiguous.
        labelHits(arrayOf("商品名称", "商品详情", "商品说明", "备注", "转账说明", "优惠说明")).flatMap { hit ->
            hit.inline?.let { return@flatMap listOf(it) }
            val anchor = hit.indices.last()
            val next = anchor + if (text.getOrNull(anchor + 1) == ":") 2 else 1
            if (next !in text.indices || snapshot.nodes[next].parentIndex != snapshot.nodes[anchor].parentIndex) emptyList()
            else (listOf(next) + descendants(next, RuleEngine.MAX_NODES)).flatMap { aliases[it] }
        }
    }
    private val statusValues by lazy { values.filterNot { value ->
        narrativeValues.any { value.nodeIndices.single() in it.nodeIndices && value.rawText == it.rawText } &&
            STATUS_LABELS.none { value.value.startsWith(it) }
    } }
    private fun statusValue(value: String): String {
        val prefix = STATUS_LABELS.firstOrNull { value.startsWith(it) } ?: return value
        return value.removePrefix(prefix).trim().removePrefix(":").trim()
    }
    private fun statusAmount(value: String, status: String): String? {
        val source = statusValue(value)
        val label = cleanText(status).removeSuffix("!")
        if (!source.startsWith(label)) return null
        val suffix = source.removePrefix(label).trim().removePrefix("!").trim().removePrefix(":").trim()
        return suffix.takeIf { it.isEmpty() || STATUS_MONEY.matches(it) }
    }
    fun hasStatus(vararg status: String) = statusValues.any { value -> status.any { statusAmount(value.value, it) != null } }
    fun statusCandidates(vararg status: String) = statusValues.filter { value -> status.any { statusAmount(value.value, it) != null } }
    fun typedStatus(status: String, className: String) = statusValues.any {
        snapshot.nodes[it.nodeIndices.single()].className == className && statusAmount(it.value, status) != null
    }
    fun statusAmounts(vararg status: String = SUCCESS.toTypedArray()): List<FieldCandidate> = statusValues.flatMap { value ->
        status.mapNotNull { statusAmount(value.value, it)?.takeIf(String::isNotEmpty)?.let { amount -> value.copy(value = amount) } }
    }.distinctBy { it.value to it.nodeIndices }

    fun moneyValues(): List<FieldCandidate> {
        val pairs = text.indices.flatMap { currencyPair(it, it + 1) }
        return pairs + matching(STATUS_MONEY)
    }
    fun hasAmount(vararg labels: String) = hasLabel(*labels) || moneyValues().isNotEmpty() || statusAmounts().isNotEmpty()
    fun amounts(vararg labels: String): List<FieldCandidate> {
        val titled = statusAmounts()
        if (hasLabel(*labels)) return (fields(*labels) + titled).distinctBy { it.value to it.nodeIndices }
        val excluded = fields(*NON_PAID_LABELS).flatMap { it.nodeIndices }.toSet()
        return (titled + moneyValues().filterNot { candidate -> candidate.nodeIndices.any { it in excluded } })
            .distinctBy { it.value to it.nodeIndices }
    }
    fun times(vararg labels: String): List<FieldCandidate> = if (hasLabel(*labels)) fields(*labels) else matching(FULL_TIME)
    fun state(default: PaymentState = PaymentState.UNKNOWN): PaymentState {
        val statuses = statusValues.map { statusValue(it.value) }
        fun negative(labels: Set<String>) = hasStatus(*labels.toTypedArray()) || statuses.any { value -> labels.any { label ->
            value.startsWith(label) && value.drop(label.length).firstOrNull()?.let { it.isWhitespace() || it in ",:;!.()" } == true
        } }
        return when {
            negative(FAILED) || statuses.any { value -> SUCCESS.any { status ->
                listOf("未", "尚未", "并未").any { statusAmount(value, it + status) != null }
            } } -> PaymentState.FAILED
            negative(REFUND) || statuses.any { REFUND_AMOUNT.matches(it) } -> PaymentState.REFUND_PENDING
            negative(PENDING) || statuses.any { it.startsWith("待") && it.endsWith("收款") } -> PaymentState.PENDING
            hasStatus(*SUCCESS.toTypedArray()) || statuses.any { it.startsWith("你已收款,") || it.endsWith("已收款") || it.startsWith("已存入零钱") } -> PaymentState.SUCCEEDED
            else -> default
        }
    }
    fun evidence(field: String, candidates: List<FieldCandidate>) = candidates.filter { it.value.isNotBlank() && it.value.length <= 256 }
        .map { candidate ->
            val hint = field.contains("accountHint", ignoreCase = true) || field in ORDER_ID_FIELDS
            val evidence = FieldEvidence(field, candidate.nodeIndices, if (hint) candidate.value else candidate.rawText,
                redacted = hint && candidate.value != candidate.rawText)
            if (field in ORDER_ID_FIELDS) evidence else EvidencePrivacy.redact(evidence)
        }

    companion object {
        private val STATUS_LABELS = listOf("当前状态", "交易状态", "支付状态", "订单状态")
        private val CURRENCY_SYMBOLS = setOf("¥", "$", "HK$", "€", "£", "CNY", "USD", "HKD", "EUR", "GBP", "人民币")
        private const val NUMBER_PATTERN = "(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?"
        private val NUMBER = Regex("[+-]?$NUMBER_PATTERN")
        private val STATUS_MONEY = Regex("(?:[+-]?\\s*(?:¥|HK\\$|\\$|€|£|CNY|USD|HKD|EUR|GBP)\\s*[+-]?$NUMBER_PATTERN|[+-]?${NUMBER_PATTERN}元|[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)\\.[0-9]{1,2})")
        private val NON_PAID_LABELS = arrayOf("账户余额", "可用余额", "余额", "订单金额", "订单总额", "原价", "商品原价", "商品总额", "优惠金额", "优惠", "红包", "优惠券", "手续费", "服务费")
        val DECIMAL = Regex("[0-9]+\\.[0-9]{1,2}")
        val MONEY = Regex("[+-]?\\s*(?:¥\\s*[+-]?\\s*)?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)\\.[0-9]{1,2}(?:元)?")
        val FULL_TIME = Regex("[0-9]{4}[-年/.][0-9]{1,2}[-月/.][0-9]{1,2}日? [0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?")
        private val SUCCESS = setOf("支付成功", "付款成功", "收款成功", "交易成功", "已收钱", "已收款", "充值成功", "充值完成", "提现成功", "已到账", "本人已领取",
            "转账成功", "转入成功", "转出成功", "成功转入", "成功转出", "收益到账", "代付成功", "免密支付成功", "自动扣款成功", "还款成功")
        private val FAILED = setOf("支付失败", "交易失败", "已取消", "订单已取消", "交易关闭", "待付款", "未付款", "转账失败", "提现失败")
        private val PENDING = setOf("等待收款", "等待对方领取", "处理中", "转账中", "待到账", "待领取", "待收款", "领取中")
        private val REFUND = setOf("已退款", "已全额退款", "部分退款", "退款中", "退款处理中", "退款申请已提交")
        private val REFUND_AMOUNT = Regex("(?:已退款|部分退款|已全额退款)\\(.*\\)")
    }
}

internal fun pageRule(id: String, packageName: String, priority: Int, matches: (PageFields) -> Boolean,
    extract: (PageFields) -> RecognitionResult): PageRule = object : PageRule {
    override val id = id
    override val packageName = packageName
    override val priority = priority
    override fun matches(snapshot: UiSnapshot): Boolean {
        val page = PageFields(snapshot)
        return snapshot.packageName == packageName && !page.allText.any { it.contains("支付密码") } && matches(page)
    }
    override fun extract(snapshot: UiSnapshot) = extract(PageFields(snapshot))
}

internal fun RecognitionResult.withEvidence(extra: List<FieldEvidence>): RecognitionResult = when (this) {
    is RecognitionResult.Matched -> copy(evidence = evidence + extra)
    is RecognitionResult.NeedsReview -> copy(evidence = evidence + extra)
    is RecognitionResult.Unsupported -> this
}
