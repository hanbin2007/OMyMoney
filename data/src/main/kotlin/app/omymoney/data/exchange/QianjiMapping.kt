package app.omymoney.data.exchange

import app.omymoney.core.model.BillKind
import app.omymoney.data.LedgerJson
import kotlinx.serialization.encodeToString

// Independently modeled from the APK's public export fields, not a verified third-party file.
internal object QianjiMapping {
    val columns = listOf("ID", "时间", "账本", "分类", "二级分类", "类型", "金额", "币种", "账户1", "账户2", "备注", "已报销", "手续费", "优惠券", "记账者", "账单标记", "标签", "账单图片", "关联账单")
    val mapping = ImportMapping(mapOf(ImportField.SOURCE_ID to 0, ImportField.TIME to 1, ImportField.CATEGORY to 3,
        ImportField.KIND to 5, ImportField.AMOUNT to 6, ImportField.CURRENCY to 7, ImportField.FROM to 8,
        ImportField.TO to 9, ImportField.NOTE to 10, ImportField.FEE to 12, ImportField.EXCLUDE_STATS to 15,
        ImportField.EXCLUDE_BUDGET to 15, ImportField.TAGS to 16, ImportField.LINK to 18))
    private val kinds = mapOf("支出" to BillKind.EXPENSE, "收入" to BillKind.INCOME, "转账" to BillKind.TRANSFER, "还款" to BillKind.CREDIT_PAYMENT)
    fun value(field: ImportField, raw: List<String>, mapping: ImportMapping): String {
        fun at(index: Int) = raw.getOrNull(index).orEmpty()
        fun mapped(key: ImportField) = mapping.columns[key]?.let(::at).orEmpty()
        val kind = kinds[mapped(ImportField.KIND)]
        return when (field) {
            ImportField.KIND -> kind?.name ?: mapped(field)
            ImportField.FROM -> if (kind == BillKind.INCOME) "" else mapped(field)
            ImportField.TO -> if (kind == BillKind.INCOME) mapped(ImportField.FROM) else mapped(field)
            ImportField.CATEGORY -> if (kind !in setOf(BillKind.EXPENSE, BillKind.INCOME)) "" else
                listOf(mapped(field), at(4)).filter { it.isNotEmpty() }.joinToString(" / ")
            ImportField.TAGS -> mapped(field).takeIf { it.isNotEmpty() }?.let { LedgerJson.format.encodeToString(it.split('#').filter(String::isNotBlank)) }.orEmpty()
            ImportField.EXCLUDE_STATS -> when (mapped(field)) { "nc", "nc&nb", "不计收支", "不计收支&预算" -> "true"; else -> "false" }
            ImportField.EXCLUDE_BUDGET -> when (mapped(field)) { "nb", "nc&nb", "不计预算", "不计收支&预算" -> "true"; else -> "false" }
            else -> mapped(field)
        }
    }
    fun issues(raw: List<String>): List<ImportIssue> = buildList {
        if (raw.getOrNull(13).orEmpty().let { it.isNotBlank() && it.toBigDecimalOrNull()?.signum() != 0 }) add(ImportIssue("fee", "unsupported_discount"))
        if (raw.getOrNull(11).orEmpty().isNotBlank()) add(ImportIssue("kind", "unsupported_reimbursement"))
        if (raw.getOrNull(17).orEmpty().isNotBlank()) add(ImportIssue("attachments", "unsupported_attachments"))
        if (raw.getOrNull(15).orEmpty() !in setOf("", "nc", "nb", "nc&nb", "不计收支", "不计预算", "不计收支&预算")) add(ImportIssue("flags", "invalid_flag"))
    }
}
