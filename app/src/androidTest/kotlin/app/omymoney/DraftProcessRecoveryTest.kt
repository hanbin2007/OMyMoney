package app.omymoney

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.Book
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DraftProcessRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recoverRawDraftAfterProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("processPhase")
        assumeTrue("Run prepare and verify with a force-stop between them", phase in listOf("prepare", "verify"))
        val preferences = compose.activity.getSharedPreferences("qa-process-recovery", Context.MODE_PRIVATE)
        val store = (compose.activity.application as OMyMoneyApplication).container.store
        if (phase == "prepare") {
            val book = Book(UUID.randomUUID().toString(), "进程恢复 ${UUID.randomUUID().toString().take(6)}")
            runBlocking { store.catalog.ensureLocalDefaults(); store.catalog.saveBook(book) }
            assertTrue(preferences.edit().putString("bookName", book.name).commit())
        }
        val name = requireNotNull(preferences.getString("bookName", null))
        compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("add_bill").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("bill_amount").fetchSemanticsNodes().isNotEmpty() }
        if (phase == "prepare") {
            compose.onNodeWithTag("bill_amount").performTextInput("7..25")
            compose.onNodeWithTag("bill_note").performScrollTo().performTextInput("进程结束后仍保留的原始输入")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("草稿已保存到本机").fetchSemanticsNodes().isNotEmpty() }
        } else {
            compose.onNodeWithTag("bill_amount").assertTextContains("7..25")
            compose.onNodeWithTag("bill_note").assertTextContains("进程结束后仍保留的原始输入")
            compose.waitForIdle()
            captureUiEvidence("a6-process-recovered.png")
            compose.onNodeWithTag("screen_back").performClick()
            compose.onNodeWithText("放弃草稿").performClick()
            assertTrue(preferences.edit().clear().commit())
        }
    }
}
