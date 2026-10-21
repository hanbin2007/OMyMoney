package app.omymoney.feature.bills

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.omymoney.android.R
import app.omymoney.core.model.TransactionDraft
import app.omymoney.feature.automation.applicationLabel

@Composable
internal fun unassignedFundingLabel(draft: TransactionDraft, from: Boolean): String {
    val hint = if (from) draft.payment?.fromAccountHint else draft.payment?.toAccountHint
    return if (!hint.isNullOrBlank()) "$hint · ${stringResource(R.string.payment_account_unmatched)}"
    else listOfNotNull(draft.sourcePackage?.let(::applicationLabel), stringResource(R.string.payment_account_unknown)).joinToString(" · ")
}
