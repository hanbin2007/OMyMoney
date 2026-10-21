package app.omymoney

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.core.model.AttachmentRef
import app.omymoney.data.AttachmentStore
import app.omymoney.feature.bills.AttachmentGallery
import app.omymoney.ui.theme.LedgerTheme
import app.omymoney.ui.theme.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AttachmentLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun missingImageCaptionDoesNotOverlapItsRemoveButtonAtLargeFont() {
        val id = UUID.randomUUID().toString()
        val ref = AttachmentRef(id, "attachments/$id.png", "image/png", "0".repeat(64))
        val store = AttachmentStore(ApplicationProvider.getApplicationContext())
        compose.setContent {
            LedgerTheme(ThemeMode.LIGHT, false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.width(328.dp)) { AttachmentGallery(listOf(ref), store, onRemove = {}) }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("attachment_missing_$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val caption = compose.onNodeWithTag("attachment_missing_$id", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val remove = compose.onNodeWithTag("remove_attachment_$id").getUnclippedBoundsInRoot()
        assertTrue("Caption $caption overlaps remove target $remove", caption.top >= remove.bottom || caption.bottom <= remove.top ||
            caption.right <= remove.left || caption.left >= remove.right)
    }
}
