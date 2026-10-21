package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.core.model.*
import app.omymoney.data.CatalogResult
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StatisticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store
    private val today get() = LocalDate.now(ZoneOffset.UTC)
    private data class Fixture(val book: Book, val cash: AssetAccount, val bank: AssetAccount, val meals: Category)
    private fun fixture(): Fixture {
        val book = Book(UUID.randomUUID().toString(), "Stats ${UUID.randomUUID().toString().take(6)}", zoneId = ZoneOffset.UTC)
        val cash = AssetAccount(UUID.randomUUID().toString(), "Cash ${book.id.take(6)}")
        val bank = AssetAccount(UUID.randomUUID().toString(), "Bank ${book.id.take(6)}")
        val meals = Category(UUID.randomUUID().toString(), book.id, "Meals", BillKind.EXPENSE)
        runBlocking {
            assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
            listOf(cash, bank).forEach { assertTrue(store.catalog.saveAccount(it) is CatalogResult.Saved) }
            assertTrue(store.catalog.saveCategory(meals) is CatalogResult.Saved)
            store.catalog.selectBook(book.id)
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
        return Fixture(book, cash, bank, meals)
    }
    private fun save(f: Fixture, minor: Long, kind: BillKind = BillKind.EXPENSE, day: LocalDate = today,
        account: AssetAccount = f.cash, excluded: Boolean = false, linked: String? = null, note: String = "", fee: Long? = null): String = runBlocking {
        val result = store.ledger.save(TransactionDraft(f.book.id, kind = kind, amount = Money(minor, account.currency),
            fromAccountId = if (kind in setOf(BillKind.INCOME, BillKind.REFUND)) null else account.id,
            toAccountId = when (kind) { BillKind.INCOME, BillKind.REFUND -> account.id; BillKind.TRANSFER -> f.bank.id; else -> null },
            categoryId = f.meals.id.takeIf { kind == BillKind.EXPENSE }, occurredAt = day.atTime(12, 0).toInstant(ZoneOffset.UTC),
            timeSource = TimeSource.USER, zoneId = ZoneOffset.UTC, excludeStats = excluded, excludeBudget = true,
            linkedBillId = linked, note = note, fee = fee?.let { Money(it, account.currency) }), null)
        assertTrue(result.toString(), result is SaveResult.Saved)
        (result as SaveResult.Saved).billId
    }
    private fun open() {
        compose.onNodeWithTag("nav_stats").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("stats_list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) {
            runCatching { scroll("stats_total_EXPENSE") }.isSuccess
        }
    }
    private fun tab(index: Int) { compose.onNodeWithTag("stats_tab_$index").performClick() }
    private fun scroll(tag: String) { compose.onNodeWithTag("stats_list").performScrollToNode(hasTestTag(tag)) }
    private fun capture(name: String) { compose.waitForIdle(); captureUiEvidence(name) }

    @Test fun trendPlotFitsInsideTheVisibleViewport() {
        val f = fixture()
        save(f, 12000); save(f, 50000, BillKind.INCOME)
        open()
        val plot = hasContentDescription("CNY趋势", substring = true)
        compose.onNodeWithTag("stats_list").performScrollToNode(plot)
        val bounds = compose.onNode(plot).getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("stats_list").getUnclippedBoundsInRoot()
        assertTrue("The complete plot must fit the visible statistics viewport: $bounds / $viewport",
            bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        capture("c5-plot.png")
    }

    @Test fun incomeFilteredCalendarLabelsItsIncomeAmount() = filteredCalendar(BillKind.INCOME, "收入", "CNY 500.00")

    @Test fun transferFilteredCalendarLabelsItsTransferAmount() = filteredCalendar(BillKind.TRANSFER, "转账及还款", "CNY 200.00")

    private fun filteredCalendar(kind: BillKind, label: String, amount: String) {
        val configuration = compose.activity.resources.configuration
        org.junit.Assume.assumeTrue(configuration.screenWidthDp < 368 || configuration.fontScale >= 1.5f || configuration.screenHeightDp < 480)
        val f = fixture()
        save(f, if (kind == BillKind.INCOME) 50000 else 20000, kind)
        open()
        compose.onNodeWithTag("stats_filter").performClick()
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("stats_filter_apply").performScrollTo().performClick()
        tab(2)
        scroll("stats_day_$today")
        compose.onNodeWithTag("stats_day_$today").assertTextContains(label, substring = true).assertTextContains(amount, substring = true)
        capture("c5-calendar-${kind.name.lowercase()}.png")
    }

    @Test fun detailBackAndRecreationPreserveStatisticsScroll() {
        val f = fixture()
        repeat(24) { save(f, 100 + it.toLong(), note = "Entry $it") }
        open(); tab(3)
        compose.onNodeWithTag("stats_list").performScrollToIndex(15)
        fun position() = compose.onNodeWithTag("stats_list").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        val previous = position()
        val row = compose.onAllNodes(hasTestTagPrefix("stats_entry_")).fetchSemanticsNodes().first { it.boundsInRoot.height > 0 }
        val tag = row.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("stats_list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals("Detail back must not reset statistics scroll", previous, position(), 0.01f)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("stats_list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals("Recreation must not reset statistics scroll", previous, position(), 0.01f)
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }

    @Test fun summaryCategoryTrendCalendarAnnualAndContributionsShareExactAmounts() {
        val f = fixture()
        val source = save(f, 12000, note = "Lunch")
        save(f, 5000, excluded = true); save(f, 50000, BillKind.INCOME)
        save(f, 20000, BillKind.TRANSFER, fee = 200)
        val refund = save(f, 2000, BillKind.REFUND, linked = source, note = "Refund")
        open()
        compose.onNodeWithTag("stats_total_EXPENSE").assertTextEquals("CNY 102.00")
        scroll("stats_total_INCOME"); compose.onNodeWithTag("stats_total_INCOME").assertTextEquals("CNY 500.00")
        scroll("stats_total_TRANSFER"); compose.onNodeWithTag("stats_total_TRANSFER").assertTextEquals("CNY 200.00")
        scroll("stats_chart"); compose.onNodeWithTag("stats_chart").assertExists()
        capture("c5-trend.png")
        tab(1)
        scroll("stats_category_EXPENSE_${f.meals.id}")
        compose.onNodeWithTag("stats_category_EXPENSE_${f.meals.id}").assertTextContains("CNY 100.00", substring = true)
        capture("c5-category.png")
        tab(2)
        scroll("stats_day_$today"); compose.onNodeWithTag("stats_day_$today").performClick()
        scroll("stats_day_EXPENSE"); compose.onNodeWithTag("stats_day_EXPENSE").assertTextEquals("CNY 102.00")
        scroll("stats_entry_$refund/EXPENSE/false")
        compose.onNodeWithTag("stats_entry_$refund/EXPENSE/false").assertTextContains("CNY -20.00", substring = true)
        capture("c5-day.png")
        compose.onNodeWithTag("stats_entry_$refund/EXPENSE/false").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        tab(0)
        scroll("stats_annual"); compose.onNodeWithTag("stats_annual").performClick()
        scroll("stats_month_${YearMonth.from(today)}")
        compose.onNodeWithTag("stats_month_${YearMonth.from(today)}").assertTextContains("CNY 102.00", substring = true)
        capture("c5-annual.png")
    }

    @Test fun accountAndNoteFiltersPersistAcrossRotationTabsAndBookSwitch() {
        val f = fixture()
        save(f, 1000, note = "coffee"); save(f, 2000, account = f.bank, note = "coffee"); save(f, 4000, account = f.bank, note = "dinner")
        open()
        compose.onNodeWithTag("stats_filter").performClick()
        compose.onNodeWithTag("stats_filter_account").performScrollTo().performClick()
        compose.onNodeWithTag("selection_search").performTextInput(f.bank.name)
        compose.waitForIdle()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("option_${f.bank.id}").performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasText(f.bank.name) and hasAnyAncestor(hasTestTag("stats_filter_account"))).assertExists()
        compose.onNodeWithTag("stats_filter_search").performScrollTo().performTextInput("coffee")
        compose.waitForIdle()
        compose.onNodeWithTag("stats_filter_search").assertIsDisplayed().assertTextContains("coffee")
        capture("c5-filter.png")
        compose.onNodeWithTag("stats_filter_apply").performScrollTo().assertIsDisplayed().performClick()
        compose.activityRule.scenario.recreate()
        scroll("stats_total_EXPENSE"); compose.onNodeWithTag("stats_total_EXPENSE").assertTextEquals("CNY 20.00")
        tab(1); scroll("stats_category_EXPENSE_${f.meals.id}")
        compose.onNodeWithTag("stats_category_EXPENSE_${f.meals.id}").assertTextContains("CNY 20.00", substring = true)
        tab(0)
        val other = fixture()
        save(other, 9900)
        compose.waitUntil(5000) { runCatching {
            scroll("stats_total_EXPENSE")
            compose.onNodeWithTag("stats_total_EXPENSE").assertTextEquals("CNY 99.00")
        }.isSuccess }
        compose.onNodeWithTag("stats_total_EXPENSE").assertTextEquals("CNY 99.00")
        runBlocking { store.catalog.selectBook(f.book.id) }
        compose.waitUntil(5000) { compose.onAllNodesWithText(f.book.name).fetchSemanticsNodes().isNotEmpty() }
        scroll("stats_total_EXPENSE"); compose.onNodeWithTag("stats_total_EXPENSE").assertTextEquals("CNY 20.00")
        compose.onNodeWithTag("stats_clear").performClick()
        scroll("stats_total_EXPENSE"); compose.onNodeWithTag("stats_total_EXPENSE").assertTextEquals("CNY 70.00")
    }

    @Test fun missingFxNeverShowsZeroAndEmptyPeriodRemainsNavigable() {
        val f = fixture()
        val usd = f.cash.copy(id = UUID.randomUUID().toString(), name = "USD ${f.book.id.take(6)}", currency = CurrencySpec("USD", 2),
            openingBalance = Money(0, CurrencySpec("USD", 2)), balance = Money(0, CurrencySpec("USD", 2)))
        runBlocking { assertTrue(store.catalog.saveAccount(usd) is CatalogResult.Saved) }
        val previousRate = runBlocking { store.catalog.observeExchangeRates().first().singleOrNull { it.source == usd.currency && it.target == f.book.currency } }
        try {
            runBlocking { assertTrue(store.catalog.removeExchangeRate(usd.currency, f.book.currency) is CatalogResult.Saved) }
            save(f, 1000, account = usd)
            open()
            compose.onNodeWithTag("stats_total_EXPENSE").assertTextContains("USD", substring = true)
            capture("c5-missing.png")
            tab(1)
            compose.onNodeWithTag("stats_list").performScrollToIndex(1)
            compose.onNodeWithText("范围：CNY -0.01 至 CNY 0.01").assertDoesNotExist()
            tab(0)
            scroll("stats_previous"); compose.onNodeWithTag("stats_previous").performClick()
            scroll("stats_total_EXPENSE"); compose.onNodeWithTag("stats_total_EXPENSE").assertTextEquals("CNY 0.00")
            scroll("stats_empty"); compose.onNodeWithTag("stats_empty").assertExists()
            capture("c5-empty.png")
        } finally {
            previousRate?.let { runBlocking { assertTrue(store.catalog.saveExchangeRate(it) is CatalogResult.Saved) } }
        }
    }

    @Test fun negativeRefundAndVeryLargeTotalsAreReadable() {
        val f = fixture()
        val source = save(f, 10000, day = today.withDayOfMonth(1).minusDays(1))
        save(f, 3000, BillKind.REFUND, linked = source)
        open()
        compose.onNodeWithTag("stats_total_EXPENSE").assertTextEquals("CNY -30.00")
        scroll("stats_chart"); capture("c5-negative.png")
        save(f, Long.MAX_VALUE - 10000)
        save(f, Long.MAX_VALUE, account = f.bank)
        scroll("stats_total_EXPENSE")
        compose.waitUntil(5000) { compose.onAllNodesWithText("CNY 184467440737095386.14").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("stats_total_EXPENSE").assertTextEquals("CNY 184467440737095386.14")
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("stats_total_EXPENSE").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("The complete large amount must be visible, not only its currency prefix", layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
        capture("c5-large.png")
    }

    @Test fun invalidPeriodKeepsInputAndAnnualCalendarSelectionFollowsBrowsedMonth() {
        fixture()
        open()
        scroll("stats_period"); compose.onNodeWithTag("stats_period").performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("0")
        compose.onNodeWithText(compose.activity.getString(app.omymoney.android.R.string.confirm)).performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction())[0].assertTextContains("0")
        compose.onNodeWithText(compose.activity.getString(app.omymoney.android.R.string.budget_invalid_period)).assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("2024")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("2")
        compose.onNodeWithText(compose.activity.getString(app.omymoney.android.R.string.confirm)).performScrollTo().performClick()
        compose.onNodeWithTag("stats_period").assertTextContains("2024-02", substring = true)
        scroll("stats_monthly"); compose.onNodeWithTag("stats_monthly").assertTextContains("月度")
        compose.onNodeWithTag("stats_annual").performClick()
        tab(2)
        scroll("stats_calendar_next"); compose.onNodeWithTag("stats_calendar_next").performClick()
        scroll("stats_selected_date"); compose.onNodeWithTag("stats_selected_date").assertTextEquals("2024-03-01")
        scroll("stats_day_2024-03-31"); compose.onNodeWithTag("stats_day_2024-03-31").performClick()
        scroll("stats_selected_date"); compose.onNodeWithTag("stats_selected_date").assertTextEquals("2024-03-31")
        scroll("stats_calendar_month")
        capture("c5-calendar.png")
    }
}
