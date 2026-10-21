package app.omymoney.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LedgerDao {
    @Query("SELECT * FROM batch_actions WHERE bookId=:bookId ORDER BY expiresAt DESC, id") abstract fun observeBatchActions(bookId: String): Flow<List<BatchActionEntity>>
    @Query("SELECT * FROM batch_actions ORDER BY id") abstract suspend fun allBatchActions(): List<BatchActionEntity>
    @Query("SELECT * FROM batch_actions WHERE id=:id") abstract suspend fun batchAction(id: String): BatchActionEntity?
    @Upsert abstract suspend fun upsertBatchAction(action: BatchActionEntity)
    @Query("DELETE FROM batch_actions") abstract suspend fun clearBatchActions()
    @Query("SELECT * FROM import_receipts ORDER BY id") abstract suspend fun allImportReceipts(): List<ImportReceiptEntity>
    @Query("SELECT * FROM import_receipts WHERE id=:id") abstract suspend fun importReceipt(id: String): ImportReceiptEntity?
    @Insert abstract suspend fun insertImportReceipt(receipt: ImportReceiptEntity)
    @Query("DELETE FROM import_receipts") abstract suspend fun clearImportReceipts()
    @Query("SELECT * FROM savings_plans WHERE bookId=:bookId ORDER BY sortOrder, id") abstract fun observeSavingsPlans(bookId: String): Flow<List<SavingsPlanEntity>>
    @Query("SELECT * FROM savings_plans WHERE bookId=:bookId ORDER BY sortOrder, id") abstract suspend fun savingsPlans(bookId: String): List<SavingsPlanEntity>
    @Query("SELECT * FROM savings_plans ORDER BY id") abstract suspend fun allSavingsPlans(): List<SavingsPlanEntity>
    @Query("SELECT * FROM savings_plans WHERE id=:id") abstract suspend fun savingsPlan(id: String): SavingsPlanEntity?
    @Upsert abstract suspend fun upsertSavingsPlan(plan: SavingsPlanEntity)
    @Query("DELETE FROM savings_plans WHERE id=:id") abstract suspend fun removeSavingsPlan(id: String)
    @Query("DELETE FROM savings_plans") abstract suspend fun clearSavingsPlans()
    @Query("SELECT * FROM savings_deposits WHERE planId=:planId ORDER BY id") abstract fun observeSavingsDeposits(planId: String): Flow<List<SavingsDepositEntity>>
    @Query("SELECT d.* FROM savings_deposits d INNER JOIN savings_plans p ON p.id=d.planId WHERE p.bookId=:bookId ORDER BY d.id")
    abstract fun observeBookSavingsDeposits(bookId: String): Flow<List<SavingsDepositEntity>>
    @Query("SELECT * FROM savings_deposits WHERE planId=:planId ORDER BY id") abstract suspend fun savingsDeposits(planId: String): List<SavingsDepositEntity>
    @Query("SELECT * FROM savings_deposits WHERE id=:id") abstract suspend fun savingsDeposit(id: String): SavingsDepositEntity?
    @Query("SELECT * FROM savings_deposits WHERE linkedBillId=:billId") abstract suspend fun savingsDepositForBill(billId: String): SavingsDepositEntity?
    @Query("SELECT * FROM savings_deposits ORDER BY id") abstract suspend fun allSavingsDeposits(): List<SavingsDepositEntity>
    @Upsert abstract suspend fun upsertSavingsDeposit(deposit: SavingsDepositEntity)
    @Query("DELETE FROM savings_deposits WHERE id=:id") abstract suspend fun removeSavingsDeposit(id: String)
    @Query("DELETE FROM savings_deposits WHERE planId=:planId") abstract suspend fun removePlanSavingsDeposits(planId: String)
    @Query("DELETE FROM savings_deposits") abstract suspend fun clearSavingsDeposits()
    @Query("SELECT * FROM schedules WHERE bookId=:bookId ORDER BY id") abstract fun observeSchedules(bookId: String): Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM schedules ORDER BY id") abstract suspend fun allSchedules(): List<ScheduleEntity>
    @Query("SELECT * FROM schedules WHERE id=:id") abstract suspend fun schedule(id: String): ScheduleEntity?
    @Upsert abstract suspend fun upsertSchedule(plan: ScheduleEntity)
    @Query("DELETE FROM schedules") abstract suspend fun clearSchedules()
    @Query("SELECT * FROM schedule_executions WHERE planId=:planId ORDER BY plannedAt DESC, id") abstract fun observeScheduleExecutions(planId: String): Flow<List<ScheduleExecutionEntity>>
    @Query("SELECT * FROM schedule_executions ORDER BY id") abstract suspend fun allScheduleExecutions(): List<ScheduleExecutionEntity>
    @Query("SELECT * FROM schedule_executions WHERE id=:id") abstract suspend fun scheduleExecution(id: String): ScheduleExecutionEntity?
    @Upsert abstract suspend fun upsertScheduleExecution(execution: ScheduleExecutionEntity)
    @Query("DELETE FROM schedule_executions") abstract suspend fun clearScheduleExecutions()
    @Query("SELECT * FROM budgets WHERE bookId=:bookId AND periodKey=:periodKey") abstract fun observeBudget(bookId: String, periodKey: String): Flow<BudgetEntity?>
    @Query("SELECT * FROM budgets WHERE bookId=:bookId AND periodKey=:periodKey") abstract suspend fun budget(bookId: String, periodKey: String): BudgetEntity?
    @Query("SELECT * FROM budgets ORDER BY bookId, periodKey") abstract suspend fun allBudgets(): List<BudgetEntity>
    @Upsert abstract suspend fun upsertBudget(budget: BudgetEntity)
    @Query("DELETE FROM budgets") abstract suspend fun clearBudgets()
    @Query("SELECT * FROM exchange_rates ORDER BY sourceCode, sourceScale, targetCode, targetScale") abstract fun observeExchangeRates(): Flow<List<ExchangeRateEntity>>
    @Query("SELECT * FROM exchange_rates ORDER BY sourceCode, sourceScale, targetCode, targetScale") abstract suspend fun allExchangeRates(): List<ExchangeRateEntity>
    @Upsert abstract suspend fun upsertExchangeRate(rate: ExchangeRateEntity)
    @Query("DELETE FROM exchange_rates WHERE sourceCode=:source AND sourceScale=:sourceScale AND targetCode=:target AND targetScale=:targetScale")
    abstract suspend fun removeExchangeRate(source: String, sourceScale: Int, target: String, targetScale: Int)
    @Query("DELETE FROM exchange_rates") abstract suspend fun clearExchangeRates()
    @Query("SELECT * FROM recognition_queue ORDER BY createdAtSeconds, id") abstract suspend fun allQueueEntries(): List<QueueEntity>
    @Query("SELECT * FROM recognition_queue ORDER BY createdAtSeconds, id") abstract fun observeQueue(): Flow<List<QueueEntity>>
    @Query("SELECT * FROM recognition_queue WHERE id = :id") abstract suspend fun queueEntry(id: String): QueueEntity?
    @Upsert abstract suspend fun upsertQueueEntry(entry: QueueEntity)
    @Query("DELETE FROM recognition_queue") abstract suspend fun clearQueue()
    @Query("""SELECT * FROM bills WHERE deletedAt IS NULL AND bookId = :bookId AND
        (occurredAtSeconds BETWEEN :fromSeconds AND :toSeconds) ORDER BY occurredAtSeconds, id""")
    abstract suspend fun billsNear(bookId: String, fromSeconds: Long, toSeconds: Long): List<BillEntity>
    @Query("SELECT * FROM categories ORDER BY id") abstract suspend fun allCategories(): List<CategoryEntity>
    @Query("SELECT * FROM bills ORDER BY id") abstract suspend fun allBills(): List<BillEntity>
    @Query("SELECT * FROM postings ORDER BY billId, position") abstract suspend fun allPostings(): List<PostingEntity>
    @Query("SELECT * FROM submissions ORDER BY id") abstract suspend fun allSubmissions(): List<SubmissionEntity>
    @Query("SELECT * FROM deletions ORDER BY id") abstract suspend fun allDeletions(): List<DeletionEntity>
    @Query("SELECT * FROM preferences ORDER BY `key`") abstract suspend fun allPreferences(): List<PreferenceEntity>
    @Query("SELECT * FROM drafts ORDER BY `key`") abstract suspend fun allDrafts(): List<DraftEntity>
    @Query("DELETE FROM drafts") abstract suspend fun clearDrafts()
    @Query("DELETE FROM preferences") abstract suspend fun clearPreferences()
    @Query("DELETE FROM bills") abstract suspend fun clearBills()
    @Query("DELETE FROM categories WHERE parentId IS NOT NULL") abstract suspend fun clearChildCategories()
    @Query("DELETE FROM categories") abstract suspend fun clearCategories()
    @Query("DELETE FROM accounts") abstract suspend fun clearAccounts()
    @Query("DELETE FROM books") abstract suspend fun clearBooks()
    @Upsert abstract suspend fun upsertDraft(draft: DraftEntity)
    @Query("SELECT * FROM drafts WHERE `key` = :key") abstract suspend fun draft(key: String): DraftEntity?
    @Query("SELECT * FROM drafts WHERE bookId = :bookId ORDER BY `key`") abstract fun observeDrafts(bookId: String): Flow<List<DraftEntity>>
    @Query("DELETE FROM drafts WHERE `key` = :key") abstract suspend fun removeDraft(key: String)
    @Upsert abstract suspend fun upsertPreference(preference: PreferenceEntity)
    @Query("SELECT value FROM preferences WHERE `key` = :key") abstract suspend fun preference(key: String): String?
    @Query("DELETE FROM preferences WHERE `key` = :key") abstract suspend fun removePreference(key: String)
    @Query("SELECT value FROM preferences WHERE `key` = :key") abstract fun observePreference(key: String): Flow<String?>
    @Query("SELECT * FROM books ORDER BY sortOrder, name, id") abstract suspend fun books(): List<BookEntity>
    @Query("SELECT * FROM accounts ORDER BY sortOrder, name, id") abstract suspend fun accounts(): List<AccountEntity>
    @Query("SELECT * FROM categories WHERE bookId = :bookId ORDER BY sortOrder, name, id")
    abstract suspend fun categories(bookId: String): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE parentId = :parentId ORDER BY sortOrder, name, id")
    abstract suspend fun children(parentId: String): List<CategoryEntity>
    @Query("SELECT (SELECT COUNT(*) FROM bills WHERE categoryId = :id) + (SELECT COUNT(*) FROM schedules WHERE categoryId = :id)")
    protected abstract suspend fun currentCategoryReferenceCount(id: String): Int
    suspend fun categoryReferenceCount(id: String): Int = allBatchActions().fold(currentCategoryReferenceCount(id)) { count, row ->
        Math.addExact(count, row.model().before.count { it.categoryId == id })
    }
    @Query("SELECT * FROM books WHERE :includeArchived OR archived = 0 ORDER BY archived, sortOrder, name, id")
    abstract fun observeBooks(includeArchived: Boolean): Flow<List<BookEntity>>
    @Query("SELECT * FROM accounts WHERE :includeArchived OR archived = 0 ORDER BY archived, sortOrder, name, id")
    abstract fun observeAccounts(includeArchived: Boolean): Flow<List<AccountEntity>>
    @Query("""
        SELECT * FROM categories WHERE bookId = :bookId AND (:kind IS NULL OR kind = :kind)
        AND (:includeArchived OR archived = 0) ORDER BY sortOrder, name, id
    """)
    abstract fun observeCategories(bookId: String, kind: String?, includeArchived: Boolean): Flow<List<CategoryEntity>>
    @Upsert abstract suspend fun upsertBook(book: BookEntity)
    @Upsert abstract suspend fun upsertAccount(account: AccountEntity)
    @Upsert abstract suspend fun upsertCategory(category: CategoryEntity)
    @Upsert abstract suspend fun upsertBill(bill: BillEntity)
    @Insert abstract suspend fun insertPostings(postings: List<PostingEntity>)
    @Insert abstract suspend fun insertSubmission(submission: SubmissionEntity)
    @Upsert abstract suspend fun upsertDeletion(deletion: DeletionEntity)
    @Query("SELECT * FROM books WHERE id = :id") abstract suspend fun book(id: String): BookEntity?
    @Query("SELECT * FROM accounts WHERE id = :id") abstract suspend fun account(id: String): AccountEntity?
    @Query("SELECT * FROM categories WHERE id = :id") abstract suspend fun category(id: String): CategoryEntity?
    @Query("SELECT * FROM bills WHERE id = :id") abstract suspend fun bill(id: String): BillEntity?
    @Query("SELECT * FROM bills WHERE id = :id AND deletedAt IS NULL") abstract fun observeBill(id: String): Flow<BillEntity?>
    @Query("SELECT * FROM submissions WHERE id = :id") abstract suspend fun submission(id: String): SubmissionEntity?
    @Query("SELECT * FROM deletions WHERE id = :id") abstract suspend fun deletion(id: String): DeletionEntity?
    @Query("SELECT * FROM deletions WHERE billId = :billId AND used = 0 LIMIT 1")
    abstract suspend fun unusedDeletionFor(billId: String): DeletionEntity?
    @Query("SELECT * FROM postings WHERE billId = :billId ORDER BY position")
    abstract suspend fun postings(billId: String): List<PostingEntity>
    @Query("DELETE FROM postings WHERE billId = :billId") abstract suspend fun removePostings(billId: String)
    @Query("UPDATE accounts SET balanceMinor = :balance WHERE id = :id") abstract suspend fun updateBalance(id: String, balance: Long)
    @Query("UPDATE accounts SET archived = 1 WHERE id = :id") abstract suspend fun archiveAccount(id: String)
    @Query("DELETE FROM accounts WHERE id = :id") abstract suspend fun deleteAccount(id: String)
    @Query("SELECT (SELECT COUNT(*) FROM bills WHERE fromAccountId = :id OR toAccountId = :id) + (SELECT COUNT(*) FROM schedules WHERE fromAccountId = :id OR toAccountId = :id)")
    protected abstract suspend fun currentAccountReferenceCount(id: String): Int
    // Retained undo snapshots remain part of portable history even after use or expiry.
    suspend fun accountReferenceCount(id: String): Int = allBatchActions().fold(currentAccountReferenceCount(id)) { count, row ->
        Math.addExact(count, row.model().before.count { it.fromAccountId == id || it.toAccountId == id })
    }
    @Query("SELECT COUNT(*) FROM bills") abstract suspend fun billCount(): Int
    @Query("SELECT COUNT(*) FROM postings") abstract suspend fun postingCount(): Int
    @Query("SELECT COUNT(*) FROM submissions") abstract suspend fun submissionCount(): Int
    @Query("""
        SELECT * FROM bills WHERE deletedAt IS NULL
        AND (:bookId IS NULL OR bookId = :bookId)
        AND (:accountId IS NULL OR fromAccountId = :accountId OR toAccountId = :accountId)
        AND (:kind IS NULL OR kind = :kind)
        AND (:categoryId IS NULL OR categoryId = :categoryId)
        AND (:search = '' OR instr(lower(note), lower(:search)) > 0)
        ORDER BY occurredAtSeconds DESC, occurredAtNanos DESC, id DESC
    """)
    abstract fun observeBills(bookId: String?, accountId: String?, kind: String?, categoryId: String?, search: String): Flow<List<BillEntity>>

    @Transaction open suspend fun retireAccount(id: String) {
        if (accountReferenceCount(id) > 0) archiveAccount(id) else deleteAccount(id)
    }
}
