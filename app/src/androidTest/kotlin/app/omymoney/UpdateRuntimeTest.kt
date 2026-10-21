package app.omymoney

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import app.omymoney.android.BuildConfig
import app.omymoney.core.updates.UpdateRelease
import app.omymoney.platform.remote.HttpTransport
import app.omymoney.platform.updates.*
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class UpdateRuntimeTest {
    private val application = ApplicationProvider.getApplicationContext<OMyMoneyApplication>()
    private fun isolatedContext(): Context {
        val id = "update-test-${UUID.randomUUID()}"
        return object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(application.cacheDir, id).apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = application.getSharedPreferences("$id-$name", mode)
        }.also { it.getSharedPreferences("app_updates", 0).edit().putBoolean("auto_check", false).commit() }
    }

    @Test fun signedChecksRejectTamperingAndAnApkWithWrongVersionCannotInstall(): Unit = runBlocking {
        val context = isolatedContext()
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false); server.start()
            val origin = server.url("/").toString()
            val manager = UpdateManager(context, origin, HttpTransport(origin, OkHttpClient.Builder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())) { key.public }
            val apk = File(application.applicationInfo.sourceDir)
            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { input -> val buffer = ByteArray(32768); while (true) {
                val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
            } }
            val now = Instant.now()
            val release = UpdateRelease(1, BuildConfig.VERSION_CODE.toLong() + 1, "qa", application.packageName,
                signer(application.packageManager.getPackageInfo(application.packageName, signatureFlags())),
                server.url("/updates/test.apk").toString(), digest.digest().joinToString("") { "%02x".format(it) }, apk.length(), now.toString(), now.plusSeconds(3600).toString(), "QA only")
            val bytes = Json.encodeToString(release).toByteArray()
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(key.private); update(bytes) }.sign()
            val envelope = buildJsonObject { put("payload", Base64.getEncoder().encodeToString(bytes)); put("signature", Base64.getEncoder().encodeToString(signature)) }.toString()
            server.enqueue(MockResponse().setBody(envelope))
            assertTrue(manager.check())
            assertEquals("available", manager.state.value.phase)
            try { manager.verifyApk(apk, release); fail("old APK accepted as a new release") } catch (_: IllegalArgumentException) { }
            val checked = manager.state.value.lastCheck
            server.enqueue(MockResponse().setBody(envelope.replace("payload", "tampered")))
            assertFalse(manager.check())
            assertEquals(checked, manager.state.value.lastCheck)
            assertEquals("verification_failed", manager.state.value.error)
            manager.installResult(Intent("app.omymoney.UPDATE_RESULT").putExtra(PackageInstaller.EXTRA_SESSION_ID, 123)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
            assertEquals("error", manager.state.value.phase)
            server.enqueue(MockResponse().setBody(envelope).setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS))
            val checking = launch { manager.check() }
            withTimeout(5000) { manager.state.first { it.phase == "checking" } }
            checking.cancelAndJoin()
            assertEquals("Cancelling a check is not a paused APK download", "error", manager.state.value.phase)
        }
    }

    @Test fun restartConfirmsInstalledVersionNotMerelyTheSystemCallback(): Unit = runBlocking {
        val context = isolatedContext()
        val prefs = context.getSharedPreferences("app_updates", 0)
        prefs.edit().putLong("install_target", BuildConfig.VERSION_CODE.toLong()).putInt("install_session", 42).commit()
        val manager = UpdateManager(context)
        withTimeout(5000) { manager.state.first { it.phase == "installed" } }
        assertFalse(prefs.contains("install_target"))
        assertFalse(prefs.contains("install_session"))
    }
}
