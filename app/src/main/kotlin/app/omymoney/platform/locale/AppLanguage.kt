package app.omymoney.platform.locale

import app.omymoney.android.R
import java.util.Locale

enum class AppLanguage(val tag: String, val label: Int) {
    SYSTEM("", R.string.language_system),
    SIMPLIFIED_CHINESE("zh-Hans", R.string.language_simplified),
    TRADITIONAL_CHINESE("zh-Hant", R.string.language_traditional),
    ENGLISH("en", R.string.language_english);

    companion object {
        fun fromLanguageTags(tags: String): AppLanguage {
            if (tags.isBlank()) return SYSTEM
            val locale = Locale.forLanguageTag(tags.substringBefore(','))
            return when (locale.language) {
                "en" -> ENGLISH
                "zh" -> if (locale.script == "Hant" || locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))
                    TRADITIONAL_CHINESE else SIMPLIFIED_CHINESE
                else -> SYSTEM
            }
        }
    }
}
