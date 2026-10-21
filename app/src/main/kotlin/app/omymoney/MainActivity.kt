package app.omymoney

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.omymoney.ui.OMyMoneyApp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import app.omymoney.platform.security.LaunchRequest

class MainActivity : ComponentActivity() {
    private var queueRequest by mutableIntStateOf(0)
    private var diagnosticsRequest by mutableIntStateOf(0)
    private var reviewId by mutableStateOf<String?>(null)
    private var launchRequest by mutableStateOf<LaunchRequest?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) acceptRequest(intent) else {
            queueRequest = savedInstanceState.getInt("queueRequest")
            diagnosticsRequest = savedInstanceState.getInt("diagnosticsRequest")
            reviewId = savedInstanceState.getString("reviewId")
            savedInstanceState.getString("launchToken")?.let { token -> launchRequest = LaunchRequest(token,
                savedInstanceState.getString("launchBook"), savedInstanceState.getBoolean("launchNew")) }
        }
        setContent { OMyMoneyApp(queueRequest = queueRequest, reviewId = reviewId,
            onQueueConsumed = { queueRequest = 0; reviewId = null }, launchRequest = launchRequest, onLaunchConsumed = { launchRequest = null },
            diagnosticsRequest = diagnosticsRequest, onDiagnosticsConsumed = { diagnosticsRequest = 0 }) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptRequest(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("queueRequest", queueRequest)
        outState.putInt("diagnosticsRequest", diagnosticsRequest)
        outState.putString("reviewId", reviewId)
        launchRequest?.let { outState.putString("launchToken", it.token); outState.putString("launchBook", it.bookId); outState.putBoolean("launchNew", it.newBill) }
        super.onSaveInstanceState(outState)
    }
    private fun acceptRequest(intent: Intent?) {
        if (intent?.action == ACTION_DIAGNOSTICS) {
            diagnosticsRequest++; queueRequest = 0; reviewId = null; launchRequest = null; return
        }
        diagnosticsRequest = 0
        LaunchRequest.from(intent)?.let { launchRequest = it; queueRequest = 0; reviewId = null; return }
        if (intent?.action != ACTION_QUEUE) return
        launchRequest = null
        reviewId = runCatching { intent.getStringExtra(EXTRA_REVIEW_ID) }.getOrNull()?.takeIf { value ->
            runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false)
        }
        queueRequest++
    }
    companion object {
        const val ACTION_QUEUE = "app.omymoney.OPEN_RECOGNITION_QUEUE"
        const val ACTION_DIAGNOSTICS = "app.omymoney.OPEN_DIAGNOSTICS"
        const val EXTRA_REVIEW_ID = "recognition_entry_id"
    }
}
