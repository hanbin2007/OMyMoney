package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.core.model.*
import app.omymoney.core.savings.*
import app.omymoney.data.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SavingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store
    private data class Fixture(val book: Book, val cash: AssetAccount, val bank: AssetAccount)
    private fun fixture(): Fixture {
        val book = Book(UUID.randomUUID().toString(), "Savings ${UUID.randomUUID().toString().take(6)}", zoneId = ZoneOffset.UTC)
        val cash = AssetAccount(UUID.randomUUID().toString(), "Cash ${book.id.take(6)}")
        val bank = AssetAccount(UUID.randomUUID().toString(), "Bank ${book.id.take(6)}")
        runBlocking {
            assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(cash) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(bank) is CatalogResult.Saved)
            store.catalog.selectBook(book.id)
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_savings").performScrollTo().performClick()
        return Fixture(book, cash, bank)
    }
    private fun enter(tag: String, value: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    private fun create(f: Fixture, custom: Boolean = true): SavingsPlan {
        compose.onNodeWithTag("sav_new").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sav_name").fetchSemanticsNodes().isNotEmpty() }
        enter("sav_name", "Travel fund")
        if (custom) {
            compose.onNodeWithTag("sav_mode").performScrollTo().performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("option_CUSTOM"))
            compose.onNodeWithTag("option_CUSTOM").performClick()
        }
        enter("sav_amount", "100")
        if (!custom) { enter("sav_count", "3"); enter("sav_start", "2024-01-31") }
        compose.onNodeWithTag("sav_save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sav_detail").fetchSemanticsNodes().isNotEmpty() }
        return runBlocking { store.savings.observePlans(f.book.id).first().single() }
    }
    private fun scroll(tag: String) = compose.onNodeWithTag("sav_detail").performScrollToNode(hasTestTag(tag))
    private fun capture(name: String) { compose.waitForIdle(); captureUiEvidence(name) }
    private fun addManual(value: String) {
        compose.onNodeWithTag("sav_add").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("dep_amount").fetchSemanticsNodes().isNotEmpty() }
        enter("dep_amount", value)
        compose.onNodeWithTag("dep_save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sav_detail").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun manualDepositEditAndCancellationKeepLedgerUntouchedAndClampProgress() {
        val f = fixture(); val p = create(f)
        addManual("60")
        scroll("sav_summary")
        compose.onNodeWithTag("sav_summary").assertTextContains("60.00", substring = true).assertTextContains("40.00", substring = true)
        capture("c7-progress.png")
        val d = runBlocking { store.savings.observeDeposits(p.id).first().single() }
        scroll("sav_deposit_${d.id}")
        compose.onNodeWithTag("sav_deposit_${d.id}").performClick()
        enter("dep_amount", "110")
        compose.onNodeWithTag("dep_save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sav_detail").fetchSemanticsNodes().isNotEmpty() }
        scroll("sav_summary")
        compose.onNodeWithTag("sav_summary").assertTextContains("100%", substring = true).assertTextContains("10.00", substring = true)
        capture("c7-completed.png")
        scroll("sav_remove_${d.id}")
        compose.onNodeWithTag("sav_remove_${d.id}").performClick()
        compose.onNodeWithText(compose.activity.getString(app.omymoney.android.R.string.cancel)).performClick()
        assertEquals(1, runBlocking { store.savings.observeDeposits(p.id).first().size })
        compose.onNodeWithTag("sav_remove_${d.id}").performClick()
        compose.onNodeWithTag("sav_remove_confirm").performClick()
        compose.waitUntil(5000) { runBlocking { store.savings.observeDeposits(p.id).first().isEmpty() } }
        assertTrue(runBlocking { store.ledger.observeBills(BillQuery(bookId = f.book.id)).first().isEmpty() })
        assertEquals(0L, runBlocking { store.catalog.observeAccounts().first().single { it.id == f.cash.id }.balance.minor })
    }

    @Test fun increasingPreviewIsExactAndExistingRecordsLockOnlyTheCurrency() {
        val f = fixture()
        compose.onNodeWithTag("sav_new").performClick()
        enter("sav_name", "Increasing fund")
        compose.onNodeWithTag("sav_mode").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("option_INCREASING"))
        compose.onNodeWithTag("option_INCREASING").performClick()
        enter("sav_amount", "100"); enter("sav_increment", "10"); enter("sav_count", "3"); enter("sav_start", "2024-01-31")
        compose.onNodeWithTag("sav_preview").performClick()
        compose.onNodeWithTag("sav_preview_context").performScrollTo().assertTextContains("CNY 330.00", substring = true)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("sav_preview_2"))
        compose.onNodeWithTag("sav_preview_2").assertTextContains("CNY 120.00", substring = true)
        capture("c7-preview.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("sav_preview_close"))
        compose.onNodeWithTag("sav_preview_close").performScrollTo().performClick()
        compose.onNodeWithTag("sav_save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sav_detail").fetchSemanticsNodes().isNotEmpty() }
        addManual("50")
        compose.onNodeWithTag("sav_edit").performClick()
        compose.onNodeWithTag("sav_currency").performScrollTo().onChildren().filter(isNotEnabled()).assertCountEquals(1)
        enter("sav_amount", "200")
        compose.onNodeWithTag("sav_save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sav_detail").fetchSemanticsNodes().isNotEmpty() }
        scroll("sav_summary")
        compose.onNodeWithTag("sav_summary").assertTextContains("630.00", substring = true).assertTextContains("580.00", substring = true)
        assertEquals(1, runBlocking { store.savings.observeBookDeposits(f.book.id).first().size })
    }

    @Test fun linkingARealTransferDoesNotPostAgainAndDeletionIsVisible() {
        val f = fixture(); val p = create(f)
        val bill = runBlocking { store.ledger.save(TransactionDraft(f.book.id, kind = BillKind.TRANSFER, amount = Money(6000, CurrencySpec.CNY),
            fromAccountId = f.cash.id, toAccountId = f.bank.id, occurredAt = Instant.parse("2024-01-31T00:00:00Z"),
            timeSource = TimeSource.USER, note = "Move to savings"), null) as SaveResult.Saved }
        compose.onNodeWithTag("sav_add").performClick()
        compose.onNodeWithTag("dep_mode_link").performScrollTo().performClick()
        compose.onNodeWithTag("dep_transfer").performScrollTo().performClick()
        compose.onNodeWithTag("option_${bill.billId}").performScrollTo().performClick()
        compose.onNodeWithTag("dep_save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sav_detail").fetchSemanticsNodes().isNotEmpty() }
        val d = runBlocking { store.savings.observeDeposits(p.id).first().single() }
        scroll("sav_deposit_${d.id}")
        compose.onNodeWithTag("sav_deposit_${d.id}").assertTextContains("关联转账", substring = true).assertTextContains("60.00", substring = true)
        capture("c7-linked.png")
        fun modeRoundTrip(note: String, recreate: Boolean) {
            scroll("sav_deposit_${d.id}")
            compose.onNodeWithTag("sav_deposit_${d.id}").performClick()
            compose.onNodeWithTag("dep_mode_manual").performScrollTo().performClick()
            enter("dep_date", "not-a-date")
            compose.onNodeWithTag("dep_mode_link").performScrollTo().performClick()
            if (recreate) compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("dep_mode_manual").performScrollTo().performClick()
            compose.onNodeWithTag("dep_date").performScrollTo().assertTextContains("not-a-date")
            compose.onNodeWithTag("dep_mode_link").performScrollTo().performClick()
            enter("dep_note", note)
            compose.onNodeWithTag("dep_save").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("sav_detail").fetchSemanticsNodes().isNotEmpty() }
            val updated = runBlocking { store.savings.readDeposit(d.id)!! }
            assertEquals(LocalDate.parse("2024-01-31"), updated.date)
            assertEquals(note, updated.note)
        }
        modeRoundTrip("Active link corrected", recreate = true)
        assertEquals(1, runBlocking { store.ledger.observeBills(BillQuery(bookId = f.book.id)).first().size })
        assertEquals(6000L, runBlocking { store.catalog.observeAccounts().first().single { it.id == f.bank.id }.balance.minor })
        runBlocking { store.ledger.delete(bill.billId) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("关联转账已删除或不可用", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("c7-unavailable.png")
        scroll("sav_summary")
        compose.onNodeWithTag("sav_summary").assertTextContains("0.00", substring = true)
        modeRoundTrip("Unavailable link corrected", recreate = false)
        scroll("sav_deposit_${d.id}")
        compose.onNodeWithTag("sav_deposit_${d.id}").assertTextContains("Unavailable link corrected", substring = true)
        capture("c7-linked-edited.png")
    }

    @Test fun invalidInputSurvivesRecreationAndDiscardWritesNothing() {
        val f = fixture()
        compose.onNodeWithTag("sav_new").performClick()
        enter("sav_name", "Invalid input")
        enter("sav_amount", "bad")
        compose.onNodeWithTag("sav_save").performClick()
        compose.onNodeWithTag("sav_amount_error", useUnmergedTree = true).assertIsDisplayed()
        val field = compose.onNodeWithTag("sav_amount").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("sav_editor").getUnclippedBoundsInRoot()
        assertTrue("Input and validation must be visible together: $field / $viewport", field.top >= viewport.top && field.bottom <= viewport.bottom)
        capture("c7-invalid.png")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sav_amount").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sav_amount").performScrollTo().assertTextContains("bad")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithTag("sav_keep_editing").performClick()
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithTag("sav_discard").performClick()
        assertTrue(runBlocking { store.savings.observePlans(f.book.id).first().isEmpty() })
    }

    @Test fun fixedCalendarPauseAndResumeRemainAvailableWithoutAutoDeposits() {
        val f = fixture(); val p = create(f, custom = false)
        scroll("sav_tab_schedule")
        compose.onNodeWithTag("sav_tab_schedule").performClick()
        scroll("sav_step_1")
        compose.onNodeWithTag("sav_step_1").assertTextContains("2024-02-29", substring = true)
        scroll("sav_step_2")
        compose.onNodeWithTag("sav_step_2").assertTextContains("2024-03-31", substring = true)
        capture("c7-calendar.png")
        scroll("sav_enabled")
        compose.onNodeWithTag("sav_enabled").assertTextContains("接受新入金").performClick()
        compose.onNodeWithTag("sav_add").assertIsNotEnabled()
        compose.onNodeWithTag("sav_enabled").performClick()
        compose.onNodeWithTag("sav_add").assertIsEnabled()
        assertTrue(runBlocking { store.savings.observeDeposits(p.id).first().isEmpty() })
    }

    @Test fun pausedStatusIsExplicitInThePlanSummary() {
        val f = fixture(); val p = create(f)
        scroll("sav_enabled")
        compose.onNodeWithTag("sav_enabled").performClick()
        compose.waitUntil(5000) { runBlocking { store.savings.readPlan(p.id)!!.paused } }
        scroll("sav_summary")
        compose.onNodeWithTag("sav_summary").assertTextContains("已暂停", substring = true)
        capture("c7-paused.png")
    }

    @Test fun longPlanListSortsPersistentlyAndDeletionHasExplicitCancellation() {
        val f = fixture()
        val plans = runBlocking { (0 until 35).map { index ->
            (store.savings.savePlan(SavingsPlan(bookId = f.book.id, name = "Plan $index", zone = f.book.zoneId,
                rule = SavingsRule(SavingsMode.CUSTOM, Money(10000, CurrencySpec.CNY), LocalDate.parse("2024-01-31")))) as SavingsResult.Saved).value
        } }
        compose.onNodeWithTag("sav_sort").performClick()
        compose.onNodeWithTag("sav_plans").performScrollToNode(hasTestTag("sav_plan_${plans.last().id}"))
        compose.onNodeWithTag("sav_up_${plans.last().id}").performClick()
        assertEquals(plans.last().id, runBlocking { store.savings.observePlans(f.book.id).first()[33].id })
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sav_plans").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sav_plans").performScrollToNode(hasTestTag("sav_plan_${plans.last().id}"))
        capture("c7-sorted.png")
        compose.onNodeWithTag("sav_plan_${plans.last().id}").performClick()
        compose.onNodeWithTag("sav_delete_plan").performClick()
        compose.onNodeWithText(compose.activity.getString(app.omymoney.android.R.string.cancel)).performClick()
        assertNotNull(runBlocking { store.savings.readPlan(plans.last().id) })
        compose.onNodeWithTag("sav_delete_plan").performClick()
        capture("c7-delete.png")
        compose.onNodeWithTag("sav_delete_confirm").performClick()
        compose.waitUntil(5000) { runBlocking { store.savings.readPlan(plans.last().id) == null } }
    }

    @Test fun failedDepositWriteRetainsRawInputAndRetryHasNoDuplicate() {
        val f = fixture(); val p = create(f)
        compose.onNodeWithTag("sav_add").performClick()
        enter("dep_amount", "12.34")
        val database = compose.activity.openOrCreateDatabase("omymoney.db", android.content.Context.MODE_PRIVATE, null)
        try {
            database.execSQL("CREATE TRIGGER fail_savings_ui BEFORE INSERT ON savings_deposits BEGIN SELECT RAISE(ABORT, 'test'); END")
            compose.onNodeWithTag("dep_save").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("dep_general_error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("dep_general_error").assertIsDisplayed()
            compose.onNodeWithTag("dep_amount").performScrollTo().assertTextContains("12.34")
            capture("c7-storage.png")
            assertTrue(runBlocking { store.savings.observeDeposits(p.id).first().isEmpty() })
        } finally { database.execSQL("DROP TRIGGER fail_savings_ui"); database.close() }
        compose.onNodeWithTag("dep_save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sav_detail").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, runBlocking { store.savings.observeDeposits(p.id).first().size })
    }

    @Test fun linkedDepositSaveIgnoresHiddenManualDateAndRetainsItsRawInput() {
        val f = fixture(); val p = create(f)
        val d = runBlocking {
            val bill = store.ledger.save(TransactionDraft(f.book.id, kind = BillKind.TRANSFER, amount = Money(6000, CurrencySpec.CNY),
                fromAccountId = f.cash.id, toAccountId = f.bank.id, occurredAt = Instant.parse("2024-01-31T00:00:00Z"),
                timeSource = TimeSource.USER), null) as SaveResult.Saved
            (store.savings.saveDeposit(SavingsDeposit(planId = p.id, date = LocalDate.parse("2024-01-31"),
                linkedBillId = bill.billId)) as SavingsResult.Saved).value
        }
        val handle = androidx.lifecycle.SavedStateHandle()
        lateinit var editor: app.omymoney.feature.savings.SavingsDepositEditorViewModel
        compose.runOnIdle { editor = app.omymoney.feature.savings.SavingsDepositEditorViewModel(store, f.book.id, p.id, d.id, handle) }
        compose.waitUntil(5000) { editor.state.value.form != null }
        compose.runOnIdle {
            editor.update { it.copy(linked = false, date = "not-a-date") }
            editor.update { it.copy(linked = true, note = "Edited linked record") }
            editor.save()
        }
        compose.waitUntil(5000) { !editor.state.value.saving }
        assertEquals("Hidden manual date must not block a linked record", d.id, editor.state.value.savedId)
        assertEquals("not-a-date", editor.state.value.form!!.date)
        val saved = runBlocking { store.savings.readDeposit(d.id)!! }
        assertEquals(LocalDate.parse("2024-01-31"), saved.date)
        assertEquals("Edited linked record", saved.note)
    }

    @Test fun failedSortAtTheBottomShowsVisibleErrorAndRetriesWithoutChangingPosition() {
        val f = fixture()
        val plans = runBlocking { (0 until 35).map { index ->
            (store.savings.savePlan(SavingsPlan(bookId = f.book.id, name = "Failure plan $index", zone = f.book.zoneId,
                rule = SavingsRule(SavingsMode.CUSTOM, Money(10000, CurrencySpec.CNY), LocalDate.parse("2024-01-31")))) as SavingsResult.Saved).value
        } }
        compose.onNodeWithTag("sav_sort").performClick()
        compose.onNodeWithTag("sav_plans").performScrollToNode(hasTestTag("sav_plan_${plans.last().id}"))
        val database = compose.activity.openOrCreateDatabase("omymoney.db", android.content.Context.MODE_PRIVATE, null)
        try {
            database.execSQL("CREATE TRIGGER fail_savings_sort BEFORE INSERT ON savings_plans BEGIN SELECT RAISE(ABORT, 'test'); END")
            compose.onNodeWithTag("sav_up_${plans.last().id}").performClick()
            compose.onNodeWithTag("sav_up_${plans.last().id}").assertIsEnabled()
            compose.onNodeWithText(compose.activity.getString(app.omymoney.android.R.string.savings_error_storage)).assertIsDisplayed()
            compose.onNodeWithTag("sav_up_${plans.last().id}").assertIsDisplayed()
            assertEquals(plans.map { it.id }, runBlocking { store.savings.observePlans(f.book.id).first().map { it.id } })
            capture("c7-sort-error.png")
        } finally { database.execSQL("DROP TRIGGER fail_savings_sort"); database.close() }
        compose.onNodeWithText(compose.activity.getString(app.omymoney.android.R.string.retry)).performClick()
        compose.waitUntil(5000) { runBlocking { store.savings.observePlans(f.book.id).first()[33].id == plans.last().id } }
    }
}
