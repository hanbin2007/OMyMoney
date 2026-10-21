package app.omymoney.platform.remote

import app.omymoney.core.updates.UpdateRelease
import app.omymoney.platform.updates.ApkDownload
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class ApkDownloadTest {
    private fun withServer(block: suspend (MockWebServer, HttpTransport) -> Unit) = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false); server.start()
            block(server, HttpTransport(server.url("/").toString(), OkHttpClient.Builder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()))
        }
    }
    private fun release(server: MockWebServer, bytes: ByteArray) = UpdateRelease(1, 3, "qa", "app.omymoney.android", "0".repeat(64),
        server.url("/updates/test.apk").toString(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        bytes.size.toLong(), "2026-10-12T00:00:00Z", "2026-11-12T00:00:00Z", "")

    @Test fun resumesOnlyExactRangeAndVerifiesFinalDigest() = withServer { server, transport ->
        val dir = Files.createTempDirectory("apk-test").toFile()
        try {
            val bytes = "signed apk payload".toByteArray()
            val release = release(server, bytes)
            val download = ApkDownload(dir, transport)
            download.partial(release).writeBytes(bytes.take(6).toByteArray())
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 6-17/18").setBody(" apk payload"))
            assertArrayEquals(bytes, download.fetch(release) {}.readBytes())
            assertEquals("bytes=6-", server.takeRequest().getHeader("Range"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun malformedRangeOversizeAndWrongHashNeverPublishAnApk() = withServer { server, transport ->
        val dir = Files.createTempDirectory("apk-test").toFile()
        try {
            val release = release(server, "good".toByteArray())
            val download = ApkDownload(dir, transport)
            download.partial(release).writeText("g")
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-2/4").setBody("ood"))
            try { download.fetch(release) {}; fail("bad range accepted") } catch (_: IllegalArgumentException) { }
            download.partial(release).delete()
            server.enqueue(MockResponse().setChunkedBody("oversized", 2))
            try { download.fetch(release) {}; fail("oversize accepted") } catch (_: IllegalArgumentException) { }
            download.partial(release).delete()
            server.enqueue(MockResponse().setBody("evil"))
            try { download.fetch(release) {}; fail("bad hash accepted") } catch (_: IllegalArgumentException) { }
            assertFalse(dir.listFiles().orEmpty().any { it.extension == "apk" })
        } finally { dir.deleteRecursively() }
    }
}
