package app.omymoney.platform.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class HttpTransportTest {
    @Test fun cancellationWaitsForResponseConsumerToReleaseItsFiles(): Unit = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false); server.start()
            val transport = HttpTransport(server.url("/").toString(), OkHttpClient.Builder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
            server.enqueue(MockResponse().setBody("payload"))
            val consuming = CompletableDeferred<Unit>()
            val released = java.util.concurrent.atomic.AtomicBoolean(false)
            val request = launch(Dispatchers.Default) {
                transport.request("/download") {
                    consuming.complete(Unit)
                    try { Thread.sleep(300) } finally { released.set(true) }
                }
            }
            consuming.await()
            request.cancelAndJoin()
            assertTrue("Cancellation returned while the old download could still mutate its file", released.get())
        }
    }
    @Test fun redirectsNeverCarryTokensAndOversizedResponsesFailClosed(): Unit = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false); server.start()
            val transport = HttpTransport(server.url("/").toString().removeSuffix("/"), OkHttpClient.Builder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/secret")))
            try { transport.bytes("/v1/test", token = "test-token"); fail("redirect accepted") }
            catch (error: HttpFailure) { assertEquals(302, error.status) }
            assertEquals(1, server.requestCount)
            assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setChunkedBody("x".repeat(2048), 100))
            try { transport.bytes("/v1/test", maxBytes = 1024); fail("oversized response accepted") }
            catch (error: IllegalArgumentException) { assertEquals("response_size", error.message) }
            try { transport.bytes("https://elsewhere.test/path", token = "test-token"); fail("foreign origin accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun cancellingTheCoroutineCancelsTheNetworkCall(): Unit = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false); server.start()
            val transport = HttpTransport(server.url("/").toString().removeSuffix("/"), OkHttpClient.Builder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
            server.enqueue(MockResponse().setBody("later").setBodyDelay(5, TimeUnit.SECONDS))
            val start = System.nanoTime()
            try { withTimeout(500) { transport.bytes("/wait") }; fail("request was not cancelled") }
            catch (_: TimeoutCancellationException) { }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
        }
    }
}
