package app.omymoney.platform.security

import android.content.Context
import android.content.SharedPreferences
import app.omymoney.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PrivacyOptions(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val secureScreens: Boolean = false,
    val lockEnabled: Boolean = false,
)

class PrivacyPreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("local-privacy", Context.MODE_PRIVATE))
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private fun read() = PrivacyOptions(
        theme = runCatching { ThemeMode.valueOf(preferences.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = preferences.getBoolean("dynamic", true),
        secureScreens = preferences.getBoolean("secure", false),
        lockEnabled = preferences.getBoolean("lock", false),
    )
    private fun save(edit: SharedPreferences.Editor.() -> Unit): Boolean {
        val saved = preferences.edit().apply(edit).commit()
        if (saved) mutable.value = read()
        return saved
    }
    fun setTheme(value: ThemeMode) = save { putString("theme", value.name) }
    fun setDynamicColor(value: Boolean) = save { putBoolean("dynamic", value) }
    fun setSecureScreens(value: Boolean) = save { putBoolean("secure", value) }
    internal fun setLockEnabled(value: Boolean) = save { putBoolean("lock", value) }
}
