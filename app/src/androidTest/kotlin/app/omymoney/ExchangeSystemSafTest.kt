package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import app.omymoney.core.model.*
import app.omymoney.data.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ExchangeSystemSafTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else
        listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun native(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            nodes(automation.rootInActiveWindow).firstOrNull {
                it.isVisibleToUser && it.packageName?.toString()?.endsWith(".documentsui") == true && predicate(it)
            }?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected native document control was not visible")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("Native document action must be clickable", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun openExchange(book: Book) {
        runBlocking { store.catalog.selectBook(book.id) }
        compose.waitUntil(5000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bill_more").performClick()
        compose.onNodeWithTag("bill_export").performClick()
    }
    private fun format(value: String) {
        compose.onNodeWithTag("exchange_format").performScrollTo().performClick()
        compose.onNodeWithTag("option_$value").performClick()
    }
    @Test fun actualDocumentsUiExportsAndReimportsEveryFormat() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("c9SystemSaf") == "true")
        val suffix = UUID.randomUUID().toString().take(8)
        val source = Book(UUID.randomUUID().toString(), "SAF $suffix")
        val cash = AssetAccount(UUID.randomUUID().toString(), "SAF cash $suffix")
        val category = Category(UUID.randomUUID().toString(), source.id, "Food", BillKind.EXPENSE)
        runBlocking {
            store.catalog.saveBook(source); store.catalog.saveAccount(cash); store.catalog.saveCategory(category)
            listOf("Selected", "Other").forEach { note ->
                assertTrue(store.ledger.save(TransactionDraft(source.id, kind = BillKind.EXPENSE, amount = Money(1230, CurrencySpec.CNY),
                    fromAccountId = cash.id, categoryId = category.id, occurredAt = Instant.parse("2026-10-11T12:00:00Z"),
                    timeSource = TimeSource.USER, note = note), null) is SaveResult.Saved)
            }
        }
        for (value in listOf("CSV", "XLSX", "JSON")) {
            openExchange(source); format(value)
            compose.onNodeWithTag("exchange_export").performScrollTo().performClick()
            val name = "OMyMoney-QA-$suffix.${value.lowercase()}"
            val input = native { it.className?.toString() == "android.widget.EditText" }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
            }))
            val save = native { it.text?.toString()?.equals("save", ignoreCase = true) == true }
            captureUiEvidence("c9-system-$value-save.png")
            click(save)
            compose.waitUntil(5000) { compose.onAllNodesWithText("已导出 2 笔账单。").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("screen_back").performClick()
            val target = Book(UUID.randomUUID().toString(), "Readback $value $suffix")
            val targetCategory = category.copy(id = UUID.randomUUID().toString(), bookId = target.id)
            runBlocking { store.catalog.saveBook(target); store.catalog.saveCategory(targetCategory) }
            openExchange(target); format(value)
            compose.onNodeWithTag("exchange_import").performScrollTo().performClick()
            val file = native { it.text?.toString() == name }
            click(file)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("exchange_preview_summary").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("exchange_preview_summary").assertTextContains("可导入 2", substring = true)
            compose.onNodeWithTag("exchange_select_all").performScrollTo().performClick()
            compose.onNodeWithTag("exchange_commit").performClick()
            compose.waitUntil(5000) { runBlocking { store.ledger.observeBills(BillQuery(bookId = target.id)).first().size } == 2 }
            val actual = runBlocking { store.ledger.observeBills(BillQuery(bookId = target.id)).first() }
            assertEquals(setOf("Selected", "Other"), actual.map { it.note }.toSet())
            assertTrue(actual.all { it.amount == Money(1230, CurrencySpec.CNY) && it.fromAccountId == cash.id && it.categoryId == targetCategory.id })
            compose.waitForIdle(); captureUiEvidence("c9-system-$value-imported.png")
            compose.onNodeWithTag("screen_back").performClick()
            compose.onNodeWithTag("exchange_discard").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("exchange_export").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("screen_back").performClick()
        }
    }
}
