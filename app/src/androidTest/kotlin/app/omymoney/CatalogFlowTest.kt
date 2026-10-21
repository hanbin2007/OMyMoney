package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class CatalogFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val suffix = UUID.randomUUID().toString().take(6)

    @Test fun createTwoBooksAndAccountsThenSwitchAndRecreateWithoutLosingContext() {
        val trip = "旅行 A5 $suffix"
        val family = "家庭 A5 $suffix"
        val cash = "旅行现金 A5 $suffix"
        val transit = "交通卡 A5 $suffix"
        waitFor("book_switch")
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_books").performClick()
        createBook(trip)
        createBook(family)
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithTag("nav_bills").performClick()
        compose.onNodeWithTag("book_switch").performClick()
        compose.onNodeWithTag("selection_search").performTextInput(trip)
        compose.onNode(hasText(trip) and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)).performClick()
        waitFor("book_switch")
        compose.onNodeWithTag("book_switch").assertTextContains(trip)
        compose.onNodeWithTag("nav_assets").performClick()
        createAccount(cash, "100.00")
        createAccount(transit, "200.00")
        compose.onNodeWithContentDescription("更多操作：$transit").performClick()
        compose.onNodeWithText("上移").performClick()
        compose.waitUntil(10_000) {
            compose.onNodeWithText(transit).getBoundsInRoot().top < compose.onNodeWithText(cash).getBoundsInRoot().top
        }
        compose.onNodeWithTag("nav_bills").performClick()
        compose.activityRule.scenario.recreate()
        waitFor("book_switch")
        compose.onNodeWithTag("book_switch").assertTextContains(trip)
        compose.onNodeWithTag("nav_assets").performClick()
        compose.onNodeWithText(cash).assertIsDisplayed()
        compose.onNodeWithText(transit).assertIsDisplayed()
    }

    @Test fun validationAndCancelKeepInputUntilExplicitDiscard() {
        waitFor("book_switch")
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_books").performClick()
        compose.onNodeWithTag("add_book").assert(hasText("新建账本") or hasContentDescription("新建账本")).performClick()
        compose.onNodeWithTag("catalog_save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("请输入名称").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("catalog_name").performTextInput("未保存账本 A5")
        compose.activityRule.scenario.recreate()
        waitFor("catalog_name")
        compose.onNodeWithTag("catalog_name").assertTextContains("未保存账本 A5")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("catalog_name").assertTextContains("未保存账本 A5")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithText("放弃更改").performClick()
        waitFor("add_book")
        compose.onNodeWithText("未保存账本 A5").assertDoesNotExist()
    }

    @Test fun selectingTheCurrentKindDoesNotDetachAChildCategory() {
        val parent = "通勤组 A5 $suffix"
        val child = "地铁 A5 $suffix"
        waitFor("book_switch")
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_books").performClick()
        createBook("分类 A5 $suffix")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithTag("catalog_settings").performScrollToNode(hasTestTag("settings_categories"))
        compose.onNodeWithTag("settings_categories").performClick()
        compose.onNodeWithTag("add_category").assert(hasText("新建分类") or hasContentDescription("新建分类")).performClick()
        compose.onNodeWithTag("catalog_name").performTextInput(parent)
        compose.onNodeWithTag("catalog_save").performClick()
        waitFor("category_list")
        compose.onNodeWithTag("category_list").performScrollToNode(hasText(parent))
        compose.onNodeWithContentDescription("更多操作：$parent").performClick()
        compose.onNodeWithText("添加子分类").performClick()
        compose.onNodeWithTag("catalog_name").performTextInput(child)
        compose.onNodeWithText(parent).assertExists()
        compose.onNodeWithText("支出").performClick()
        compose.onNodeWithText(parent).assertExists()
        compose.onNodeWithTag("catalog_save").performClick()
        waitFor("category_list")
        compose.onNodeWithTag("category_list").performScrollToNode(hasText(child))
        compose.onNode(hasText(child) and hasText(parent)).assertIsDisplayed()
        compose.onNodeWithTag("category_list").performScrollToNode(hasContentDescription("更多操作：$parent"))
        compose.onNodeWithContentDescription("更多操作：$parent").performClick()
        compose.onNodeWithText("归档").performClick()
        compose.onNodeWithText("1 个子分类", substring = true).assertExists()
        compose.onNodeWithText("子分类仍保持归档", substring = true).assertExists()
        compose.waitForIdle()
        captureUiEvidence("a5-category-archive.png")
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun firstInvalidFieldIsVisibleAfterSubmittingFromTheBottom() {
        waitFor("book_switch")
        compose.onNodeWithTag("nav_assets").performClick()
        compose.onNodeWithTag("add_account").performClick()
        compose.onNodeWithTag("account_aliases").performScrollTo().performClick().performTextInput("Travel wallet")
        compose.onNodeWithTag("catalog_save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("请输入名称").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        captureUiEvidence("a5-name-error-bottom.png")
        compose.onNodeWithText("请输入名称").assertIsDisplayed()
        compose.onNodeWithTag("account_aliases").assertTextContains("Travel wallet")
    }

    @Test fun searchableBookSelectionHasVisibleMatchingOptions() {
        waitFor("book_switch")
        compose.onNodeWithTag("book_switch").performClick()
        compose.onNodeWithTag("selection_search").performTextInput("日")
        compose.onNodeWithTag("selection_search").assertTextContains("日")
        captureUiEvidence("a5-book-picker-search.png")
        compose.onAllNodesWithText("日常账本").onFirst().assertExists()
    }

    private fun createBook(name: String) {
        compose.onNodeWithTag("add_book").performClick()
        compose.onNodeWithTag("catalog_name").performTextInput(name)
        compose.onNodeWithTag("catalog_save").performClick()
        waitFor("add_book")
        compose.onNodeWithTag("book_list").performScrollToNode(hasText(name))
        compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).assertIsDisplayed()
    }

    private fun createAccount(name: String, balance: String) {
        compose.onNodeWithTag("add_account").assert(hasText("新建账户") or hasContentDescription("新建账户")).performClick()
        compose.onNodeWithTag("catalog_name").performTextInput(name)
        compose.onNodeWithTag("account_opening").performTextReplacement(balance)
        compose.onNodeWithTag("catalog_save").performClick()
        waitFor("add_account")
        val count = runBlocking { (compose.activity.application as OMyMoneyApplication).container.store.catalog.observeAccounts().first().size }
        compose.onNodeWithTag("account_list").performScrollToIndex(count)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).assertIsDisplayed()
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
