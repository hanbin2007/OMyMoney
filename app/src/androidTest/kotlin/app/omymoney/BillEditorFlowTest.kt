package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.core.model.*
import app.omymoney.data.EditorDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import android.view.KeyEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class BillEditorFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val book = Book(UUID.randomUUID().toString(), "表单 ${UUID.randomUUID().toString().take(6)}")
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store

    @Before fun prepare() {
        runBlocking { store.catalog.ensureLocalDefaults(); store.catalog.saveBook(book) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_switch").fetchSemanticsNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) } }
    }

    @Test fun saveExactAmountOnlyOnceAndReturnWithFeedback() {
        openEditor()
        compose.onNodeWithTag("bill_amount").performTextInput("12.30")
        compose.onNodeWithTag("bill_category").performScrollTo().performClick()
        compose.onNode(hasText("餐饮") and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)).performClick()
        compose.onNodeWithTag("bill_save").assertIsDisplayed().performClick()
        waitFor("add_bill")
        compose.onNodeWithText("已保存").assertIsDisplayed()
        val bills = runBlocking { store.ledger.observeBills(BillQuery(bookId = book.id)).first() }
        assertEquals(1, bills.size)
        assertEquals(1230L, bills.single().amount.minor)
        assertEquals(BillKind.EXPENSE, bills.single().kind)
    }

    @Test fun rotationPickerBackAndLeavingKeepTheSameDraft() {
        openEditor()
        compose.onNodeWithTag("bill_amount").performTextInput("23.45")
        compose.onNodeWithTag("bill_note").performScrollTo().performTextInput("午饭与饮料")
        compose.activityRule.scenario.recreate()
        waitFor("bill_amount")
        compose.onNodeWithTag("bill_amount").assertTextContains("23.45")
        compose.onNodeWithTag("bill_note").assertTextContains("午饭与饮料")
        compose.onNodeWithTag("bill_category").performScrollTo().performClick()
        closeSelectionSheet()
        compose.onNodeWithTag("bill_amount").assertTextContains("23.45")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithText("保留草稿").performClick()
        waitFor("add_bill")
        compose.onNodeWithTag("add_bill").performClick()
        waitFor("bill_amount")
        compose.onNodeWithTag("bill_amount").assertTextContains("23.45")
        compose.onNodeWithTag("bill_note").assertTextContains("午饭与饮料")
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithText("放弃草稿").performClick()
    }

    @Test fun systemBackDismissesKeyboardBeforeAskingToLeave() {
        openEditor()
        compose.onNodeWithTag("bill_amount").performClick().performTextInput("9.50")
        fun keyboardVisible() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        compose.waitUntil(10_000) { keyboardVisible() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { !keyboardVisible() }
        compose.onNodeWithText("离开记账？").assertDoesNotExist()
        compose.onNodeWithTag("bill_amount").assertTextContains("9.50")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("离开记账？").assertIsDisplayed()
        compose.onNodeWithText("放弃草稿").performClick()
    }

    @Test fun invalidAmountIsFocusedAndCorrectableWithKeyboardVisible() {
        openEditor()
        compose.onNodeWithTag("bill_note").performScrollTo().performTextInput("保留这段备注")
        compose.onNodeWithTag("bill_save").assertIsDisplayed().performClick()
        compose.onNodeWithText("请输入大于零的有效金额").assertIsDisplayed()
        compose.onNodeWithTag("bill_amount").assertIsFocused().performTextReplacement("6.789")
        compose.onNodeWithTag("bill_save").performClick()
        compose.onNodeWithText("请输入大于零的有效金额").assertIsDisplayed()
        compose.onNodeWithTag("bill_amount").performTextReplacement("6.78")
        compose.onNodeWithText("请输入大于零的有效金额").assertDoesNotExist()
        compose.onNodeWithTag("bill_save").assertIsDisplayed()
        compose.waitForIdle()
        captureUiEvidence("a6-editor-keyboard.png")
        compose.onNodeWithTag("bill_save").performClick()
        waitFor("add_bill")
        val bills = runBlocking { store.ledger.observeBills(BillQuery(bookId = book.id)).first() }
        assertEquals("保留这段备注", bills.single().note)
        assertEquals(678L, bills.single().amount.minor)
    }

    @Test fun archivedDraftReferencesAreLabelledButNotSelectableForANewBill() {
        val account = AssetAccount(UUID.randomUUID().toString(), "待归档账户 ${UUID.randomUUID().toString().take(6)}")
        val category = runBlocking {
            store.catalog.saveAccount(account)
            store.catalog.observeCategories(book.id, BillKind.EXPENSE).first().first()
        }
        runBlocking {
            val transaction = store.catalog.initialDraft(book.id).copy(fromAccountId = account.id, categoryId = category.id)
            store.drafts.write(EditorDraft("manual/${book.id}", transaction, amountText = "1.00", dirty = true))
            store.catalog.archiveAccount(account.id)
            store.catalog.archiveCategory(category.id)
        }
        openEditor()
        compose.onNodeWithTag("bill_from_account").assert(hasAnyDescendant(hasText("已归档", substring = true)))
        compose.onNodeWithTag("bill_from_account").performScrollTo().performClick()
        compose.onNodeWithTag("option_${account.id}").assertDoesNotExist()
        closeSelectionSheet()
        compose.onNodeWithTag("bill_category").assert(hasAnyDescendant(hasText("已归档", substring = true)))
        compose.onNodeWithTag("bill_category").performScrollTo().performClick()
        compose.onNodeWithTag("option_${category.id}").assertDoesNotExist()
        closeSelectionSheet()
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithText("放弃草稿").performClick()
    }

    @Test fun balanceOverflowHasAnAccurateErrorAndKeepsInput() {
        val account = AssetAccount(UUID.randomUUID().toString(), "大额账户 ${UUID.randomUUID().toString().take(6)}",
            openingBalance = Money(Long.MAX_VALUE, CurrencySpec.CNY), balance = Money(Long.MAX_VALUE, CurrencySpec.CNY))
        runBlocking {
            store.catalog.saveAccount(account)
            val transaction = store.catalog.initialDraft(book.id).copy(kind = BillKind.INCOME,
                fromAccountId = null, toAccountId = account.id, categoryId = null)
            store.drafts.write(EditorDraft("manual/${book.id}", transaction, amountText = "1.00", dirty = true))
        }
        openEditor()
        compose.onNodeWithTag("bill_save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("金额超出支持范围。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("金额超出支持范围。").assertIsDisplayed()
        compose.onNodeWithTag("bill_amount").assertTextContains("1.00")
        assertTrue(runBlocking { store.ledger.observeBills(BillQuery(bookId = book.id)).first().isEmpty() })
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithText("放弃草稿").performClick()
    }

    private fun openEditor() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("add_bill").performClick()
        waitFor("bill_amount")
    }
    private fun closeSelectionSheet() {
        compose.onNodeWithTag("selection_search").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        // Older system IMEs consume the first Back before the modal can dismiss.
        if (compose.onAllNodesWithTag("selection_search").fetchSemanticsNodes().isNotEmpty()) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        withFailureEvidence("failure-editor-selector") {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("selection_search").fetchSemanticsNodes().isEmpty() }
        }
    }
    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
