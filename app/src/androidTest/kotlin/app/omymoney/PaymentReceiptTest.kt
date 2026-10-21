package app.omymoney

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.omymoney.core.model.*
import app.omymoney.platform.overlay.PaymentReceipt
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class PaymentReceiptTest {
    @get:Rule val compose = createComposeRule()
    @Test fun receiptExposesTransactionDirectionWithoutOpeningTheEditor() {
        val kind = mutableStateOf(BillKind.EXPENSE)
        compose.setContent { MaterialTheme {
            PaymentReceipt(TransactionDraft("book", kind = kind.value, amount = Money(1, CurrencySpec.CNY),
                sourcePackage = "com.eg.android.AlipayGphone", payment = PaymentDetails("zjy")), {}, {})
        } }
        for ((value, label) in listOf(BillKind.EXPENSE to "支出", BillKind.INCOME to "收入", BillKind.TRANSFER to "转账")) {
            compose.runOnUiThread { kind.value = value }
            compose.onNodeWithText(label, substring = true).assertIsDisplayed()
            compose.onNodeWithText("已记账", substring = true).assertIsDisplayed()
        }
    }
    @Test fun longAmountRemainsReadableAtTwoHundredPercentTextOnANarrowPhone() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { Box(Modifier.width(296.dp)) {
                    PaymentReceipt(TransactionDraft("book", kind = BillKind.EXPENSE, amount = Money(Long.MAX_VALUE, CurrencySpec.CNY),
                        sourcePackage = "com.eg.android.AlipayGphone", payment = PaymentDetails("zjy")), {}, {})
                } }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("CNY 92233720368547758.07", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val folder = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "qa").apply { mkdirs() }
        java.io.File(folder, "automatic-v5-capsule-large.png").outputStream().use {
            compose.onNodeWithTag("payment_receipt").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertFalse("The saved amount must not be clipped", layouts.single().hasVisualOverflow)
    }
}
