package app.omymoney.core.recognition

import app.omymoney.core.recognition.rules.PageFields
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PageFieldsRegressionTest {
    @Test fun fragmentedLabelsAndCurrencyPreserveOriginalEvidenceIndices() {
        for (parts in listOf(listOf("实", "付", "金", "额", ":", "￥", "12.30"), listOf("实付", "金额", ":", "￥", "12.30"))) {
            val page = fields(parts.map { UiNode(it) })
            assertTrue(page.hasLabel("实付金额"))
            val candidate = page.fields("实付金额").single()
            assertEquals("¥12.30", candidate.value)
            assertEquals(parts.indices.toList(), candidate.nodeIndices)
        }
    }

    @Test fun fragmentedLabelsNeverCrossParentsOrSkipInterveningText() {
        val detached = fields(listOf(UiNode(), UiNode("实付", parentIndex = 0), UiNode(), UiNode("金额", parentIndex = 2), UiNode("￥12.30", parentIndex = 2)))
        assertFalse(detached.hasLabel("实付金额"))
        assertTrue(detached.fields("实付金额").isEmpty())
        assertTrue(fields(listOf(UiNode("实付"), UiNode("无关内容"), UiNode("金额"), UiNode("￥12.30"))).fields("实付金额").isEmpty())
    }

    @Test fun oneValueInsideABlankSiblingContainerCanBeExtractedWithoutCrossingFields() {
        val page = fields(listOf(UiNode(), UiNode("付款方式", parentIndex = 0), UiNode(parentIndex = 0), UiNode("账户余额", parentIndex = 2)))
        val candidate = page.fields("付款方式").single()
        assertEquals("账户余额", candidate.value)
        assertTrue(candidate.nodeIndices.containsAll(listOf(1, 3)))
        val ambiguous = fields(listOf(UiNode(), UiNode("付款方式", parentIndex = 0), UiNode(parentIndex = 0), UiNode("账户余额", parentIndex = 2), UiNode("其他银行卡", parentIndex = 2)))
        assertEquals("", ambiguous.fields("付款方式").single().value)
    }

    @Test fun descriptiveAliasesAreNotDiscardedOrTurnedIntoExtraNodes() {
        val page = fields(listOf(UiNode("结果", "当前状态：支付失败"), UiNode("支付成功")))
        assertEquals(PaymentState.FAILED, page.state())
        val amount = fields(listOf(UiNode("金额", "实付金额：￥12.30"))).fields("实付金额").single()
        assertEquals("￥12.30", amount.rawText.substringAfter("："))
        assertEquals(listOf(0), amount.nodeIndices)
    }

    @Test fun oversizedValueContainersFailClosedInsteadOfReadingOnlyTheirFirstValue() {
        val nodes = mutableListOf(UiNode(), UiNode("实付金额", parentIndex = 0), UiNode(parentIndex = 0), UiNode("￥12.30", parentIndex = 2))
        repeat(70) { nodes += UiNode(parentIndex = 2) }
        nodes += UiNode("￥14.20", parentIndex = 2)
        assertEquals("", fields(nodes).fields("实付金额").single().value)
    }

    @Test fun textBearingAncestorCannotHideAConflictingDescendant() {
        val nodes = listOf(UiNode(), UiNode("实付金额", parentIndex = 0), UiNode(parentIndex = 0),
            UiNode("￥12.30", parentIndex = 2), UiNode("￥14.20", parentIndex = 3))
        assertEquals("", fields(nodes).fields("实付金额").single().value)
        val duplicate = nodes.map { it.copy(text = it.text?.replace("14.20", "12.30")) }
        assertEquals("¥12.30", fields(duplicate).fields("实付金额").single().value)
    }

    private fun fields(nodes: List<UiNode>) = PageFields(UiSnapshot("test.app", 1, Instant.EPOCH, nodes))
}
