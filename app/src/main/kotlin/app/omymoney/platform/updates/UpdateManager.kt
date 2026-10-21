package app.omymoney.platform.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import app.omymoney.android.BuildConfig
import app.omymoney.core.updates.ManifestVerifier
import app.omymoney.core.updates.UpdateRelease
import app.omymoney.platform.remote.HttpFailure
import app.omymoney.platform.remote.HttpTransport
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UpdateState(
    val release: UpdateRelease? = null,
    val phase: String = "idle",
    val downloaded: Long = 0,
    val lastCheck: Long = 0,
    val autoCheck: Boolean = true,
    val autoDownload: Boolean = false,
    val error: String? = null,
    val confirmation: Intent? = null,
) { val busy get() = phase in setOf("checking", "downloading", "verifying", "installing") }

class UpdateManager(context: Context,
    private val origin: String = BuildConfig.SERVICE_ORIGIN,
    private val transport: HttpTransport = HttpTransport(origin),
    private val publicKey: () -> PublicKey = { readUpdateKey(context) },
) {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val directory = File(context.noBackupFilesDir, "updates")
    private val downloader = ApkDownload(directory, transport)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var active: Job? = null
    private val mutable = MutableStateFlow(UpdateState(lastCheck = preferences.getLong("last_check", 0),
        autoCheck = preferences.getBoolean("auto_check", true), autoDownload = preferences.getBoolean("auto_download", false)))
    val state = mutable.asStateFlow()
    private val verifier by lazy { ManifestVerifier(publicKey(), origin, context.packageName, installedSigner()) }
    private val initialized = scope.async {
        mutex.withLock {
            val target = preferences.getLong("install_target", 0)
            if (target > 0 && installedVersion() >= target) {
                check(preferences.edit().remove("install_target").remove("install_session").remove("manifest").commit())
                directory.listFiles().orEmpty().forEach { it.delete() }
                mutable.update { it.copy(phase = "installed") }
            } else {
                val saved = preferences.getString("manifest", null)
                if (saved != null) runCatching { applyRelease(verifier.verify(saved, 0, Instant.now())) }
                if (target > 0) mutable.update { it.copy(phase = "interrupted") }
            }
        }
    }

    init { UpdateWork.schedule(context, state.value.autoCheck, state.value.autoDownload) }

    suspend fun settings(autoCheck: Boolean, autoDownload: Boolean) = withContext(Dispatchers.IO) {
        check(preferences.edit().putBoolean("auto_check", autoCheck).putBoolean("auto_download", autoDownload).commit())
        mutable.update { it.copy(autoCheck = autoCheck, autoDownload = autoDownload) }
        UpdateWork.schedule(context, autoCheck, autoDownload)
    }

    suspend fun check(): Boolean = operation("checking") {
        val envelope = transport.bytes("/updates/stable.json").toString(Charsets.UTF_8)
        val release = verifier.verify(envelope, 0, Instant.now())
        val now = System.currentTimeMillis()
        check(preferences.edit().putString("manifest", envelope).putLong("last_check", now).commit())
        applyRelease(release)
        mutable.update { it.copy(lastCheck = now) }
    }

    private fun applyRelease(release: UpdateRelease) {
        if (release.versionCode <= installedVersion()) {
            mutable.update { it.copy(release = null, phase = "current", downloaded = 0, error = null) }
            directory.listFiles().orEmpty().forEach { it.delete() }
        } else {
            val ready = File(directory, "${release.apkSha256}.apk")
            val valid = ready.exists() && runCatching { verifyApk(ready, release) }.isSuccess
            directory.listFiles().orEmpty().filter { it.name !in setOf("${release.apkSha256}.apk", "${release.apkSha256}.part") }.forEach { it.delete() }
            mutable.update { it.copy(release = release, phase = if (valid) "ready" else "available", error = null,
                downloaded = if (valid) release.apkBytes else downloader.partial(release).length()) }
        }
    }

    fun startDownload() { scope.launch { download() } }

    suspend fun download(): Boolean = operation("downloading") {
        val release = currentRelease()
        val apk = downloader.fetch(release) { bytes -> mutable.update { it.copy(downloaded = bytes) } }
        mutable.update { it.copy(phase = "verifying") }
        verifyApk(apk, release)
        mutable.update { it.copy(phase = "ready", downloaded = release.apkBytes) }
    }

    fun cancelDownload() { if (state.value.phase == "downloading") { active?.cancel(); transport.cancel() } }

    fun startInstall() { scope.launch { install() } }

    private suspend fun install(): Boolean = operation("installing") {
        check(context.packageManager.canRequestPackageInstalls()) { "install_permission" }
        val release = currentRelease()
        val apk = File(directory, "${release.apkSha256}.apk")
        verifyApk(apk, release)
        val installer = context.packageManager.packageInstaller
        val previous = preferences.getInt("install_session", -1)
        if (previous >= 0) runCatching { installer.abandonSession(previous) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName); setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }; session.fsync(output)
                }
                check(preferences.edit().putLong("install_target", release.versionCode).putInt("install_session", sessionId).commit())
                val callback = Intent(context, UpdateInstallReceiver::class.java).setAction("app.omymoney.UPDATE_RESULT")
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            preferences.edit().remove("install_target").remove("install_session").commit()
            throw error
        }
    }

    fun installResult(intent: Intent) {
        if (intent.action != "app.omymoney.UPDATE_RESULT" ||
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != preferences.getInt("install_session", -1)) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                mutable.update { it.copy(phase = if (confirmation != null) "confirmation" else "interrupted", confirmation = confirmation) }
            }
            PackageInstaller.STATUS_SUCCESS -> mutable.update { it.copy(phase = "awaiting_restart", confirmation = null) }
            else -> {
                preferences.edit().remove("install_target").remove("install_session").apply()
                mutable.update { it.copy(phase = "ready", confirmation = null, error = "install_cancelled") }
            }
        }
    }

    fun confirmationLaunched() { mutable.update { it.copy(phase = "interrupted", confirmation = null) } }

    private fun currentRelease(): UpdateRelease {
        val envelope = preferences.getString("manifest", null) ?: error("no_release")
        return verifier.verify(envelope, installedVersion(), Instant.now())
    }

    internal fun verifyApk(apk: File, release: UpdateRelease) {
        require(downloader.valid(apk, release)) { "apk_digest" }
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags()) ?: error("apk_parse")
        require(info.packageName == context.packageName && info.version() == release.versionCode && info.version() > installedVersion()) { "apk_identity" }
        require(signer(info) == installedSigner() && signer(info) == release.signerSha256) { "apk_signer" }
    }

    private fun installedVersion() = context.packageManager.getPackageInfo(context.packageName, 0).version()
    private fun installedSigner() = signer(context.packageManager.getPackageInfo(context.packageName, signatureFlags()))

    private suspend fun operation(phase: String, block: suspend () -> Unit): Boolean {
        initialized.await()
        return mutex.withLock {
            val previous = state.value
            active = currentCoroutineContext()[Job]
            mutable.update { it.copy(phase = phase, error = null, confirmation = null) }
            try { withContext(Dispatchers.IO) { block() }; true }
            catch (cancelled: CancellationException) {
                mutable.update { it.copy(phase = if (phase == "downloading") "paused" else previous.phase, error = previous.error) }
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(phase = "error", error = when {
                    error is HttpFailure -> "http_${error.status}"
                    error.message in setOf("disk_space", "install_permission") -> error.message
                    error is IllegalArgumentException -> "verification_failed"
                    else -> "request_failed"
                }) }; false
            } finally { active = null }
        }
    }
}

@Suppress("DEPRECATION")
internal fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
@Suppress("DEPRECATION")
internal fun PackageInfo.version(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
@Suppress("DEPRECATION")
internal fun signer(info: PackageInfo): String {
    val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    require(certificates?.size == 1) { "apk_signers" }
    return MessageDigest.getInstance("SHA-256").digest(certificates[0].toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun readUpdateKey(context: Context): PublicKey {
    val encoded = context.assets.open("update-manifest-public.pem").bufferedReader().use { it.readText() }
        .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replace(Regex("\\s"), "")
    return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
}
