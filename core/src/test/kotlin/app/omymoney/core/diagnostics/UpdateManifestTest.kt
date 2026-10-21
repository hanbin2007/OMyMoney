package app.omymoney.core.diagnostics

import app.omymoney.core.updates.ManifestVerifier
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val now = Instant.parse("2026-10-12T12:00:00Z")
    private val verifier = ManifestVerifier(keys.public, "https://example.test:58952", "app.omymoney.android", "a".repeat(64))
    private fun payload(overrides: Map<String, JsonElement> = emptyMap()): ByteArray {
        val fields = buildJsonObject {
            put("schema", 1); put("versionCode", 2); put("versionName", "0.2.0")
            put("packageName", "app.omymoney.android"); put("signerSha256", "a".repeat(64))
            put("apkUrl", "https://example.test:58952/updates/app-2.apk")
            put("apkSha256", "b".repeat(64)); put("apkBytes", 40000000)
            put("issuedAt", "2026-10-12T11:00:00Z"); put("expiresAt", "2026-11-12T11:00:00Z")
            put("notes", "Diagnostics and updates")
        }.toMutableMap().apply { putAll(overrides) }
        return JsonObject(fields).toString().toByteArray()
    }
    private fun envelope(bytes: ByteArray = payload(), tamper: Boolean = false): String {
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(keys.private); update(bytes) }.sign()
        val actual = if (tamper) bytes + ' '.code.toByte() else bytes
        return buildJsonObject {
            put("payload", Base64.getEncoder().encodeToString(actual))
            put("signature", Base64.getEncoder().encodeToString(signature))
        }.toString()
    }

    @Test fun verifiedPayloadExposesOnlyAuthenticatedReleaseFields() {
        val release = verifier.verify(envelope(), installedVersion = 1, now = now)
        assertEquals(2L, release.versionCode)
        assertEquals("https://example.test:58952/updates/app-2.apk", release.apkUrl)
        assertEquals(40000000L, release.apkBytes)
    }

    @Test fun changingAnySignedByteOrTheVerificationKeyFails() {
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(envelope(tamper = true), 1, now) }
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            ManifestVerifier(other.public, "https://example.test:58952", "app.omymoney.android", "a".repeat(64)).verify(envelope(), 1, now)
        }
    }

    @Test fun signedButUnsafeOrStaleReleasesAreRejected() {
        val bad = listOf(
            "versionCode" to JsonPrimitive(1), "schema" to JsonPrimitive(2),
            "packageName" to JsonPrimitive("other.package"), "signerSha256" to JsonPrimitive("c".repeat(64)),
            "apkSha256" to JsonPrimitive("../fake"), "apkBytes" to JsonPrimitive(200000000),
            "apkUrl" to JsonPrimitive("http://example.test:58952/updates/app.apk"),
            "apkUrl" to JsonPrimitive("https://other.test/updates/app.apk"),
            "apkUrl" to JsonPrimitive("https://example.test:58952/updates/../private"),
            "apkUrl" to JsonPrimitive("https://user:pass@example.test:58952/updates/app.apk"),
            "expiresAt" to JsonPrimitive("2026-10-11T11:00:00Z"),
            "issuedAt" to JsonPrimitive("2027-10-11T11:00:00Z"),
        )
        bad.forEach { field ->
            assertThrows("Must reject $field", IllegalArgumentException::class.java) {
                verifier.verify(envelope(payload(mapOf(field))), 1, now)
            }
        }
    }

    @Test fun oversizedEnvelopeAndMalformedPayloadFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { verifier.verify("x".repeat(40000), 1, now) }
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(envelope("not json".toByteArray()), 1, now) }
    }
}
