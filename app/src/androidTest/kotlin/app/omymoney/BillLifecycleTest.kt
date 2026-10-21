package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.core.model.*
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val book = Book(UUID.randomUUID().toString(), "账单流程 ${UUID.randomUUID().toString().take(6)}", zoneId = ZoneOffset.UTC)
    private val account = AssetAccount(UUID.randomUUID().toString(), "流程现金 ${UUID.randomUUID().toString().take(6)}",
        openingBalance = Money(10000, CurrencySpec.CNY), balance = Money(10000, CurrencySpec.CNY))
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store
    private lateinit var expenseId: String
    private lateinit var incomeId: String

    @Before fun setup() {
        runBlocking {
            store.catalog.ensureLocalDefaults()
            store.catalog.saveBook(book)
            store.catalog.saveAccount(account)
            expenseId = saved(store.catalog.initialDraft(book.id).copy(fromAccountId = account.id,
                amount = Money(1230, CurrencySpec.CNY), note = "午饭便当", occurredAt = Instant.now()))
            incomeId = saved(store.catalog.initialDraft(book.id).copy(kind = BillKind.INCOME, fromAccountId = null,
                toAccountId = account.id, categoryId = null, amount = Money(5000, CurrencySpec.CNY), note = "九月工资",
                occurredAt = Instant.parse("2026-09-01T12:00:00Z")))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun searchAndDateFiltersHaveCorrectResultsAndAnEmptyStateReset() {
        compose.onNodeWithTag("bill_search_action").performClick()
        compose.onNodeWithTag("bill_search").performTextInput("午饭")
        compose.onNodeWithTag("bill_row_$expenseId").assertIsDisplayed()
        compose.onNodeWithText("CNY -12.30").assertIsDisplayed()
        compose.onNodeWithTag("bill_row_$incomeId").assertDoesNotExist()
        compose.waitForIdle()
        captureUiEvidence("a7-search.png")
        compose.onNodeWithTag("bill_search").performTextReplacement("没有匹配的账单")
        compose.onNodeWithTag("clear_bill_filters").assertIsDisplayed()
        compose.waitForIdle()
        captureUiEvidence("a7-empty-filter.png")
        compose.onNodeWithTag("clear_bill_filters").performClick()
        waitFor("bill_list")
        compose.onNodeWithTag("bill_list").performScrollToNode(hasTestTag("bill_row_$incomeId"))
        compose.onNodeWithTag("bill_row_$incomeId").assertIsDisplayed()
        compose.onNodeWithTag("bill_filter_action").performClick()
        compose.onNodeWithText("本月").performClick()
        compose.onNodeWithTag("apply_bill_filters").performClick()
        compose.onNodeWithTag("bill_row_$expenseId").assertExists()
        compose.onNodeWithTag("bill_row_$incomeId").assertDoesNotExist()
        compose.waitForIdle()
        captureUiEvidence("a7-date-filter.png")
        compose.activityRule.scenario.recreate()
        waitFor("bill_row_$expenseId")
        compose.onNodeWithTag("bill_row_$incomeId").assertDoesNotExist()
    }

    @Test fun editingDeletingAndUndoingUseOneBillAndRestoreTheExactBalance() {
        waitFor("bill_row_$expenseId")
        compose.onNodeWithTag("bill_row_$expenseId").performClick()
        compose.onNodeWithTag("bill_edit").performClick()
        waitFor("bill_amount")
        compose.onNodeWithTag("bill_amount").performTextReplacement("15.60")
        compose.onNodeWithTag("bill_save").performClick()
        waitFor("bill_detail")
        compose.onNodeWithText("CNY -15.60").assertExists()
        assertEquals(13440L, balance())
        compose.onNodeWithTag("bill_delete").performClick()
        waitFor("add_bill")
        compose.onNodeWithTag("bill_row_$expenseId").assertDoesNotExist()
        assertEquals(15000L, balance())
        compose.waitUntil(2000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("撤销").performClick()
        waitFor("bill_row_$expenseId")
        assertEquals(13440L, balance())
        val bills = runBlocking { store.ledger.observeBills(BillQuery(bookId = book.id)).first() }
        assertEquals(2, bills.size)
        assertEquals(1560L, bills.single { it.id == expenseId }.amount.minor)
    }

    @Test fun longMerchantAndLargeAmountDoNotOverlapOrHideDetailActions() {
        val title = "很长的商户名称与需要完整保留的记账备注".repeat(12)
        val id = runBlocking { saved(store.catalog.initialDraft(book.id).copy(fromAccountId = account.id,
            amount = Money(9000000000000000000L, CurrencySpec.CNY), note = title)) }
        waitFor("bill_row_$id")
        compose.onNodeWithTag("bill_row_$id").performClick()
        waitFor("bill_detail")
        compose.onNodeWithTag("bill_edit").assertIsDisplayed()
        compose.onNodeWithTag("bill_delete").assertIsDisplayed()
        compose.onNodeWithText("CNY -90000000000000000.00").assertIsDisplayed()
        compose.waitForIdle()
        captureUiEvidence("a7-large-bill-detail.png")
        compose.onNodeWithTag("detail_note").performScrollTo().assertTextContains(title)
    }

    @Test fun listPositionSurvivesDetailAndBookRoundTrips() {
        val ids = runBlocking { (1..28).map { index -> saved(store.catalog.initialDraft(book.id).copy(fromAccountId = account.id,
            amount = Money(1, CurrencySpec.CNY), note = "位置账单 $index", occurredAt = Instant.now().plusSeconds(index.toLong()))) } }
        waitFor("bill_list")
        val target = "bill_row_${ids[10]}"
        compose.onNodeWithTag("bill_list").performScrollToNode(hasTestTag(target))
        val before = compose.onNodeWithTag(target).getUnclippedBoundsInRoot().top.value
        compose.onNodeWithTag(target).performClick()
        compose.onNodeWithTag("screen_back").performClick()
        waitFor(target)
        assertEquals(before, compose.onNodeWithTag(target).getUnclippedBoundsInRoot().top.value, 1f)
        val other = Book(UUID.randomUUID().toString(), "另一本 ${UUID.randomUUID().toString().take(6)}")
        runBlocking { store.catalog.saveBook(other) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(other.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book_switch").performClick()
        compose.onNodeWithTag("selection_search").performTextInput(book.name)
        compose.onNode(hasText(book.name) and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)).performClick()
        waitFor(target)
        assertEquals(before, compose.onNodeWithTag(target).getUnclippedBoundsInRoot().top.value, 1f)
    }

    @Test fun invalidCustomDateFilterReturnsFocusToTheVisibleError() {
        compose.onNodeWithTag("bill_filter_action").performClick()
        compose.onNodeWithTag("filter_from").performTextInput("2026-13-11")
        compose.onNode(hasText("标签") and hasSetTextAction()).performScrollTo().performTextInput("交通")
        compose.onNodeWithTag("apply_bill_filters").performClick()
        compose.onNodeWithTag("filter_from").assertIsFocused()
        compose.onNodeWithText("日期格式为 YYYY-MM-DD，结束日期不能早于开始日期。").assertIsDisplayed()
        compose.waitForIdle()
        captureUiEvidence("a7-filter-error.png")
    }

    @Test fun pendingUndoSurvivesActivityRecreation() {
        waitFor("bill_row_$expenseId")
        compose.onNodeWithTag("bill_row_$expenseId").performClick()
        compose.onNodeWithTag("bill_delete").performClick()
        compose.waitUntil(2000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(3000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("撤销").performClick()
        waitFor("bill_row_$expenseId")
        assertEquals(13770L, balance())
    }

    @Test fun selectingCurrentFilterKindKeepsTheCategory() {
        compose.onNodeWithTag("bill_filter_action").performClick()
        compose.onNodeWithText("支出").performScrollTo().performClick()
        compose.onNodeWithTag("filter_category").performScrollTo().performClick()
        compose.onNode(hasText("餐饮") and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)).performClick()
        compose.onNodeWithText("支出").performScrollTo().performClick()
        compose.onNodeWithTag("filter_category").assert(hasAnyDescendant(hasText("餐饮", substring = true)))
    }

    @Test fun sameNamedCategoryOptionsHaveKindAndParentContext() {
        val categories = runBlocking { store.catalog.observeCategories(book.id).first() }
        val parents = categories.filter { it.kind == BillKind.EXPENSE && it.parentId == null }.take(2)
        val children = parents.map { Category(UUID.randomUUID().toString(), book.id, "同名子类", BillKind.EXPENSE, it.id) }
        runBlocking { children.forEach { store.catalog.saveCategory(it) } }
        compose.onNodeWithTag("bill_filter_action").performClick()
        compose.onNodeWithTag("filter_category").performScrollTo().performClick()
        categories.filter { it.name == "其他" }.forEach { category ->
            compose.onNodeWithTag("selection_search").performTextReplacement("其他")
            compose.onNodeWithTag("option_${category.id}").assertTextContains(if (category.kind == BillKind.EXPENSE) "支出" else "收入", substring = true)
        }
        compose.onNodeWithTag("selection_search").performTextReplacement("同名子类")
        children.forEachIndexed { index, child ->
            compose.onNodeWithTag("option_${child.id}").assertTextContains(parents[index].name, substring = true)
        }
        compose.onNodeWithTag("option_${children.first().id}").performClick()
        compose.onNodeWithTag("apply_bill_filters").performClick()
        compose.onNodeWithTag("bill_filter_summary").assertTextContains(parents.first().name, substring = true)
    }

    private suspend fun saved(draft: TransactionDraft): String {
        val result = store.ledger.save(draft, null)
        assertTrue("$result", result is SaveResult.Saved)
        return (result as SaveResult.Saved).billId
    }
    private fun balance() = runBlocking { store.catalog.observeAccounts().first().single { it.id == account.id }.balance.minor }
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
