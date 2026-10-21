package app.omymoney.platform.security

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omymoney.android.R

@Composable
fun PrivacyGate(lock: PrivacyLock, content: @Composable () -> Unit) {
    val state by lock.state.collectAsState()
    val holder = rememberSaveableStateHolder()
    val activity = LocalActivity.current as ComponentActivity
    if (state.locked) Surface(Modifier.fillMaxSize().testTag("privacy_locked")) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(40.dp))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Button({ lock.requestUnlock(activity) }, Modifier.testTag("privacy_unlock"), enabled = !state.authenticating) {
                Text(stringResource(R.string.privacy_unlock))
            }
            state.error?.let { app.omymoney.ui.components.ErrorText(stringResource(lockErrorMessage(it))) }
        }
    } else holder.SaveableStateProvider("private-content") { content() }
}

fun lockErrorMessage(error: LockError): Int = when (error) {
    LockError.NO_CREDENTIAL -> R.string.privacy_no_credential
    LockError.CANCELLED -> R.string.privacy_cancelled
    LockError.FAILED -> R.string.privacy_failed
    LockError.STORAGE -> R.string.privacy_storage
}
