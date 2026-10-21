@file:kotlinx.serialization.UseSerializers(ZoneIdSerializer::class)

package app.omymoney.core.model

import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val id: String,
    val name: String,
    val currency: CurrencySpec = CurrencySpec.CNY,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)

@Serializable
data class Category(
    val id: String,
    val bookId: String,
    val name: String,
    val kind: BillKind,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)

@Serializable enum class AccountKind { CASH, DEBIT, CREDIT, WALLET, INVESTMENT, OTHER, LIABILITY, RECEIVABLE }

@Serializable
data class AssetAccount(
    val id: String,
    val name: String,
    val currency: CurrencySpec = CurrencySpec.CNY,
    val openingBalance: Money = Money(0, currency),
    val balance: Money = openingBalance,
    val kind: AccountKind = AccountKind.CASH,
    val group: String? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val cardLastFour: String? = null,
    val aliases: List<String> = emptyList(),
    val creditTerms: CreditTerms? = null,
)
