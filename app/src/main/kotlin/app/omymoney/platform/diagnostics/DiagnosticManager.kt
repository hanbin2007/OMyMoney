package app.omymoney.platform.diagnostics

import android.content.Context
import android.app.ActivityManager
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import app.omymoney.platform.accessibility.AccessibilityStatus
import android.os.Build
import android.os.SystemClock
import app.omymoney.android.BuildConfig
import app.omymoney.core.diagnostics.*
import app.omymoney.core.recognition.RuleRegistry
import app.omymoney.platform.remote.HttpTransport
import app.omymoney.platform.remote.HttpFailure
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class DiagnosticState(
    val enabled: Boolean = false,
    val paired: Boolean = false,
    val extraPackages: Set<String> = emptySet(),
    val pendingBatches: Int = 0,
    val pendingBytes: Long = 0,
    val droppedRecords: Long = 0,
    val sentBatches: Long = 0,
    val uncleanSessions: Long = 0,
    val uploading: Boolean = false,
    val error: String? = null,
    val lastUpload: Long = 0,
    val interruptedSession: Boolean = false,
) { val packages: Set<String> get() = RuleRegistry.supportedPackages + extraPackages }

class DiagnosticManager(context: Context, private val transport: HttpTransport = HttpTransport(BuildConfig.SERVICE_ORIGIN)) : DiagnosticSink {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val interruptedDebugSession = preferences.getBoolean("debug_session_open", false)
    private val directory = File(context.noBackupFilesDir, "diagnostics")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sequence = AtomicLong()
    private val dropped = AtomicLong()
    private val lastAccessibilityEvent = AtomicLong()
    private val accessibilityEvents = AtomicLong()
    private val session = UUID.randomUUID().toString()
    private val uploadMutex = Mutex()
    private val settingsMutex = Mutex()
    private val pairMutex = Mutex()
    private val exitMutex = Mutex()
    private val credentialRevision = AtomicLong()
    private val mutable = MutableStateFlow(DiagnosticState(
        enabled = preferences.getBoolean("enabled", false) && !interruptedDebugSession,
        paired = preferences.getString("upload_credential", null) != null,
        extraPackages = preferences.getStringSet("extra_packages", emptySet()).orEmpty().toSet(),
        lastUpload = preferences.getLong("last_upload", 0),
        interruptedSession = interruptedDebugSession,
    ))
    val state = mutable.asStateFlow()
    override val enabled get() = state.value.enabled
    private sealed interface Message {
        data class Record(val record: JsonObject) : Message
        data class Flush(val result: CompletableDeferred<Unit>) : Message
    }
    private val messages = Channel<Message>(256)
    private val spool by lazy {
        DiagnosticSpool(directory).also { spool ->
            spool.append(buildJsonObject {
                put("kind", "process_session"); put("session", session); put("at", Instant.now().toString())
                put("versionCode", BuildConfig.VERSION_CODE); put("versionName", BuildConfig.VERSION_NAME)
                put("sdk", Build.VERSION.SDK_INT); put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
                put("previousUncleanSessions", spool.stats().uncleanSessions)
            })
            val crash = File(directory, "crash.json")
            if (crash.exists()) {
                try {
                    require(crash.length() <= 65536)
                    if (spool.append(Json.parseToJsonElement(crash.readText()).jsonObject)) crash.delete()
                } catch (_: Exception) { crash.delete(); spool.noteDropped(1) }
            }
        }
    }

    init {
        scope.launch {
            var lastSeal = SystemClock.elapsedRealtime()
            for (message in messages) {
                try {
                    val missing = dropped.getAndSet(0)
                    try { if (missing > 0) spool.noteDropped(missing) }
                    catch (error: Exception) { dropped.addAndGet(missing); throw error }
                    when (message) {
                        is Message.Record -> spool.append(message.record)
                        is Message.Flush -> spool.seal()
                    }
                    if (SystemClock.elapsedRealtime() - lastSeal >= 60000) {
                        spool.seal(); lastSeal = SystemClock.elapsedRealtime()
                        if (state.value.paired) DiagnosticWork.request(context)
                    }
                    refreshStats()
                    (message as? Message.Flush)?.result?.complete(Unit)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    mutable.update { it.copy(error = "storage") }
                    if (message is Message.Record) dropped.incrementAndGet()
                    (message as? Message.Flush)?.result?.completeExceptionally(error)
                }
            }
        }
        if (directory.exists()) scope.launch { runCatching { flush() } }
        if (state.value.paired) DiagnosticWork.schedule(context)
        if (interruptedDebugSession) {
            // A killed continuous session cannot silently resume ordinary capture.
            preferences.edit().putBoolean("enabled", false).remove("debug_session_open").commit()
            scope.launch {
                enqueue("debug_session_interrupted", buildJsonObject { put("recordingResumed", false) })
                recordExitHistory(sessionEnd = true)
                runCatching { flush() }
            }
        }
        if (state.value.enabled) {
            recordSafely("process_started", buildJsonObject { put("sdk", Build.VERSION.SDK_INT) })
            recordRuntime("process_started")
            scope.launch { recordExitHistory() }
        }
    }

    override fun record(kind: String, fields: JsonObject) {
        if (!state.value.enabled) return
        if (kind == "accessibility_event") { lastAccessibilityEvent.set(SystemClock.uptimeMillis()); accessibilityEvents.incrementAndGet() }
        enqueue(kind, fields)
    }

    private fun enqueue(kind: String, fields: JsonObject) {
        val record = buildJsonObject {
            put("kind", kind.take(80)); put("session", session); put("sequence", sequence.incrementAndGet())
            put("at", Instant.now().toString()); put("uptime", SystemClock.uptimeMillis())
            put("versionCode", BuildConfig.VERSION_CODE); put("fields", DiagnosticRedactor.clean(fields))
        }
        if (!messages.trySend(Message.Record(record)).isSuccess) dropped.incrementAndGet()
    }

    suspend fun setEnabled(enabled: Boolean) = settingsMutex.withLock {
        if (!enabled) stopRecordingNow()
        withContext(Dispatchers.IO) {
            val editor = preferences.edit().putBoolean("enabled", enabled)
            if (!enabled) editor.remove("debug_session_open")
            if (!editor.commit()) {
                mutable.update { it.copy(error = "storage") }
                error("settings_storage")
            }
            mutable.update { it.copy(enabled = enabled, error = null, interruptedSession = if (enabled) false else it.interruptedSession) }
            if (enabled) {
                recordSafely("recording_started", buildJsonObject { put("packages", JsonArray(state.value.packages.sorted().map(::JsonPrimitive))) })
                recordRuntime("recording_started")
                recordExitHistory()
            }
        }
    }

    internal fun stopRecordingNow() {
        recordSafely("recording_stopped")
        mutable.update { it.copy(enabled = false) }
    }

    internal fun markDebugSessionStarted() {
        check(preferences.edit().putBoolean("debug_session_open", true).commit()) { "settings_storage" }
    }

    suspend fun setExtraPackages(packages: Set<String>) = settingsMutex.withLock {
        require(packages.size <= 25 && packages.all {
            it.length <= 255 && it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) &&
                it != context.packageName && it != "com.android.systemui"
        }) { "invalid_packages" }
        withContext(Dispatchers.IO) {
            check(preferences.edit().putStringSet("extra_packages", packages.toSet()).commit())
            mutable.update { it.copy(extraPackages = packages.toSet()) }
            recordSafely("diagnostic_packages", buildJsonObject { put("packages", JsonArray(state.value.packages.sorted().map(::JsonPrimitive))) })
        }
    }

    suspend fun pair(code: String) = pairMutex.withLock {
        require(code.matches(Regex("[0-9a-fA-F]{16}"))) { "pair_format" }
        val revision = credentialRevision.get()
        val body = buildJsonObject { put("code", code.lowercase()) }.toString().toRequestBody(JSON)
        val response = Json.parseToJsonElement(transport.bytes("/v1/pair", body = body, maxBytes = 4096).toString(Charsets.UTF_8)).jsonObject
        val credential = response.getValue("token").jsonPrimitive.content
        val device = response.getValue("deviceId").jsonPrimitive.content
        require(credential.matches(Regex("[A-Za-z0-9_-]{43}")) && UUID.fromString(device).toString() == device)
        settingsMutex.withLock { withContext(Dispatchers.IO) {
            check(revision == credentialRevision.get()) { "pair_cancelled" }
            check(preferences.edit().putString("upload_credential", credential).putString("device_id", device).commit())
            mutable.update { it.copy(paired = true, error = null) }
            recordSafely("diagnostic_paired")
            DiagnosticWork.schedule(context)
            DiagnosticWork.request(context)
        } }
    }

    suspend fun disconnect() {
        recordSafely("diagnostic_disconnected")
        credentialRevision.incrementAndGet()
        transport.cancel()
        DiagnosticWork.cancel(context)
        settingsMutex.withLock { withContext(Dispatchers.IO) {
            check(preferences.edit().remove("upload_credential").remove("device_id").commit())
            mutable.update { it.copy(paired = false, uploading = false, error = null) }
        } }
    }

    suspend fun flush() {
        val result = CompletableDeferred<Unit>()
        messages.send(Message.Flush(result))
        result.await()
    }

    suspend fun upload(manual: Boolean = false): Boolean = uploadMutex.withLock {
        val credential = preferences.getString("upload_credential", null) ?: return@withLock true
        if (manual) recordRuntime("manual_upload")
        val started = SystemClock.elapsedRealtime()
        var confirmed = 0
        // Store only the latest transfer outcome; logging each worker run into its own
        // upload queue would generate an endless sequence of self-reporting batches.
        preferences.edit().putLong("upload_attempts", preferences.getLong("upload_attempts", 0) + 1)
            .putString("upload_outcome", "in_progress").putBoolean("upload_manual", manual).apply()
        mutable.update { it.copy(uploading = true, error = null) }
        try {
            flush()
            withContext(Dispatchers.IO) {
                repeat(16) {
                    currentCoroutineContext().ensureActive()
                    if (credential != preferences.getString("upload_credential", null)) return@withContext
                    val next = synchronized(spool) { spool.pending().firstOrNull()?.let { it to it.file.readBytes() } } ?: return@withContext
                    val (batch, payload) = next
                    val receipt = Json.parseToJsonElement(transport.bytes("/v1/batches/${batch.id}", credential,
                        payload.toRequestBody(GZIP), 4096, batch.sha256).toString(Charsets.UTF_8)).jsonObject
                    require(receipt["batchId"]?.jsonPrimitive?.content == batch.id && receipt["sha256"]?.jsonPrimitive?.content == batch.sha256) { "receipt_mismatch" }
                    spool.acknowledge(batch.id, batch.sha256)
                    confirmed++
                    val now = System.currentTimeMillis()
                    preferences.edit().putLong("last_upload", now).apply()
                    mutable.update { it.copy(lastUpload = now) }
                    refreshStats()
                }
            }
            preferences.edit().putString("upload_outcome", if (confirmed > 0) "confirmed" else "no_pending_or_cancelled").apply()
            true
        } catch (error: CancellationException) {
            preferences.edit().putString("upload_outcome", "cancelled").apply()
            throw error
        }
        catch (error: Exception) {
            if (credential != preferences.getString("upload_credential", null)) return@withLock true
            mutable.update { it.copy(error = if (error is HttpFailure) "http_${error.status}" else "upload_failed") }
            preferences.edit().putString("upload_outcome", state.value.error).apply()
            false
        } finally {
            preferences.edit().putInt("upload_confirmed", confirmed).putLong("upload_duration_ms", SystemClock.elapsedRealtime() - started).apply()
            mutable.update { it.copy(uploading = false) }
        }
    }

    fun recordRuntime(reason: String, foregroundSession: Boolean = false, appVisible: Boolean? = null) {
        if (!enabled) return
        try {
            val power = context.getSystemService(PowerManager::class.java)
            val process = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
            val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            val current = state.value
            val access = AccessibilityStatus.refreshAccess(context)
            val notificationCounts = app.omymoney.platform.notification.NotificationCounterStore.read(context)
            recordSafely("runtime_snapshot", buildJsonObject {
                put("reason", reason); put("foregroundSession", foregroundSession)
                appVisible?.let { put("appVisible", it) }
                put("accessibilityGranted", AccessibilityStatus.granted(context)); put("accessibilityConnected", access.available)
                access.systemEnabled?.let { put("accessibilitySettingEnabled", it) }
                put("accessibilitySettingReadable", access.systemEnabled != null); put("accessibilitySystemBound", access.systemBound)
                put("accessibilityCallbackConnected", access.connected)
                put("unlocked", AccessibilityStatus.unlocked(context)); put("captureError", AccessibilityStatus.runtime.value.error)
                put("notificationsAllowed", NotificationManagerCompat.from(context).areNotificationsEnabled())
                put("debugNotificationAllowed", DebugSessionService.notificationsAllowed(context))
                put("notificationAccessGranted", app.omymoney.platform.notification.NotificationCaptureStatus.granted(context))
                put("notificationListenerConnected", app.omymoney.platform.notification.NotificationCaptureStatus.runtime.value.connected)
                put("notificationCaptureError", app.omymoney.platform.notification.NotificationCaptureStatus.runtime.value.error)
                put("notificationCounts", buildJsonObject {
                    put("received", notificationCounts.received); put("candidates", notificationCounts.candidates)
                    put("filtered", notificationCounts.filtered); put("dropped", notificationCounts.dropped)
                    put("suppressed", notificationCounts.suppressed); put("failed", notificationCounts.failed)
                })
                put("powerSave", power.isPowerSaveMode); put("deviceIdle", power.isDeviceIdleMode)
                put("batteryOptimizationExempt", power.isIgnoringBatteryOptimizations(context.packageName))
                put("processImportance", process.importance); put("lowMemory", memory.lowMemory)
                put("pendingBatches", current.pendingBatches); put("pendingBytes", current.pendingBytes)
                put("droppedRecords", current.droppedRecords); put("sentBatches", current.sentBatches)
                put("paired", current.paired); put("uploading", current.uploading); put("storageOrUploadError", current.error)
                put("uploadAttempts", preferences.getLong("upload_attempts", 0)); put("uploadOutcome", preferences.getString("upload_outcome", "not_attempted"))
                put("uploadConfirmed", preferences.getInt("upload_confirmed", 0)); put("uploadDurationMs", preferences.getLong("upload_duration_ms", 0))
                put("uploadManual", preferences.getBoolean("upload_manual", false))
                put("accessibilityEventCount", accessibilityEvents.get()); put("lastAccessibilityEventUptime", lastAccessibilityEvent.get())
            })
        } catch (error: Exception) { recordSafely("runtime_snapshot_failed", DiagnosticRedactor.failure(error)) }
    }

    private suspend fun recordExitHistory(sessionEnd: Boolean = false) = exitMutex.withLock {
        if (!enabled && !sessionEnd) return@withLock
        fun evidence(kind: String, fields: JsonObject) {
            if (sessionEnd) enqueue(kind, fields) else recordSafely(kind, fields)
        }
        if (Build.VERSION.SDK_INT < 30) {
            evidence("process_exit_coverage", buildJsonObject { put("status", "unsupported_sdk") })
            return@withLock
        }
        try {
            val exits = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 5)
            // Repeat the bounded history on each session: never acknowledge an exit
            // before the spool is durable. Timestamp + reason identify duplicates.
            exits.sortedBy { it.timestamp }.forEach { exit -> evidence("process_exit", buildJsonObject {
                put("timestamp", exit.timestamp); put("reason", exit.reason); put("reasonName", EXIT_REASONS[exit.reason] ?: "UNRECOGNIZED"); put("status", exit.status)
                put("importance", exit.importance); put("pssKb", exit.pss); put("rssKb", exit.rss)
                // OS descriptions/traces can contain unrelated private data. Keep only numeric metadata.
            }) }
            evidence("process_exit_coverage", buildJsonObject {
                put("status", "queried"); put("returned", exits.size); put("limit", 5)
                put("absenceDoesNotProveCleanExit", true)
            })
        } catch (error: Exception) { evidence("process_exit_coverage", buildJsonObject {
            put("status", "unavailable"); put("failure", DiagnosticRedactor.failure(error))
        }) }
    }

    suspend fun export(output: OutputStream) {
        flush()
        withContext(Dispatchers.IO) {
            synchronized(spool) {
                ZipOutputStream(output).use { zip ->
                    spool.pending().forEach { batch ->
                        zip.putNextEntry(ZipEntry("${batch.id}.ndjson.gz")); batch.file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("coverage.json"))
                    zip.write(buildJsonObject {
                        val stats = spool.stats()
                        put("droppedRecords", stats.droppedRecords + dropped.get()); put("uncleanSessions", stats.uncleanSessions)
                        put("pendingBatches", stats.pendingBatches); put("sentBatches", stats.sentBatches)
                        put("scope", "Selected app accessibility, transaction notifications from supported apps and OMyMoney only; protected data and missing batches are not covered")
                    }.toString().toByteArray())
                    zip.closeEntry()
                }
            }
        }
    }

    fun recordCrash(error: Throwable) {
        if (!state.value.enabled) return
        try {
            directory.mkdirs()
            val data = DiagnosticRedactor.clean(buildJsonObject {
                put("kind", "uncaught_exception"); put("session", session); put("at", Instant.now().toString())
                put("fields", DiagnosticRedactor.failure(error))
            }).toString().toByteArray()
            if (data.size <= 65536) FileOutputStream(File(directory, "crash.json")).use { it.write(data); it.fd.sync() }
        } catch (_: Exception) { }
    }

    private fun refreshStats() {
        val stats = spool.stats()
        mutable.update { it.copy(pendingBatches = stats.pendingBatches, pendingBytes = stats.pendingBytes,
            droppedRecords = stats.droppedRecords + dropped.get(), sentBatches = stats.sentBatches, uncleanSessions = stats.uncleanSessions) }
    }
    companion object {
        private val EXIT_REASONS = mapOf(0 to "UNKNOWN", 1 to "EXIT_SELF", 2 to "SIGNALED", 3 to "LOW_MEMORY",
            4 to "CRASH", 5 to "CRASH_NATIVE", 6 to "ANR", 7 to "INITIALIZATION_FAILURE", 8 to "PERMISSION_CHANGE",
            9 to "EXCESSIVE_RESOURCE_USAGE", 10 to "USER_REQUESTED", 11 to "USER_STOPPED", 12 to "DEPENDENCY_DIED",
            13 to "OTHER", 14 to "FREEZER", 15 to "PACKAGE_STATE_CHANGE", 16 to "PACKAGE_UPDATED")
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val GZIP = "application/gzip".toMediaType()
    }
}
