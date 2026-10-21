package app.omymoney

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.android.R
import app.omymoney.core.model.Book
import app.omymoney.platform.security.LaunchRequest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class WidgetEntryTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun unavailableBookShowsAnErrorEvenWhenAnEditorWasPreviouslyOpen() = repeat(5) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as OMyMoneyApplication).container.store
        val suffix = UUID.randomUUID().toString().take(6)
        val open = Book(UUID.randomUUID().toString(), "Existing editor $suffix")
        val archived = Book(UUID.randomUUID().toString(), "Archived widget $suffix")
        runBlocking {
            store.catalog.ensureLocalDefaults(); store.catalog.saveBook(open); store.catalog.saveBook(archived)
            store.catalog.archiveBook(archived.id)
        }
        val initial = LaunchRequest.intent(context, open.id, true)
        ActivityScenario.launch<MainActivity>(initial).use { scenario ->
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("bill_amount").fetchSemanticsNodes().isNotEmpty() }
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it; it.startActivity(LaunchRequest.intent(it, archived.id, false)) }
            try {
                compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.widget_book_unavailable)).fetchSemanticsNodes().isNotEmpty() }
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.intent = initial }
            }
            compose.onNodeWithText(context.getString(R.string.widget_book_unavailable)).assertIsDisplayed()
        }
    }
}
