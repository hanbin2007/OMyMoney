package app.omymoney.platform.diagnostics

import android.content.Context
import androidx.work.*
import app.omymoney.OMyMoneyApplication
import java.util.concurrent.TimeUnit

class DiagnosticWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val diagnostics = (applicationContext as OMyMoneyApplication).container.diagnostics
        return if (diagnostics.upload()) Result.success() else Result.retry()
    }
}

object DiagnosticWork {
    private const val PERIODIC = "diagnostics-periodic"
    private const val FLUSH = "diagnostics-flush"
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DiagnosticWorker>(15, TimeUnit.MINUTES).setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun request(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(FLUSH, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DiagnosticWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(FLUSH)
    }
}
