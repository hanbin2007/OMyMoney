package app.omymoney.platform.overlay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.omymoney.android.R
import app.omymoney.core.model.TransactionDraft
import app.omymoney.feature.automation.applicationLabel
import app.omymoney.ui.components.LedgerIconButton
import app.omymoney.feature.bills.billKindLabel

@Composable
internal fun PaymentReceipt(draft: TransactionDraft, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val foreground = Color(0xFFF9FAFB)
    Surface(color = Color(0xFF202124), contentColor = foreground, shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().testTag("payment_receipt").semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(22.dp), tint = Color(0xFF8AD5AE))
            Column(Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClickLabel = stringResource(R.string.edit_bill), onClick = onEdit)
                .padding(horizontal = 10.dp, vertical = 2.dp), verticalArrangement = Arrangement.Center) {
                Text(billKindLabel(requireNotNull(draft.kind)) + stringResource(R.string.payment_recorded),
                    color = Color(0xFF8AD5AE), style = MaterialTheme.typography.labelMedium)
                BasicText(draft.amount?.let { "${it.currency.code} ${it.decimalText()}" }.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(color = foreground), maxLines = 3,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.titleMedium.fontSize, stepSize = 1.sp))
                Text(listOfNotNull(draft.sourcePackage?.let(::applicationLabel), draft.payment?.counterparty?.takeIf(String::isNotBlank)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            LedgerIconButton(Icons.Outlined.Edit, stringResource(R.string.edit_bill), onEdit, Modifier.testTag("receipt_edit"))
            LedgerIconButton(Icons.Outlined.Close, stringResource(R.string.payment_receipt_dismiss), onDismiss, Modifier.testTag("receipt_close"))
        }
    }
}
