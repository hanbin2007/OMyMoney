package app.omymoney

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.omymoney.feature.settings.DiagnosticScreen
import app.omymoney.platform.diagnostics.DiagnosticManager
import app.omymoney.platform.remote.HttpTransport
import app.omymoney.ui.theme.LedgerTheme
import app.omymoney.ui.theme.ThemeMode
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DiagnosticControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sensitiveUploadCanBeStoppedWhileRequestIsInFlight() {
        val application = ApplicationProvider.getApplicationContext<OMyMoneyApplication>()
        val id = "diagnostic-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(application.cacheDir, id).apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = application.getSharedPreferences("$id-$name", mode)
        }
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false); server.start()
            val manager = DiagnosticManager(context, HttpTransport(server.url("/").toString(), OkHttpClient.Builder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()))
            server.enqueue(MockResponse().setBody("""{"deviceId":"12345678-1234-1234-1234-123456789abc","token":"${"x".repeat(43)}"}"""))
            runBlocking { manager.pair("1234567890abcdef"); manager.setEnabled(true) }
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(5, TimeUnit.SECONDS))
            compose.setContent { LedgerTheme(ThemeMode.LIGHT, false) { DiagnosticScreen(manager) {} } }
            try {
                compose.onNodeWithText("立即上传").performScrollTo().performClick()
                compose.waitUntil { manager.state.value.uploading }
                compose.onNodeWithText("停止上传并解除配对").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil { !manager.state.value.paired && !manager.state.value.uploading }
                assertTrue(manager.state.value.enabled)
            } finally { runBlocking { manager.disconnect(); manager.setEnabled(false) } }
        }
    }
}
