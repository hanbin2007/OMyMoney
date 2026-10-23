package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.core.app.LocaleManagerCompat
import androidx.appcompat.app.AppCompatDelegate
import app.omymoney.android.R
import app.omymoney.core.model.Book
import app.omymoney.platform.widget.LedgerWidgets
import app.omymoney.platform.widget.WidgetSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AppLanguageTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store

    @Test fun lastLanguageRemainsReachableInCompactWindow() {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("compactLocale") == "true")
        val previous = AppCompatDelegate.getApplicationLocales()
        try {
            waitFor("nav_settings")
            compose.onNodeWithTag("nav_settings").performClick()
            waitFor("catalog_settings")
            compose.onNodeWithTag("catalog_settings").performScrollToNode(hasTestTag("settings_preferences"))
            compose.onNodeWithTag("settings_preferences").performClick()
            compose.onNodeWithTag("preferences_screen").performScrollToNode(hasTestTag("app_language"))
            compose.onNodeWithTag("app_language").performClick()
            compose.onNodeWithTag("language_en").performScrollTo().assertIsDisplayed()
            captureUiEvidence("app-language-compact-picker.png")
            compose.onNodeWithTag("language_en").performClick()
            compose.waitUntil(10_000) { AppCompatDelegate.getApplicationLocales().toLanguageTags() == "en" }
        } finally {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(previous) }
        }
    }

    @Test fun languageChoiceSurvivesRecreationWithoutTranslatingRawDraftInput() {
        val previous = AppCompatDelegate.getApplicationLocales()
        val book = Book(UUID.randomUUID().toString(), "Language QA 自訂帳本")
        val note = "午饭 Lunch 繁體原文"
        try {
            runBlocking { store.catalog.ensureLocalDefaults(); store.catalog.saveBook(book) }
            waitFor("nav_settings")
            compose.onNodeWithTag("nav_settings").performClick()
            waitFor("catalog_settings")
            compose.onNodeWithTag("catalog_settings").performScrollToNode(hasTestTag("settings_preferences"))
            compose.onNodeWithTag("settings_preferences").performClick()
            compose.onNodeWithTag("app_language").assertExists().performClick()
            compose.onNodeWithTag("language_zh-Hans").performClick()
            waitForLanguage("设置")
            assertEquals("设置", compose.activity.getString(R.string.nav_settings))
            compose.onNodeWithTag("screen_back").performClick()
            compose.onNodeWithTag("nav_bills").performClick()
            compose.onNodeWithTag("add_bill").performClick()
            waitFor("bill_amount")
            compose.onNodeWithTag("bill_amount").performTextInput("7..25")
            compose.onNodeWithTag("bill_note").performScrollTo().performTextInput(note)
            compose.onNodeWithTag("screen_back").performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.keep_draft)).performClick()
            waitFor("nav_settings")
            compose.onNodeWithTag("nav_settings").performClick()
            waitFor("catalog_settings")
            compose.onNodeWithTag("catalog_settings").performScrollToNode(hasTestTag("settings_preferences"))
            compose.onNodeWithTag("settings_preferences").performClick()
            choose("en", "Settings")
            assertEquals("Settings", compose.activity.getString(R.string.nav_settings))
            captureUiEvidence("app-language-en-settings.png")
            val application = compose.activity.applicationContext
            compose.waitUntil(10_000) { LocaleManagerCompat.getApplicationLocales(application).toLanguageTags() == "en" }
            assertEquals("Amounts hidden. Open the app to view.", WidgetSummary.hidden(application).status)
            compose.runOnUiThread {
                val widget = LedgerWidgets.views(application, 9876, book.id,
                    WidgetSummary(book.name, "2026-10", "CNY 12.30", "CNY 4.50"))
                    .apply(application, android.widget.FrameLayout(application))
                assertEquals("Income", widget.findViewById<android.widget.TextView>(R.id.widget_income_label).text.toString())
                assertEquals("Expense", widget.findViewById<android.widget.TextView>(R.id.widget_expense_label).text.toString())
                assertEquals("Open book", widget.findViewById<android.widget.TextView>(R.id.widget_open).text.toString())
            }
            compose.activityRule.scenario.recreate()
            waitFor("app_language")
            assertEquals("Settings", compose.activity.getString(R.string.nav_settings))
            compose.onNodeWithTag("app_language").performClick()
            compose.onNodeWithTag("language_en").assertIsSelected()
            captureUiEvidence("app-language-en-picker.png")
            compose.onNodeWithTag("language_zh-Hant").performClick()
            waitForLanguage("設定")
            assertEquals("設定", compose.activity.getString(R.string.nav_settings))
            compose.onNodeWithTag("screen_back").performClick()
            compose.onNodeWithTag("nav_bills").performClick()
            compose.onNodeWithText(book.name).assertExists()
            compose.onNodeWithTag("add_bill").performClick()
            waitFor("bill_amount")
            compose.onNodeWithTag("bill_amount").assertTextContains("7..25")
            compose.onNodeWithTag("bill_note").assertTextContains(note)
            compose.onNodeWithTag("screen_back").performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.discard_draft)).performClick()
            waitFor("nav_settings")
            compose.onNodeWithTag("nav_settings").performClick()
            waitFor("catalog_settings")
            compose.onNodeWithTag("catalog_settings").performScrollToNode(hasTestTag("settings_preferences"))
            compose.onNodeWithTag("settings_preferences").performClick()
            compose.onNodeWithTag("app_language").performClick()
            compose.onNodeWithTag("language_system").performClick()
            compose.waitUntil(10_000) { AppCompatDelegate.getApplicationLocales().isEmpty }
            compose.activityRule.scenario.recreate()
            waitFor("app_language")
            compose.onNodeWithTag("app_language").performClick()
            compose.onNodeWithTag("language_system").assertIsSelected()
            assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
        } finally {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(previous) }
            compose.waitUntil(10_000) { LocaleManagerCompat.getApplicationLocales(compose.activity.applicationContext) == previous }
        }
    }

    private fun choose(tag: String, settingsLabel: String) {
        compose.onNodeWithTag("app_language").performClick()
        compose.onNodeWithTag("language_$tag").performClick()
        waitForLanguage(settingsLabel)
    }

    private fun waitForLanguage(settingsLabel: String) {
        compose.waitUntil(10_000) {
            runCatching { compose.activity.getString(R.string.nav_settings) == settingsLabel }.getOrDefault(false)
        }
        waitFor("app_language")
        compose.waitForIdle()
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
