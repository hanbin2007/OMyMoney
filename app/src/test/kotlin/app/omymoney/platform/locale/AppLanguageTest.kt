package app.omymoney.platform.locale

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun explicitScriptsTakePriorityOverRegionAndEmptyMeansSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags(""))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en-US"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromLanguageTags("zh-Hant-CN"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromLanguageTags("zh-Hans-HK"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromLanguageTags("zh-TW"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromLanguageTags("zh-HK"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromLanguageTags("zh-MO"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromLanguageTags("zh-CN"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en,zh-Hant"))
    }
}
