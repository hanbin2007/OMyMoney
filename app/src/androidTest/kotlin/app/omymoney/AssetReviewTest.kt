package app.omymoney

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import app.omymoney.data.CatalogResult
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AssetReviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store
    private fun account(kind: AccountKind = AccountKind.CASH): AssetAccount {
        val id = UUID.randomUUID().toString()
        return AssetAccount(id, "Review ${id.take(6)}", kind = kind).also {
            runBlocking { assertTrue(store.catalog.saveAccount(it) is CatalogResult.Saved) }
        }
    }
    private fun open(account: AssetAccount) {
        compose.onNodeWithTag("nav_assets").performClick()
        compose.onNodeWithTag("account_list").performScrollToNode(hasText(account.name))
        compose.onNodeWithText(account.name).performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("asset_balance").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun credit(): AssetAccount = account(AccountKind.CREDIT).also {
        open(it)
        compose.onNodeWithTag("asset_credit_settings").performScrollTo().performClick()
        compose.onNodeWithTag("credit_enabled").performClick()
    }
    private fun save(kind: BillKind, from: String?, to: String?, amount: Long, linked: String? = null): String = runBlocking {
        val book = store.catalog.observeBooks().first().first()
        val result = store.ledger.save(TransactionDraft(book.id, kind = kind, amount = Money(amount, CurrencySpec.CNY),
            fromAccountId = from, toAccountId = to, linkedBillId = linked, note = "Review adjustment",
            occurredAt = Instant.now(), timeSource = TimeSource.USER, zoneId = book.zoneId), null)
        assertTrue(result.toString(), result is SaveResult.Saved)
        (result as SaveResult.Saved).billId
    }
    private fun assertHistory(id: String, value: String) {
        compose.onNodeWithTag("asset_transactions").performScrollToNode(hasTestTag("asset_bill_$id"))
        compose.onNodeWithTag("asset_bill_$id").assertTextContains(value)
    }

    @Test fun accountHistoryShowsSignedActualPostingsForBothAdjustmentAndTransferDirections() {
        val first = account()
        val second = account()
        val up = save(BillKind.ADJUSTMENT, null, first.id, 500)
        val down = save(BillKind.ADJUSTMENT, first.id, null, 200)
        val transfer = save(BillKind.TRANSFER, first.id, second.id, 100)
        open(first)
        assertHistory(up, "CNY +5.00")
        assertHistory(down, "CNY -2.00")
        assertHistory(transfer, "CNY -1.00")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithTag("account_list").performScrollToNode(hasText(second.name))
        compose.onNodeWithText(second.name).performScrollTo().performClick()
        assertHistory(transfer, "CNY +1.00")
    }

    @Test fun collectingPrincipalKeepsTheSourcesReceivableAccount() {
        val cash = account()
        val sourceAccount = account(AccountKind.RECEIVABLE)
        account(AccountKind.RECEIVABLE)
        val source = save(BillKind.DEBT_LEND, cash.id, sourceAccount.id, 10000)
        open(sourceAccount)
        compose.onNodeWithTag("asset_transactions").performScrollToNode(hasTestTag("asset_bill_$source"))
        compose.onNodeWithTag("asset_bill_$source").performClick()
        compose.onNodeWithTag("bill_debt_records").performScrollTo().performClick()
        compose.onNodeWithTag("debt_repay").performScrollTo().performClick()
        compose.onNodeWithTag("bill_from_account").performScrollTo().onChildren().filter(isNotEnabled()).assertCountEquals(1)
        compose.onNodeWithTag("bill_to_account").assertIsEnabled()
    }

    @Test fun creditValidationRevealsTheInvalidFieldFromTheBottomAndPreservesInput() {
        val account = credit()
        compose.onNodeWithTag("credit_statement").performTextReplacement("32")
        compose.onNodeWithTag("credit_limit").performScrollTo().performTextReplacement("1000")
        compose.onNodeWithTag("credit_save").performClick()
        compose.onNodeWithTag("credit_statement").assertIsFocused().assertIsDisplayed().assertTextContains("32")
        compose.onNodeWithTag("credit_statement_error", useUnmergedTree = true).assertIsDisplayed()
        assertNull(runBlocking { store.catalog.observeAccounts().first().single { it.id == account.id }.creditTerms })
        captureUiEvidence("c2-credit-error.png")
    }

    @Test fun adjustmentValidationRevealsAmountThenReasonWithoutChangingBalance() {
        val account = account()
        open(account)
        compose.onNodeWithTag("asset_adjust").performScrollTo().performClick()
        compose.onNodeWithTag("adjust_amount").performTextReplacement("0")
        compose.onNodeWithTag("adjust_reason").performScrollTo().performTextReplacement("Counted")
        compose.onNodeWithTag("adjust_confirm").performClick()
        compose.onNodeWithTag("adjust_amount").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("adjust_amount_error", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("adjust_amount").performTextReplacement("5")
        compose.onNodeWithTag("adjust_reason").performScrollTo().performTextClearance()
        compose.onNodeWithTag("adjust_confirm").performClick()
        compose.onNodeWithTag("adjust_reason").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("adjust_reason_error", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0L, runBlocking { store.catalog.observeAccounts().first().single { it.id == account.id }.balance.minor })
        captureUiEvidence("c2-adjust-error.png")
        assertAboveKeyboard("取消")
        compose.onNodeWithTag("adjust_cancel").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("adjust_reason").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun creditSwitchExposesItsLabelAndToggleStateTogether() {
        credit()
        compose.onNodeWithTag("credit_enabled").assertIsOn().assertTextContains("启用周期配置")
    }

    private fun assertAboveKeyboard(label: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            compose.waitUntil(5000) { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            val keyboard = Rect().also { bounds -> automation.windows.single { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.getBoundsInScreen(bounds) }
            val dialog = automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxBy { it.layer }
            val pending = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(dialog.root) }
            val controls = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (node.text?.toString() == label) controls.add(node)
                repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
            }
            assertTrue("Dialog control must remain accessible: $label", controls.isNotEmpty())
            val control = Rect().also { bounds -> controls.first().getBoundsInScreen(bounds) }
            assertTrue("$label must be above the IME: control=$control keyboard=$keyboard", control.top >= 0 && control.bottom <= keyboard.top)
        } finally { automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags } }
    }
}
