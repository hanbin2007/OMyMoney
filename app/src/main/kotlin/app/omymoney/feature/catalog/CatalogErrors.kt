package app.omymoney.feature.catalog

import android.content.Context
import app.omymoney.android.R
import app.omymoney.data.CatalogResult

fun catalogErrorMessage(context: Context, result: CatalogResult<*>): String =
    catalogErrorMessage(context, (result as? CatalogResult.Invalid)?.fieldErrors?.firstOrNull()?.code ?: "storage")

fun catalogErrorMessage(context: Context, code: String): String = context.getString(when (code) {
    "name_required" -> R.string.error_name_required
    "invalid_name" -> R.string.error_name_invalid
    "duplicate_name" -> R.string.error_name_duplicate
    "invalid_amount" -> R.string.error_amount_invalid
    "overflow" -> R.string.error_overflow
    "currency_mismatch" -> R.string.error_currency_mismatch
    "currency_locked" -> R.string.error_currency_locked
    "opening_locked" -> R.string.error_opening_locked
    "account_kind_locked" -> R.string.asset_kind_locked
    "invalid_credit_terms" -> R.string.credit_invalid
    "invalid_last_four" -> R.string.error_last_four
    "too_many_aliases" -> R.string.error_aliases
    "invalid_parent" -> R.string.error_parent
    "category_kind", "category_kind_locked", "category_book_locked" -> R.string.error_category_kind
    "last_book" -> R.string.error_last_book
    "invalid_order" -> R.string.error_order
    "not_found", "unavailable_book", "unavailable_account", "unavailable_category" -> R.string.error_not_found
    else -> R.string.error_save
})
