package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.core.model.*
import app.omymoney.data.*
import java.time.ZoneOffset
import java.time.LocalDate
import app.omymoney.core.schedule.ScheduleRule
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ScheduleUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store
    private data class Fixture(val book: Book, val cash: AssetAccount)
    private fun fixture(create: Boolean = true): Fixture {
        val book = Book(UUID.randomUUID().toString(), "Plan ${UUID.randomUUID().toString().take(6)}", zoneId = ZoneOffset.UTC)
        val cash = AssetAccount(UUID.randomUUID().toString(), "Cash ${book.id.take(6)}")
        runBlocking {
            assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(cash) is CatalogResult.Saved)
            store.catalog.selectBook(book.id)
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_schedules").performScrollTo().performClick()
        if (create) {
            compose.onNodeWithTag("new_schedule").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("sch_name").fetchSemanticsNodes().isNotEmpty() }
        }
        return Fixture(book, cash)
    }
    private fun enter(tag: String, value: String) { compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value) }
    private fun configure(f: Fixture, automatic: Boolean = false) {
        enter("sch_name", "Travel installments")
        compose.onNodeWithTag("sch_mode_installments").performScrollTo().performClick()
        compose.onNodeWithTag("sch_from").performScrollTo().performClick()
        compose.onNodeWithTag("selection_search").performTextInput(f.cash.name)
        compose.waitForIdle(); androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("option_${f.cash.id}").performScrollTo().assertIsDisplayed().performClick()
        enter("sch_amount", "100")
        enter("sch_start", "2024-01-31")
        enter("sch_count", "3")
        if (automatic) compose.onNodeWithTag("sch_automatic").performScrollTo().performClick()
    }
    private fun saved(f: Fixture): ScheduledPlan {
        compose.onNodeWithTag("sch_save").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("sch_detail").fetchSemanticsNodes().isNotEmpty() }
        val plan = runBlocking { store.schedules.observePlans(f.book.id).first().single() }
        compose.onNodeWithTag("sch_run").performClick()
        compose.waitUntil(10000) { runBlocking { store.schedules.observeExecutions(plan.id).first().size == 3 } }
        return plan
    }
    private fun scroll(tag: String) { compose.onNodeWithTag("sch_detail").performScrollToNode(hasTestTag(tag)) }
    private fun capture(name: String) { compose.waitForIdle(); captureUiEvidence(name) }

    @Test fun installmentPreviewDefaultsToDraftsAndConfirmationUsesTheCommonEditor() {
        val f = fixture(); configure(f)
        compose.onNodeWithTag("sch_preview").performClick()
        compose.onNodeWithTag("sch_preview_context").performScrollTo().assertTextContains(f.book.name, substring = true)
            .assertTextContains(f.cash.name, substring = true).assertTextContains("逐笔确认后入账", substring = true)
        capture("c6-preview-context.png")
        compose.onNodeWithTag("sch_preview_0").performScrollTo().assertTextContains("CNY 33.33", substring = true)
        compose.onNodeWithTag("sch_preview_2").performScrollTo().assertTextContains("CNY 33.34", substring = true)
        capture("c6-preview.png")
        compose.onNodeWithTag("sch_preview_close").performScrollTo().performClick()
        val plan = saved(f)
        assertFalse(plan.automatic)
        assertEquals(0L, runBlocking { store.catalog.observeAccounts().first().single { it.id == f.cash.id }.balance.minor })
        val first = runBlocking { store.schedules.observeExecutions(plan.id).first().minBy { it.index } }
        scroll("sch_execution_${first.id}")
        capture("c6-pending.png")
        compose.onNodeWithTag("sch_execution_${first.id}").performClick()
        compose.onNodeWithTag("bill_amount").assertTextContains("33.33")
        compose.onNodeWithTag("bill_note").performScrollTo().performTextReplacement("Confirmed later")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNode(hasText("跳过此期") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("保留草稿").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sch_detail").fetchSemanticsNodes().isNotEmpty() }
        scroll("sch_execution_${first.id}")
        compose.onNodeWithTag("sch_execution_${first.id}").performClick()
        compose.onNodeWithTag("bill_note").performScrollTo().assertTextContains("Confirmed later")
        compose.onNodeWithTag("bill_save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sch_detail").fetchSemanticsNodes().isNotEmpty() }
        scroll("sch_execution_${first.id}")
        compose.onNodeWithTag("sch_execution_${first.id}").assertTextContains("已入账", substring = true)
        assertEquals(-3333L, runBlocking { store.catalog.observeAccounts().first().single { it.id == f.cash.id }.balance.minor })
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sch_detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sch_run").performClick()
        assertEquals(3, runBlocking { store.schedules.observeExecutions(plan.id).first().size })
        scroll("sch_execution_${first.id}")
        capture("c6-confirmed.png")
    }

    @Test fun explicitAutomaticModePostsTheExactTotalAndStartedScheduleRemainsInspectable() {
        val f = fixture(); configure(f, automatic = true)
        val plan = saved(f)
        assertTrue(plan.automatic)
        assertEquals(-10000L, runBlocking { store.catalog.observeAccounts().first().single { it.id == f.cash.id }.balance.minor })
        val rows = runBlocking { store.schedules.observeExecutions(plan.id).first() }
        assertTrue(rows.all { it.state == ScheduleExecutionState.POSTED })
        scroll("sch_summary")
        capture("c6-automatic.png")
        compose.onNodeWithTag("sch_edit").performClick()
        compose.onNodeWithTag("sch_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("sch_amount").performScrollTo().assertIsNotEnabled()
        enter("sch_name", "Renamed installments")
        compose.onNodeWithTag("sch_save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sch_detail").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Renamed installments", runBlocking { store.schedules.read(plan.id)!!.name })
        assertEquals(-10000L, runBlocking { store.catalog.observeAccounts().first().single { it.id == f.cash.id }.balance.minor })
    }

    @Test fun invalidFieldsStayVisibleAcrossRecreationAndDiscardDoesNotCreateAPlan() {
        val f = fixture(); configure(f)
        enter("sch_amount", "bad")
        compose.onNodeWithTag("sch_save").performClick()
        capture("c6-invalid.png")
        compose.onNodeWithTag("sch_amount_error", useUnmergedTree = true).assertIsDisplayed()
        val fieldBounds = compose.onNodeWithTag("sch_amount").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("sch_editor_form").getUnclippedBoundsInRoot()
        assertTrue("Validation must reveal both the complete input and its error: $fieldBounds / $viewport",
            fieldBounds.top >= viewport.top && fieldBounds.bottom <= viewport.bottom)
        compose.onNodeWithTag("sch_amount").assertTextContains(compose.activity.getString(app.omymoney.android.R.string.schedule_error_amount), substring = true)
        compose.onNodeWithTag("sch_amount").assertTextContains("bad")
        compose.onNodeWithTag("sch_end").performScrollTo()
        compose.onNodeWithTag("sch_save").performClick()
        compose.onNodeWithTag("sch_amount_error", useUnmergedTree = true).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sch_amount").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sch_amount").performScrollTo().assertTextContains("bad")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithTag("sch_continue_editing").performClick()
        compose.onNodeWithTag("sch_amount").assertTextContains("bad")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithTag("sch_discard").performClick()
        assertTrue(runBlocking { store.schedules.observePlans(f.book.id).first().isEmpty() })
    }

    @Test fun pauseResumeAndSkipHaveExplicitCancellationAndNoBalanceEffect() {
        val f = fixture(create = false)
        val plan = ScheduledPlan(name = "Paused rent", paused = true, template = TransactionDraft(f.book.id,
            kind = BillKind.EXPENSE, amount = Money(10000, CurrencySpec.CNY), fromAccountId = f.cash.id),
            rule = ScheduleRule(LocalDate.parse("2024-01-31"), zone = ZoneOffset.UTC, count = 3))
        assertTrue(runBlocking { store.schedules.save(plan) } is ScheduleSaveResult.Saved)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sch_plan_${plan.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sch_plan_${plan.id}").performClick()
        scroll("sch_enabled")
        compose.onNodeWithTag("sch_enabled").assertIsOff()
        compose.onNodeWithTag("sch_run").assertIsNotEnabled()
        capture("c6-paused.png")
        compose.onNodeWithTag("sch_enabled").performClick()
        compose.onNodeWithText(compose.activity.getString(app.omymoney.android.R.string.cancel)).performClick()
        compose.onNodeWithTag("sch_enabled").assertIsOff().performClick()
        compose.onNodeWithTag("sch_resume_confirm").performClick()
        compose.waitUntil(5000) { runBlocking { store.schedules.observeExecutions(plan.id).first().size == 3 } }
        val first = runBlocking { store.schedules.observeExecutions(plan.id).first().minBy { it.index } }
        scroll("sch_execution_${first.id}")
        capture("c6-resumed.png")
        compose.onNodeWithTag("sch_skip_${first.id}").assertContentDescriptionEquals("跳过第 1 期").performClick()
        compose.onNode(hasText("第 1 期", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("2024-01-31", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("CNY 100.00", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText(compose.activity.getString(app.omymoney.android.R.string.cancel)).performClick()
        assertEquals(ScheduleExecutionState.PENDING, runBlocking { store.schedules.observeExecutions(plan.id).first().single { it.id == first.id }.state })
        compose.onNodeWithTag("sch_skip_${first.id}").performClick()
        compose.onNodeWithTag("sch_skip_confirm").performClick()
        compose.waitUntil(5000) { runBlocking { store.schedules.observeExecutions(plan.id).first().single { it.id == first.id }.state == ScheduleExecutionState.SKIPPED } }
        scroll("sch_execution_${first.id}")
        compose.onNodeWithTag("sch_execution_${first.id}").assertTextContains("已跳过", substring = true)
        capture("c6-skipped.png")
        assertEquals(0L, runBlocking { store.catalog.observeAccounts().first().single { it.id == f.cash.id }.balance.minor })
    }

    @Test fun failedPlanWriteKeepsInputAndRetryCreatesOnlyOnePlan() {
        val f = fixture(); configure(f)
        val database = compose.activity.openOrCreateDatabase("omymoney.db", android.content.Context.MODE_PRIVATE, null)
        try {
            database.execSQL("CREATE TRIGGER fail_schedule_ui BEFORE INSERT ON schedules BEGIN SELECT RAISE(ABORT, 'test'); END")
            compose.onNodeWithTag("sch_save").performClick()
            compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("sch_general_error").assertIsDisplayed(); true }.getOrDefault(false) }
            withFailureEvidence("failure-schedule-error") { compose.onNodeWithTag("sch_general_error").assertIsDisplayed() }
            capture("c6-storage.png")
            compose.onNodeWithTag("sch_amount").assertTextContains("100")
            assertTrue(runBlocking { store.schedules.observePlans(f.book.id).first().isEmpty() })
        } finally {
            database.execSQL("DROP TRIGGER IF EXISTS fail_schedule_ui")
            database.close()
        }
        saved(f)
        assertEquals(1, runBlocking { store.schedules.observePlans(f.book.id).first().size })
    }

    @Test fun failedResumeKeepsAutomaticPlanPausedAndRetryPostsOnce() {
        val f = fixture(create = false)
        val plan = ScheduledPlan(name = "Atomic resume", paused = true, automatic = true,
            template = TransactionDraft(f.book.id, kind = BillKind.EXPENSE, amount = Money(10000, CurrencySpec.CNY), fromAccountId = f.cash.id),
            rule = ScheduleRule(LocalDate.parse("2024-01-31"), zone = ZoneOffset.UTC, count = 1))
        assertTrue(runBlocking { store.schedules.save(plan) } is ScheduleSaveResult.Saved)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sch_plan_${plan.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sch_plan_${plan.id}").performClick()
        val database = compose.activity.openOrCreateDatabase("omymoney.db", android.content.Context.MODE_PRIVATE, null)
        try {
            database.execSQL("CREATE TRIGGER fail_resume_ui BEFORE INSERT ON schedule_executions BEGIN SELECT RAISE(ABORT, 'test'); END")
            scroll("sch_enabled")
            compose.onNodeWithTag("sch_enabled").performClick()
            compose.onNodeWithTag("sch_resume_confirm").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText(compose.activity.getString(app.omymoney.android.R.string.schedule_storage_failed)).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("Failed catch-up must not enable automatic future posting", runBlocking { store.schedules.read(plan.id)!!.paused })
            assertEquals(0, runBlocking { store.schedules.read(plan.id)!!.nextIndex })
            assertTrue(runBlocking { store.schedules.observeExecutions(plan.id).first().isEmpty() })
            assertEquals(0L, runBlocking { store.catalog.observeAccounts().first().single { it.id == f.cash.id }.balance.minor })
            compose.onNodeWithTag("sch_detail").performScrollToIndex(1)
            compose.onNodeWithTag("sch_enabled").assertIsOff()
            capture("c6-resume-failed.png")
        } finally {
            database.execSQL("DROP TRIGGER IF EXISTS fail_resume_ui")
            database.close()
        }
        compose.onNodeWithTag("sch_enabled").performClick()
        compose.onNodeWithTag("sch_resume_confirm").performClick()
        compose.waitUntil(5000) { runBlocking { store.schedules.observeExecutions(plan.id).first().size == 1 } }
        assertFalse(runBlocking { store.schedules.read(plan.id)!!.paused })
        assertEquals(-10000L, runBlocking { store.catalog.observeAccounts().first().single { it.id == f.cash.id }.balance.minor })
    }

    @Test fun skippedFailedAutomaticDraftDoesNotOfferAnUnavailableRecoveryAction() {
        val f = fixture(create = false)
        val plan = ScheduledPlan(name = "Archived account", paused = true, automatic = true,
            template = TransactionDraft(f.book.id, kind = BillKind.EXPENSE, amount = Money(10000, CurrencySpec.CNY), fromAccountId = f.cash.id),
            rule = ScheduleRule(LocalDate.parse("2024-01-31"), zone = ZoneOffset.UTC, count = 1))
        runBlocking {
            assertTrue(store.schedules.save(plan) is ScheduleSaveResult.Saved)
            assertTrue(store.catalog.archiveAccount(f.cash.id) is CatalogResult.Saved)
            assertTrue(store.schedules.setPaused(plan.id, false) is ScheduleSaveResult.Saved)
            store.schedules.executeDue(java.time.Instant.now(), plan.id)
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("sch_plan_${plan.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sch_plan_${plan.id}").performClick()
        val row = runBlocking { store.schedules.observeExecutions(plan.id).first().single() }
        assertNotNull(row.failure)
        scroll("sch_execution_${row.id}")
        compose.onNodeWithTag("sch_execution_${row.id}").assertTextContains(compose.activity.getString(app.omymoney.android.R.string.schedule_failed_posting), substring = true)
        compose.onNodeWithTag("sch_skip_${row.id}").performClick()
        capture("c6-skip-identity.png")
        compose.onNodeWithTag("sch_skip_confirm").performClick()
        compose.waitUntil(5000) { runBlocking { store.schedules.observeExecutions(plan.id).first().single().state == ScheduleExecutionState.SKIPPED } }
        compose.onNodeWithTag("sch_execution_${row.id}").assertTextContains("已跳过", substring = true)
        compose.onNodeWithText(compose.activity.getString(app.omymoney.android.R.string.schedule_failed_posting), substring = true).assertDoesNotExist()
        capture("c6-skipped-failure.png")
    }
}
