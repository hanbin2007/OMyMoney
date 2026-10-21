package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.core.model.*
import app.omymoney.data.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExchangeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store

    @Test fun billExportKeepsTheAppliedFilterAcrossRecreation() {
        val book = Book(UUID.randomUUID().toString(), "Exchange ${UUID.randomUUID().toString().take(6)}")
        val account = AssetAccount(UUID.randomUUID().toString(), "Exchange cash ${UUID.randomUUID().toString().take(6)}")
        runBlocking {
            assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(account) is CatalogResult.Saved)
            listOf("Included row", "Other row").forEach { note ->
                assertTrue(store.ledger.save(TransactionDraft(book.id, kind = BillKind.EXPENSE,
                    amount = Money(1234, CurrencySpec.CNY), fromAccountId = account.id, occurredAt = Instant.parse("2026-10-11T12:00:00Z"),
                    timeSource = TimeSource.USER, note = note), null) is SaveResult.Saved)
            }
            store.catalog.selectBook(book.id)
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bill_search_action").performClick()
        compose.onNodeWithTag("bill_search").performTextReplacement("Included row")
        compose.onNodeWithTag("bill_more").performClick()
        compose.onNodeWithTag("bill_export").performClick()
        compose.onNodeWithTag("exchange_count").assertTextContains("1", substring = true)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("exchange_count").assertTextContains("1", substring = true)
        compose.onNodeWithTag("exchange_scope").assertTextContains("Included row", substring = true)
        compose.onNodeWithTag("exchange_export").performScrollTo().assertIsEnabled()
        captureUiEvidence("c9-exchange-filtered.png")
    }

    @Test fun settingsHasExplicitBookAndSeparateImportExportActions() {
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("catalog_settings").performScrollToNode(hasTestTag("settings_exchange"))
        compose.onNodeWithTag("settings_exchange").performScrollTo().performClick()
        compose.onNodeWithTag("exchange_book").assertExists()
        compose.onNodeWithTag("exchange_import").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("exchange_export").performScrollTo().assertIsEnabled()
    }
}
