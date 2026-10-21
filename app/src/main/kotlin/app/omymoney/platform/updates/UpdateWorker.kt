package app.omymoney.platform.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import app.omymoney.OMyMoneyApplication
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as OMyMoneyApplication).container.updates
        if (!manager.state.value.autoCheck) return Result.success()
        if (!manager.check()) return Result.retry()
        if (manager.state.value.autoDownload && manager.state.value.release != null && manager.state.value.phase != "ready") {
            if (!manager.download()) return Result.retry()
        }
        return Result.success()
    }
}

object UpdateWork {
    fun schedule(context: Context, enabled: Boolean, download: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) { work.cancelUniqueWork("app-updates"); return }
        work.enqueueUniquePeriodicWork("app-updates", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (download) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build())
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as OMyMoneyApplication).container.updates.installResult(intent)
    }
}
