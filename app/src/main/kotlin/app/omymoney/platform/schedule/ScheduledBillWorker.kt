package app.omymoney.platform.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import app.omymoney.OMyMoneyApplication
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class ScheduledBillWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val store = (applicationContext as OMyMoneyApplication).container.store
        if (store.schedules.executeDue(Instant.now()).moreDue) Result.retry() else Result.success()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { Result.retry() }
}

object ScheduledBillWork {
    const val PERIODIC_NAME = "scheduled-bills-periodic"
    fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME,
        ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ScheduledBillWorker>(15, TimeUnit.MINUTES).build())
    fun requestCheck(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("scheduled-bills-catch-up",
        ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ScheduledBillWorker>().build())
}
