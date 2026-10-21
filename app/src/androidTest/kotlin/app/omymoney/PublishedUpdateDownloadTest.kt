package app.omymoney

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.platform.updates.version
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: the previously delivered client must read and download the actual published update. */
class PublishedUpdateDownloadTest {
    @Test fun installedPreviousClientChecksAndVerifiesThePublicHttpsApk(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("publishedUpdateQa") == "true")
        val application = ApplicationProvider.getApplicationContext<OMyMoneyApplication>()
        val expected = requireNotNull(arguments.getString("publishedVersion")).toLong()
        assertTrue(application.packageManager.getPackageInfo(application.packageName, 0).version() < expected)
        val manager = application.container.updates
        manager.settings(false, false)
        assertTrue("Public signed manifest must pass the previous client's verifier", manager.check())
        assertEquals(expected, manager.state.value.release?.versionCode)
        assertTrue("Published APK must pass hash, package, version and same-signer verification", withTimeout(600_000) { manager.download() })
        assertEquals("ready", manager.state.value.phase)
        assertEquals(manager.state.value.release?.apkBytes, manager.state.value.downloaded)
    }
}
