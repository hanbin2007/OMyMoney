package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.core.budget.*
import app.omymoney.core.model.*
import app.omymoney.data.CatalogResult
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BudgetFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store
    private val today get() = LocalDate.now(java.time.ZoneOffset.UTC)
    private val period get() = BudgetPeriod(today.year, today.monthValue)

    private fun fixture(): Pair<Book, Category> {
        val book = Book(UUID.randomUUID().toString(), "Budget ${UUID.randomUUID().toString().take(6)}", zoneId = java.time.ZoneOffset.UTC)
        val category = Category(UUID.randomUUID().toString(), book.id, "Meals", BillKind.EXPENSE)
        runBlocking {
            assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
            assertTrue(store.catalog.saveCategory(category) is CatalogResult.Saved)
            store.catalog.selectBook(book.id)
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
        return book to category
    }
    private fun openBudget() {
        compose.onNodeWithTag("nav_stats").performClick()
        compose.onNodeWithTag("open_budget").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("budget_edit").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitPlan(book: Book, amount: Long, target: BudgetPeriod = period) {
        compose.waitUntil(5000) { runBlocking { store.budgets.observe(book.id, target).first()?.total?.minor == amount } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("budget_edit").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) { compose.runOnUiThread {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
        } }
    }
    private fun captureBudget(name: String) {
        // Snackbar timeout uses the Compose test clock, not this polling loop's wall clock.
        compose.mainClock.advanceTimeBy(16_000)
        compose.waitUntil(15000) { compose.onAllNodesWithText("预算已保存").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        captureUiEvidence(name)
    }

    @Test fun createsEditsBudgetAndHomeUsesTheSameRemainingIncludingExclusions() {
        val (book, category) = fixture()
        runBlocking {
            val account = AssetAccount(UUID.randomUUID().toString(), "Budget cash ${book.id.take(6)}")
            assertTrue(store.catalog.saveAccount(account) is CatalogResult.Saved)
            listOf(12000L to false, 5000L to true).forEach { (amount, excluded) ->
                assertTrue(store.ledger.save(TransactionDraft(book.id, kind = BillKind.EXPENSE,
                    amount = Money(amount, book.currency), fromAccountId = account.id, categoryId = category.id,
                    occurredAt = Instant.now(), timeSource = TimeSource.USER, excludeBudget = excluded), null) is SaveResult.Saved)
            }
        }
        openBudget()
        compose.onNodeWithTag("budget_edit").performClick()
        compose.onNodeWithTag("budget_total").performTextReplacement("1000")
        compose.onNodeWithTag("budget_category_add").performScrollTo().performClick()
        compose.onNodeWithTag("selection_search").performTextInput(category.name)
        compose.onNodeWithTag("option_${category.id}").performScrollTo().performClick()
        compose.onNodeWithTag("budget_limit_${category.id}").performScrollTo().performTextReplacement("600")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("budget_total").performScrollTo().assertTextContains("1000")
        compose.onNodeWithTag("budget_save").performClick()
        waitPlan(book, 100000)
        compose.onNodeWithTag("budget_remaining").assertTextEquals("CNY 880.00")
        captureBudget("c4-summary.png")
        compose.onNodeWithTag("budget_list").performScrollToNode(hasTestTag("budget_category_${category.id}"))
        compose.onNodeWithTag("budget_category_${category.id}").assertTextContains("CNY 480.00", substring = true)
        captureBudget("c4-budget.png")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("nav_bills").performClick()
        compose.onNodeWithTag("bill_list").performScrollToIndex(0)
        compose.onNodeWithTag("home_budget_remaining").assertTextEquals("CNY 880.00")
        captureBudget("c4-home.png")
        compose.onNodeWithTag("home_budget_open").performClick()
        compose.onNodeWithTag("budget_edit").performClick()
        compose.onNodeWithTag("budget_total").performTextReplacement("1500")
        compose.onNodeWithTag("budget_save").performClick()
        waitPlan(book, 150000)
        compose.onNodeWithTag("budget_remaining").assertTextEquals("CNY 1380.00")
    }

    @Test fun invalidCategoryLimitsKeepInputsAndDiscardNeedsConfirmation() {
        val (book, category) = fixture()
        openBudget()
        compose.onNodeWithTag("budget_edit").performClick()
        compose.onNodeWithTag("budget_total").performTextReplacement("100")
        compose.onNodeWithTag("budget_category_add").performScrollTo().performClick()
        compose.onNodeWithTag("selection_search").performTextInput(category.name)
        compose.onNodeWithTag("option_${category.id}").performScrollTo().performClick()
        compose.onNodeWithTag("budget_limit_${category.id}").performScrollTo().performTextReplacement("101")
        compose.onNodeWithTag("budget_save").performClick()
        compose.onNodeWithTag("budget_total_error", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("budget_total").assertTextContains("100")
        assertNull(runBlocking { store.budgets.observe(book.id, period).first() })
        captureBudget("c4-error.png")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("budget_limit_${category.id}").performScrollTo().assertTextContains("101")
        compose.onNodeWithTag("budget_limit_${category.id}").performTextReplacement("80")
        compose.onNodeWithTag("budget_save").performClick()
        waitPlan(book, 10000)
    }

    @Test fun copiedBudgetDeletionSurvivesReopenAndExplicitCopyAndAnnualEditingWork() {
        val (book, _) = fixture()
        runBlocking {
            assertTrue(store.budgets.save(BudgetPlan(book.id, period.previous(), Money(100000, book.currency))) is CatalogResult.Saved)
        }
        openBudget()
        waitPlan(book, 100000)
        compose.onNodeWithTag("budget_delete").performClick()
        compose.onNodeWithTag("budget_delete_confirm").performClick()
        compose.waitUntil(5000) { runBlocking { store.budgets.observe(book.id, period).first() == null } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("budget_empty").assertIsDisplayed()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("budget_copy").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithTag("budget_copy").performClick()
        withFailureEvidence("failure-budget-copy") { compose.onNodeWithTag("budget_copy_confirm").performClick() }
        waitPlan(book, 100000)
        compose.onNodeWithTag("budget_previous").performClick()
        compose.onNodeWithTag("budget_period").assertTextContains(period.previous().key, substring = true)
        compose.onNodeWithTag("budget_next").performClick()
        compose.onNodeWithTag("budget_annual").performClick()
        compose.onNodeWithTag("budget_edit").performClick()
        compose.onNodeWithTag("budget_total").performTextReplacement("12000")
        compose.onNodeWithTag("budget_save").performClick()
        waitPlan(book, 1200000, BudgetPeriod(today.year))
        compose.onNodeWithTag("budget_remaining").assertTextEquals("CNY 12000.00")
        captureBudget("c4-annual.png")
    }

    @Test fun failedWriteKeepsTheUnsavedAmountAndCanBeRetried() {
        val (book, _) = fixture()
        openBudget()
        compose.onNodeWithTag("budget_edit").performClick()
        compose.onNodeWithTag("budget_total").performTextReplacement("765.43")
        val connection = android.database.sqlite.SQLiteDatabase.openDatabase(
            compose.activity.getDatabasePath("omymoney.db").absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        try {
            connection.execSQL("CREATE TRIGGER qa_fail_budget BEFORE INSERT ON budgets BEGIN SELECT RAISE(ABORT, 'qa'); END")
            compose.onNodeWithTag("budget_save").performClick()
            val message = compose.activity.getString(app.omymoney.android.R.string.error_save)
            compose.waitUntil(5000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithTag("budget_total").performScrollTo().assertTextContains("765.43")
            assertNull(runBlocking { store.budgets.observe(book.id, period).first() })
        } finally {
            connection.execSQL("DROP TRIGGER IF EXISTS qa_fail_budget")
            connection.close()
        }
        compose.onNodeWithTag("budget_save").performClick()
        waitPlan(book, 76543)
        compose.onNodeWithTag("budget_remaining").assertTextEquals("CNY 765.43")
    }

    @Test fun categoryAmountFieldsAndRemoveControlsIdentifyTheirFullCategoryContext() {
        val (book, meals) = fixture()
        val travel = Category(UUID.randomUUID().toString(), book.id, "Travel", BillKind.EXPENSE)
        val breakfast = Category(UUID.randomUUID().toString(), book.id, "Breakfast", BillKind.EXPENSE, meals.id)
        val travelBreakfast = breakfast.copy(id = UUID.randomUUID().toString(), parentId = travel.id)
        runBlocking {
            listOf(travel, breakfast, travelBreakfast).forEach { assertTrue(store.catalog.saveCategory(it) is CatalogResult.Saved) }
            assertTrue(store.budgets.save(BudgetPlan(book.id, period, Money(100000, book.currency), listOf(
                BudgetLimit(breakfast.id, Money(10000, book.currency)), BudgetLimit(travelBreakfast.id, Money(20000, book.currency))))) is CatalogResult.Saved)
        }
        openBudget()
        compose.onNodeWithTag("budget_edit").performClick()
        compose.onNodeWithTag("budget_limit_${breakfast.id}").performScrollTo()
            .assertContentDescriptionEquals("Meals / Breakfast · 分类额度").assertTextContains("100.00")
        compose.onNodeWithTag("budget_remove_${breakfast.id}").performScrollTo()
            .assertContentDescriptionEquals("移除Meals / Breakfast预算")
        compose.onNodeWithTag("budget_limit_${travelBreakfast.id}").performScrollTo()
            .assertContentDescriptionEquals("Travel / Breakfast · 分类额度").assertTextContains("200.00")
        compose.onNodeWithTag("budget_remove_${travelBreakfast.id}").performScrollTo()
            .assertContentDescriptionEquals("移除Travel / Breakfast预算")
    }
}
