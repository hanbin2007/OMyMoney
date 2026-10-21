package app.omymoney.platform.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.omymoney.MainActivity
import java.util.UUID

data class LaunchRequest(val token: String, val bookId: String?, val newBill: Boolean) {
    companion object {
        const val ACTION_BOOK = "app.omymoney.OPEN_BOOK"
        const val ACTION_NEW_BILL = "app.omymoney.NEW_BILL"
        const val EXTRA_BOOK_ID = "book_id"
        fun from(intent: Intent?): LaunchRequest? {
            if (intent?.action !in setOf(ACTION_BOOK, ACTION_NEW_BILL)) return null
            val book = runCatching { intent?.getStringExtra(EXTRA_BOOK_ID) }.getOrElse { return null }
            if (book != null && !canonicalId(book)) return null
            return LaunchRequest(UUID.randomUUID().toString(), book, intent?.action == ACTION_NEW_BILL)
        }
        fun intent(context: Context, bookId: String?, newBill: Boolean) = Intent(context, MainActivity::class.java)
            .setAction(if (newBill) ACTION_NEW_BILL else ACTION_BOOK)
            .setData(Uri.parse("omymoney://local/${if (newBill) "new" else "book"}/${bookId ?: "current"}"))
            .putExtra(EXTRA_BOOK_ID, bookId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        fun canonicalId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
