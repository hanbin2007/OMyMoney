package app.omymoney

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.data.*
import app.omymoney.data.backup.*
import app.omymoney.feature.settings.*
import app.omymoney.ui.theme.LedgerTheme
import app.omymoney.ui.theme.ThemeMode
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BackupPreviewTest {
    @get:Rule val rule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var source: LedgerDatabase
    private lateinit var target: LedgerDatabase
    private lateinit var archive: BackupArchive
    private lateinit var receiver: BackupArchive
    private lateinit var model: BackupViewModel
    @Before fun setup() = runBlocking {
        source = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        archive = BackupArchive(source, context)
        receiver = BackupArchive(target, context)
        val draft = CatalogRepository(source).initialDraft().copy(amount = Money(2345, CurrencySpec.CNY))
        assertTrue(RoomLedgerRepository(source).save(draft, null) is SaveResult.Saved)
    }
    @After fun cleanup() { source.close(); target.close() }
    private fun show(preview: RestorePreview) {
        model = BackupViewModel(receiver, context.contentResolver, SavedStateHandle(mapOf(BackupViewModel.PREVIEW_KEY to preview.previewId)))
        rule.setContent { LedgerTheme(ThemeMode.SYSTEM, dynamicColor = false) { BackupScreen(model) {} } }
        rule.waitUntil(5000) { !model.state.value.busy && model.state.value.preview != null }
    }
    private suspend fun inspect(): RestorePreview = ByteArrayOutputStream().let { archive.exportTo(it); receiver.inspect(it.toByteArray().inputStream()) }
    @Test fun savingsPlansAndDepositsAreDisclosedBeforeRestore(): Unit = runBlocking {
        val book = CatalogRepository(source).observeBooks().first().single()
        val savings = SavingsRepository(source)
        val plan = app.omymoney.core.savings.SavingsPlan(bookId = book.id, name = "Travel", zone = book.zoneId,
            rule = app.omymoney.core.savings.SavingsRule(app.omymoney.core.savings.SavingsMode.CUSTOM, Money(10000, CurrencySpec.CNY), java.time.LocalDate.parse("2024-01-31")))
        assertTrue(savings.savePlan(plan) is SavingsResult.Saved)
        assertTrue(savings.saveDeposit(app.omymoney.core.savings.SavingsDeposit(planId = plan.id, date = java.time.LocalDate.parse("2024-02-01"), amount = Money(1000, CurrencySpec.CNY))) is SavingsResult.Saved)
        show(inspect())
        rule.onNodeWithTag("backup_savings_counts").performScrollTo().assertTextContains("存钱计划 1 · 入金记录 1")
        rule.waitForIdle(); captureUiEvidence("c7-backup.png")
        rule.onNodeWithTag("backup_cancel").performScrollTo().performClick()
        rule.waitUntil(5000) { model.state.value.preview == null }
        assertTrue(SavingsRepository(target).observeBookDeposits(book.id).first().isEmpty())
    }
    @Test fun importedRawRecordsAndBatchUndoCountsAreDisclosed(): Unit = runBlocking {
        val book = CatalogRepository(source).observeBooks().first().single()
        val cash = CatalogRepository(source).observeAccounts().first().first()
        val importer = app.omymoney.data.exchange.BillImportService(source, context)
        val table = "occurred_at,kind,amount,currency,from_account\n2026-10-11T12:00:00Z,EXPENSE,1.00,CNY,${cash.name}\n"
        val pending = importer.preview(table.byteInputStream(), app.omymoney.data.exchange.ExchangeFormat.CSV, book.id)
        importer.commit(pending.previewId, app.omymoney.data.exchange.ImportChoices(setOf(2)))
        val bill = RoomLedgerRepository(source).observeBills(BillQuery(bookId = book.id)).first().first()
        BatchActions(source).edit(mapOf(bill.id to bill.revision), BatchPatch(note = "Changed"))
        show(inspect())
        rule.onNodeWithTag("backup_exchange_counts").performScrollTo().assertTextContains("导入原始记录 1 · 批量操作记录 1")
        rule.waitForIdle(); captureUiEvidence("c9-backup.png")
    }
    @Test fun previewCancelNeverChangesLocalData(): Unit = runBlocking {
        CatalogRepository(target).ensureLocalDefaults()
        show(inspect())
        rule.onNodeWithTag("restore_merge").assertIsSelected()
        rule.onNodeWithTag("backup_cancel").performScrollTo().performClick()
        rule.waitUntil(5000) { !model.state.value.busy && model.state.value.preview == null }
        assertEquals(0, target.ledgerDao().billCount())
        rule.onNodeWithTag("backup_import").assertIsDisplayed()
    }
    @Test fun replacementNeedsExplicitConfirmationAndKeepsRecovery(): Unit = runBlocking {
        CatalogRepository(target).ensureLocalDefaults()
        show(inspect())
        rule.onNodeWithTag("restore_replace").performScrollTo().performClick()
        rule.onNodeWithTag("backup_restore").performScrollTo().performClick()
        rule.onNodeWithText("替换全部本地数据？").assertIsDisplayed()
        rule.waitForIdle()
        captureUiEvidence("a8-replace-confirm.png")
        assertEquals(0, target.ledgerDao().billCount())
        rule.onNode(hasText("取消") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(0, target.ledgerDao().billCount())
        rule.onNodeWithTag("backup_restore").performScrollTo().performClick()
        rule.onNodeWithTag("backup_confirm").performClick()
        rule.waitUntil(5000) { !model.state.value.busy && model.state.value.notice == "restored" }
        assertEquals(1, target.ledgerDao().billCount())
        rule.onNodeWithTag("backup_recovery").performScrollTo().assertIsDisplayed()
        rule.waitForIdle()
        captureUiEvidence("a8-restored.png")
    }
    @Test fun conflictsDisableMergeButReplacementRemainsExplicit(): Unit = runBlocking {
        receiver.restore(inspect().previewId, RestoreMode.MERGE)
        val bill = RoomLedgerRepository(source).observeBills(BillQuery()).first().single()
        RoomLedgerRepository(source).save(bill.toDraft().copy(note = "conflict"), bill.revision)
        show(inspect())
        rule.onNodeWithTag("backup_restore").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("conflict", substring = true).assertExists()
        rule.waitForIdle()
        captureUiEvidence("a8-conflict.png")
        rule.onNodeWithTag("restore_replace").performScrollTo().performClick()
        rule.onNodeWithTag("backup_restore").performScrollTo().assertIsEnabled()
    }
    @Test fun stalePreviewErrorIsVisibleAfterSubmittingFromBottom(): Unit = runBlocking {
        CatalogRepository(target).ensureLocalDefaults()
        show(inspect())
        target.ledgerDao().upsertPreference(PreferenceEntity("changed-after-preview", "yes"))
        rule.onNodeWithTag("restore_replace").performScrollTo().performClick()
        rule.onNodeWithTag("backup_restore").performScrollTo().performClick()
        rule.onNodeWithTag("backup_confirm").performClick()
        rule.waitUntil(5000) { !model.state.value.busy && model.state.value.error != null }
        rule.waitForIdle()
        captureUiEvidence("a8-stale-error.png")
        rule.onNodeWithText("预览后本地数据已改变。请取消预览并重新选择备份，确认最新状态。").assertIsDisplayed()
        rule.waitForIdle()
        captureUiEvidence("a8-stale-error.png")
        assertEquals(0, target.ledgerDao().billCount())
    }

    @Test fun scheduleAutomationAndPendingCountsAreDisclosedBeforeRestoring(): Unit = runBlocking {
        val draft = CatalogRepository(source).initialDraft().copy(amount = Money(1000, CurrencySpec.CNY))
        val schedules = ScheduledBillRepository(source)
        val now = java.time.LocalDate.now(draft.zoneId)
        val scheduleRule = app.omymoney.core.schedule.ScheduleRule(now.minusDays(1), zone = draft.zoneId, count = 1)
        assertTrue(schedules.save(ScheduledPlan(name = "Pending", template = draft, rule = scheduleRule)) is ScheduleSaveResult.Saved)
        assertTrue(schedules.save(ScheduledPlan(name = "Automatic", template = draft, rule = scheduleRule.copy(startDate = now.plusDays(1)), automatic = true)) is ScheduleSaveResult.Saved)
        schedules.executeDue(java.time.Instant.now())
        show(inspect())
        rule.onNodeWithTag("backup_schedule_counts").performScrollTo().assertIsDisplayed().assertTextContains("2", substring = true)
        rule.onNodeWithTag("backup_schedule_counts").assertTextContains("自动入账 1", substring = true).assertTextContains("待确认期次 1", substring = true)
        rule.waitForIdle()
        captureUiEvidence("c6-backup.png")
        rule.onNodeWithTag("backup_cancel").performScrollTo().performClick()
        assertTrue(target.ledgerDao().allSchedules().isEmpty())
    }
}
