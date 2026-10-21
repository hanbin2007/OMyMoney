package app.omymoney.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "batch_actions", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("bookId")])
@Serializable data class BatchActionEntity(@PrimaryKey val id: String, val bookId: String, val expiresAt: String, val payloadJson: String)

@Entity(tableName = "import_receipts", foreignKeys = [
    ForeignKey(entity = BillEntity::class, parentColumns = ["id"], childColumns = ["billId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("billId")])
@Serializable data class ImportReceiptEntity(@PrimaryKey val id: String, val billId: String, val payloadJson: String)

@Entity(tableName = "savings_plans", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("bookId")])
@Serializable data class SavingsPlanEntity(@PrimaryKey val id: String, val bookId: String, val sortOrder: Int, val payloadJson: String)

@Entity(tableName = "savings_deposits", foreignKeys = [
    ForeignKey(entity = SavingsPlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = BillEntity::class, parentColumns = ["id"], childColumns = ["linkedBillId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("planId"), Index(value = ["linkedBillId"], unique = true)])
@Serializable data class SavingsDepositEntity(@PrimaryKey val id: String, val planId: String, val linkedBillId: String?, val payloadJson: String)

@Entity(tableName = "schedules", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["fromAccountId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["toAccountId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("bookId"), Index("fromAccountId"), Index("toAccountId"), Index("categoryId")])
@Serializable data class ScheduleEntity(@PrimaryKey val id: String, val bookId: String, val fromAccountId: String?,
    val toAccountId: String?, val categoryId: String?, val payloadJson: String)

@Entity(tableName = "schedule_executions", foreignKeys = [
    ForeignKey(entity = ScheduleEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["planId", "plannedAt"], unique = true)])
@Serializable data class ScheduleExecutionEntity(@PrimaryKey val id: String, val planId: String, val plannedAt: String,
    val state: String, val payloadJson: String)

@Entity(tableName = "budgets", primaryKeys = ["bookId", "periodKey"], foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.RESTRICT),
])
@Serializable data class BudgetEntity(val bookId: String, val periodKey: String, val payloadJson: String?)

@Entity(tableName = "exchange_rates", primaryKeys = ["sourceCode", "sourceScale", "targetCode", "targetScale"])
@Serializable data class ExchangeRateEntity(
    val sourceCode: String, val sourceScale: Int, val targetCode: String, val targetScale: Int,
    val rate: String, val asOf: String,
)

@Entity(tableName = "preferences")
@Serializable data class PreferenceEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "drafts", indices = [Index("bookId")])
@Serializable data class DraftEntity(@PrimaryKey val key: String, val bookId: String, val payloadJson: String)

@Entity(tableName = "recognition_queue", indices = [Index("state"), Index("createdAtSeconds")])
@Serializable data class QueueEntity(@PrimaryKey val id: String, val state: String, val createdAtSeconds: Long, val payloadJson: String)

@Entity(tableName = "books")
@Serializable data class BookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currencyCode: String = "CNY",
    val currencyScale: Int = 2,
    val zoneId: String = "UTC",
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)

@Entity(tableName = "accounts")
@Serializable data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currencyCode: String = "CNY",
    val currencyScale: Int = 2,
    val openingMinor: Long = 0,
    val balanceMinor: Long = 0,
    val kind: String = "CASH",
    val groupName: String? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val cardLastFour: String? = null,
    val aliasesJson: String = "[]",
    val creditTermsJson: String? = null,
)

@Entity(tableName = "categories", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("bookId"), Index("parentId")])
@Serializable data class CategoryEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val name: String,
    val kind: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)

@Entity(tableName = "bills", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["fromAccountId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["toAccountId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("bookId", "occurredAtSeconds"), Index("categoryId"), Index("fromAccountId"), Index("toAccountId")])
@Serializable data class BillEntity(
    @PrimaryKey val id: String,
    val revision: Long,
    val bookId: String,
    val kind: String,
    val occurredAtSeconds: Long,
    val occurredAtNanos: Int,
    val fromAccountId: String?,
    val toAccountId: String?,
    val categoryId: String?,
    val note: String,
    val payloadJson: String,
    val deletedAt: String? = null,
)

@Entity(tableName = "postings", primaryKeys = ["billId", "position"], foreignKeys = [
    ForeignKey(entity = BillEntity::class, parentColumns = ["id"], childColumns = ["billId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("accountId")])
@Serializable data class PostingEntity(
    val billId: String,
    val position: Int,
    val accountId: String,
    val minor: Long,
    val currencyCode: String,
    val currencyScale: Int,
)

@Entity(tableName = "submissions", foreignKeys = [
    ForeignKey(entity = BillEntity::class, parentColumns = ["id"], childColumns = ["billId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("billId")])
@Serializable data class SubmissionEntity(
    @PrimaryKey val id: String,
    val billId: String,
    val revision: Long,
    val requestHash: String,
)

@Entity(tableName = "deletions", foreignKeys = [
    ForeignKey(entity = BillEntity::class, parentColumns = ["id"], childColumns = ["billId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("billId")])
@Serializable data class DeletionEntity(
    @PrimaryKey val id: String,
    val billId: String,
    val deletedRevision: Long,
    val expiresAt: String,
    val used: Boolean = false,
)
