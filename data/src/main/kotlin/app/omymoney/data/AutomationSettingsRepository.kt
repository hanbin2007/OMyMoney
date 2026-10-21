package app.omymoney.data

import androidx.room.withTransaction
import app.omymoney.core.recognition.RuleRegistry
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable

@Serializable
data class AutomationSettings(val enabled: Boolean = false, val paused: Boolean = false, val packages: Set<String> = emptySet(),
    val dedupPresentation: DedupPresentation = DedupPresentation.CONFIRM) {
    fun permits(packageName: String) = enabled && !paused && packageName in packages && packageName in RuleRegistry.supportedPackages
}

@Serializable enum class DedupPresentation { IGNORE_PROMPT, CONFIRM, SKIP_SIMILARITY }

data class AutomationSettingsState(val settings: AutomationSettings = AutomationSettings(), val corrupt: Boolean = false)

class AutomationSettingsRepository(private val database: LedgerDatabase) {
    private val dao = database.ledgerDao()
    fun observe() = dao.observePreference(KEY).map(::decode).distinctUntilChanged()
    suspend fun read() = decode(dao.preference(KEY))
    suspend fun setEnabled(value: Boolean) = update { it.copy(enabled = value) }
    suspend fun setPaused(value: Boolean) = update { it.copy(paused = value) }
    suspend fun setDedupPresentation(value: DedupPresentation) = update { it.copy(dedupPresentation = value) }
    suspend fun setPackage(packageName: String, value: Boolean) {
        require(packageName in RuleRegistry.supportedPackages)
        update { it.copy(packages = if (value) it.packages + packageName else it.packages - packageName) }
    }
    private suspend fun update(change: (AutomationSettings) -> AutomationSettings) = database.withTransaction {
        val value = LedgerJson.format.encodeToString(change(read().settings))
        if (dao.preference(KEY) != value) dao.upsertPreference(PreferenceEntity(KEY, value))
    }
    companion object {
        const val KEY = "automation_settings"
        fun decode(value: String?): AutomationSettingsState = try {
            val settings = if (value == null) AutomationSettings() else {
                require(value.length <= 4096)
                LedgerJson.format.decodeFromString<AutomationSettings>(value).also {
                    require(it.packages.all { name -> name in RuleRegistry.supportedPackages })
                }
            }
            AutomationSettingsState(settings)
        } catch (_: IllegalArgumentException) { AutomationSettingsState(corrupt = true) }
    }
}
