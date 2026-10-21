package app.omymoney

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class QueueIntentTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun consumedReviewRequestDoesNotReplayAfterReturningAndRecreating() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as OMyMoneyApplication).container.store
        val first = runBlocking { store.recognitionQueue.enqueue(RecognitionResult.Matched(
            store.catalog.initialDraft().copy(amount = Money(1122, CurrencySpec.CNY)), emptyList(), "intent-fixture")) }
        val second = runBlocking { store.recognitionQueue.enqueue(RecognitionResult.Matched(
            store.catalog.initialDraft().copy(amount = Money(3344, CurrencySpec.CNY)), emptyList(), "intent-fixture")) }
        fun intent(id: String) = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_QUEUE)
            .putExtra(MainActivity.EXTRA_REVIEW_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ActivityScenario.launch<MainActivity>(intent(first.id)).use { scenario ->
            waitFor("bill_amount")
            compose.onNodeWithTag("bill_amount").assertTextContains("11.22")
            compose.onNodeWithTag("screen_back").performClick()
            waitFor("nav_settings")
            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithTag("bill_amount").assertDoesNotExist()
            scenario.onActivity { it.startActivity(intent(second.id)) }
            waitFor("bill_amount")
            compose.onNodeWithTag("bill_amount").assertTextContains("33.44")
            scenario.recreate()
            waitFor("bill_amount")
            compose.onNodeWithTag("bill_amount").assertTextContains("33.44")
        }
    }
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
