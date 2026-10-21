package app.omymoney

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.updates.UpdateRelease
import app.omymoney.platform.remote.HttpTransport
import app.omymoney.platform.updates.ApkDownload
import java.io.File
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in diagnostic probe using the installed application's unchanged downloader. */
class LiveUpdateDownloadTest {
    @Test fun actualPublishedDownloadReachesACompleteVerifiedFile(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveUpdateDownload") == "true")
        val application = ApplicationProvider.getApplicationContext<OMyMoneyApplication>()
        val envelope = application.getSharedPreferences("app_updates", Context.MODE_PRIVATE).getString("manifest", null)!!
        val raw = Base64.getDecoder().decode(Json.parseToJsonElement(envelope).jsonObject.getValue("payload").jsonPrimitive.content)
        val release = Json.decodeFromString<UpdateRelease>(raw.toString(Charsets.UTF_8))
        val downloader = ApkDownload(File(application.noBackupFilesDir, "updates"), HttpTransport("https://omymoney.origenclub.cn:58952"))
        val started = Instant.now()
        val offset = downloader.partial(release).length()
        try {
            val apk = downloader.fetch(release) { }
            assertTrue(downloader.valid(apk, release))
        } catch (error: Exception) {
            throw AssertionError("Download started=$started offset=$offset now=${Instant.now()} partial=${downloader.partial(release).length()}", error)
        }
    }
}
