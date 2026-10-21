package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)

    @Test fun versionOneMigratesWithoutLosingTheLedger() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val context: Context = ApplicationProvider.getApplicationContext()
        val book = UUID.randomUUID().toString()
        val account = UUID.randomUUID().toString()
        val billId = UUID.randomUUID().toString()
        val time = Instant.parse("2026-10-11T10:00:00Z")
        val draft = TransactionDraft(bookId = book, kind = BillKind.EXPENSE, amount = Money(1230, CurrencySpec.CNY),
            fromAccountId = account, occurredAt = time, timeSource = TimeSource.USER, zoneId = ZoneOffset.UTC, note = "旧版本账单")
        val bill = draft.recorded(billId, 1, time)
        try {
            helper.createDatabase(name, 1).apply {
                execSQL("INSERT INTO books VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf(book, "旧账本", "CNY", 2, "UTC", 0, 0))
                execSQL("INSERT INTO accounts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(account, "现金", "CNY", 2, 10000, 8770, "CASH", null, 0, 0, null, "[]"))
                execSQL("INSERT INTO bills VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(billId, 1, book, "EXPENSE", time.epochSecond, 0, account, null, null, bill.note, LedgerJson.encodeBill(bill), null))
                execSQL("INSERT INTO postings VALUES (?, ?, ?, ?, ?, ?)", arrayOf(billId, 0, account, -1230, "CNY", 2))
                execSQL("INSERT INTO submissions VALUES (?, ?, ?, ?)", arrayOf(draft.submissionId, billId, 1, LedgerJson.requestHash(draft, null)))
                close()
            }
            helper.runMigrationsAndValidate(name, 10, true).close()
            val db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
            try {
                val catalog = CatalogRepository(db)
                assertEquals(book, catalog.ensureLocalDefaults())
                assertEquals(book, catalog.initialDraft().bookId)
                assertEquals(8770L, catalog.observeAccounts().first().single().balance.minor)
                assertEquals(null, catalog.observeAccounts().first().single().creditTerms)
                assertEquals(bill, RoomLedgerRepository(db).observeBills(BillQuery(bookId = book)).first().single())
                assertEquals(emptyList<QueueEntry>(), RecognitionQueue(db).entries())
                assertEquals(emptyList<ManualExchangeRate>(), catalog.observeExchangeRates().first())
                assertEquals(emptyList<BudgetEntity>(), db.ledgerDao().allBudgets())
                assertEquals(emptyList<ScheduleEntity>(), db.ledgerDao().allSchedules())
                assertEquals(emptyList<ScheduleExecutionEntity>(), db.ledgerDao().allScheduleExecutions())
                assertEquals(emptyList<SavingsPlanEntity>(), db.ledgerDao().allSavingsPlans())
                assertEquals(emptyList<SavingsDepositEntity>(), db.ledgerDao().allSavingsDeposits())
                assertEquals(emptyList<ImportReceiptEntity>(), db.ledgerDao().allImportReceipts())
                assertEquals(emptyList<BatchActionEntity>(), db.ledgerDao().allBatchActions())
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
