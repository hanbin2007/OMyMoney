package app.omymoney.data

import app.omymoney.core.model.Bill
import app.omymoney.core.model.TransactionDraft
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object LedgerJson {
    val format = Json { encodeDefaults = true }

    fun encodeBill(bill: Bill): String = format.encodeToString(bill)
    fun decodeBill(entity: BillEntity): Bill = format.decodeFromString(entity.payloadJson)
    fun requestHash(draft: TransactionDraft, revision: Long?): String =
        MessageDigest.getInstance("SHA-256")
            .digest((format.encodeToString(draft) + ":" + revision).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
