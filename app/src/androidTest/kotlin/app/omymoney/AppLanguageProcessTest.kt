package app.omymoney

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.android.R
import app.omymoney.core.model.Book
import app.omymoney.data.EditorDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AppLanguageProcessTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun selectedLanguageAndRawDraftSurviveProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("localeProcessPhase")
        assumeTrue("Run prepare and verify with a force-stop between them", phase in listOf("prepare", "verify"))
        val application = compose.activity.applicationContext
        val preferences = application.getSharedPreferences("qa-locale-process", Context.MODE_PRIVATE)
        val store = (application as OMyMoneyApplication).container.store
        val key = "locale-process-test"
        if (phase == "prepare") {
            assertTrue(preferences.edit().putString("previousTags", AppCompatDelegate.getApplicationLocales().toLanguageTags()).commit())
            runBlocking {
                store.catalog.ensureLocalDefaults()
                val book = Book(UUID.randomUUID().toString(), "Locale 自訂帳本")
                store.catalog.saveBook(book)
                store.drafts.write(EditorDraft(key, store.catalog.initialDraft().copy(bookId = book.id, note = "Lunch 午饭 繁體"),
                    amountText = "7..25", dirty = true))
            }
            waitFor("nav_settings")
            compose.onNodeWithTag("nav_settings").performClick()
            waitFor("catalog_settings")
            compose.onNodeWithTag("catalog_settings").performScrollToNode(hasTestTag("settings_preferences"))
            compose.onNodeWithTag("settings_preferences").performClick()
            compose.onNodeWithTag("app_language").performClick()
            compose.onNodeWithTag("language_en").performClick()
            compose.waitUntil(10_000) {
                LocaleManagerCompat.getApplicationLocales(application).toLanguageTags() == "en" &&
                    runCatching { compose.activity.getString(R.string.nav_settings) == "Settings" }.getOrDefault(false)
            }
        } else {
            try {
                waitFor("nav_settings")
                assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                assertEquals("Settings", compose.activity.getString(R.string.nav_settings))
                val draft = runBlocking { requireNotNull(store.drafts.read(key)) }
                assertEquals("7..25", draft.amountText)
                assertEquals("Lunch 午饭 繁體", draft.transaction.note)
            } finally {
                runBlocking { store.drafts.remove(key) }
                compose.runOnUiThread {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(preferences.getString("previousTags", "").orEmpty()))
                }
                assertTrue(preferences.edit().clear().commit())
            }
        }
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
