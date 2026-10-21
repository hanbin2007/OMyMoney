package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.android.R
import app.omymoney.core.model.Book
import app.omymoney.platform.widget.WidgetConfigurationScreen
import app.omymoney.ui.theme.LedgerTheme
import app.omymoney.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class WidgetConfigurationUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun saveFailureAtTheEndOfALongBookListIsVisibleAndRetryRetainsSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val books = (1..30).map { Book(UUID.randomUUID().toString(), "Book $it") }
        var attempts = 0
        var saved: String? = null
        compose.setContent { LedgerTheme(ThemeMode.SYSTEM, false) {
            WidgetConfigurationScreen(books, null, false, false, {}, {}, { id ->
                attempts++
                (attempts > 1).also { if (it) saved = id }
            })
        } }
        compose.onNodeWithTag("widget_books").performScrollToNode(hasTestTag("widget_book_${books.last().id}"))
        compose.onNodeWithTag("widget_book_${books.last().id}").performClick()
        compose.onNodeWithTag("widget_config_save").performClick()
        compose.onNodeWithText(context.getString(R.string.privacy_storage)).assertIsDisplayed()
        compose.waitForIdle(); captureUiEvidence("c8-widget-save-error.png")
        compose.onNodeWithTag("widget_book_${books.last().id}").assertIsSelected()
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss) { it() }
        compose.onNodeWithTag("widget_config_save").performClick()
        compose.waitUntil { saved != null }
        assertEquals(books.last().id, saved)
    }
}
