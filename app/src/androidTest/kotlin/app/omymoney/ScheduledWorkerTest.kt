package app.omymoney

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import app.omymoney.core.model.*
import app.omymoney.core.schedule.ScheduleRule
import app.omymoney.data.*
import app.omymoney.platform.schedule.*
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScheduledWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store get() = (context as OMyMoneyApplication).container.store

    @Test fun periodicRegistrationIsUnique() {
        ScheduledBillWork.enqueue(context)
        ScheduledBillWork.enqueue(context)
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ScheduledBillWork.PERIODIC_NAME).get(5, TimeUnit.SECONDS)
        assertEquals(1, work.size)
    }

    @Test fun workerUsesTheIdempotentRepository() = runBlocking {
        val book = Book(UUID.randomUUID().toString(), "Worker", zoneId = ZoneOffset.UTC)
        val cash = AssetAccount(UUID.randomUUID().toString(), "Worker cash")
        assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
        assertTrue(store.catalog.saveAccount(cash) is CatalogResult.Saved)
        val plan = ScheduledPlan(name = "Worker", template = TransactionDraft(book.id, kind = BillKind.EXPENSE,
            amount = Money(100, CurrencySpec.CNY), fromAccountId = cash.id),
            rule = ScheduleRule(LocalDate.now(ZoneOffset.UTC).minusDays(2), zone = ZoneOffset.UTC, count = 1))
        assertTrue(store.schedules.save(plan) is ScheduleSaveResult.Saved)
        repeat(2) { TestListenableWorkerBuilder<ScheduledBillWorker>(context).build().doWork() }
        assertEquals(1, store.schedules.observeExecutions(plan.id).first().size)
        assertEquals(0L, store.catalog.observeAccounts().first().single { it.id == cash.id }.balance.minor)
        assertEquals(ScheduleExecutionState.PENDING, store.schedules.observeExecutions(plan.id).first().single().state)
    }
}
