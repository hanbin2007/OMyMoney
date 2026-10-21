package app.omymoney

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.platform.widget.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class WidgetConfigTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun firstLaunchWidgetCanChooseAndPersistItsDefaultBook() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("widgetFirstLaunch") == "true")
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as OMyMoneyApplication).container
        assertTrue("Dedicated first-launch fixture must start empty", runBlocking { container.store.catalog.observeBooks().first().isEmpty() })
        val host = AppWidgetHost(context, 43156)
        val id = host.allocateAppWidgetId()
        val manager = AppWidgetManager.getInstance(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        try { assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, LedgerWidgetProvider::class.java))) }
        finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        try {
            ActivityScenario.launchActivityForResult<WidgetConfigureActivity>(Intent(context, WidgetConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)).use { scenario ->
                val choice = SemanticsMatcher("widget book") { node -> node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("widget_book_") }
                compose.waitUntil(5_000) { compose.onAllNodes(choice).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodes(choice)[0].performClick()
                compose.waitForIdle(); captureUiEvidence("c8-widget-config.png")
                compose.onNodeWithTag("widget_config_save").assertIsEnabled().performClick()
                assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
                assertNotNull(WidgetBooks(context).read(id))
            }
        } finally { host.deleteAppWidgetId(id); WidgetBooks(context).remove(id) }
    }
}
