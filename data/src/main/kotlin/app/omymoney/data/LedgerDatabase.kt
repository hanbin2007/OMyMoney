package app.omymoney.data

import androidx.room.Database
import androidx.room.AutoMigration
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, AccountEntity::class, CategoryEntity::class, BillEntity::class,
        PostingEntity::class, SubmissionEntity::class, DeletionEntity::class, PreferenceEntity::class, DraftEntity::class, QueueEntity::class, ExchangeRateEntity::class, BudgetEntity::class,
        ScheduleEntity::class, ScheduleExecutionEntity::class, SavingsPlanEntity::class, SavingsDepositEntity::class,
        BatchActionEntity::class, ImportReceiptEntity::class],
    version = 10,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10)],
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
}
