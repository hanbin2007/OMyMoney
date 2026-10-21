package app.omymoney.core.updates

import java.net.URI
import java.security.PublicKey
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class UpdateRelease(
    val schema: Int,
    val versionCode: Long,
    val versionName: String,
    val packageName: String,
    val signerSha256: String,
    val apkUrl: String,
    val apkSha256: String,
    val apkBytes: Long,
    val issuedAt: String,
    val expiresAt: String,
    val notes: String,
)

class ManifestVerifier(private val publicKey: PublicKey, origin: String,
    private val packageName: String, private val signerSha256: String) {
    private val origin = URI(origin)
    @Serializable private data class Envelope(val payload: String, val signature: String)

    fun verify(envelope: String, installedVersion: Long, now: Instant): UpdateRelease {
        require(envelope.length <= 32768) { "manifest_size" }
        try {
            val signed = Json.decodeFromString<Envelope>(envelope)
            val bytes = Base64.getDecoder().decode(signed.payload)
            require(bytes.size <= 16384) { "payload_size" }
            val signature = Base64.getDecoder().decode(signed.signature)
            require(Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey); update(bytes); verify(signature)
            }) { "manifest_signature" }
            val release = Json.decodeFromString<UpdateRelease>(bytes.toString(Charsets.UTF_8))
            require(release.schema == 1 && release.versionCode > installedVersion) { "not_newer" }
            require(release.versionName.length in 1..64 && release.notes.length <= 4096) { "release_fields" }
            require(release.packageName == packageName && release.signerSha256 == signerSha256) { "release_identity" }
            require(release.apkSha256.matches(Regex("[0-9a-f]{64}"))) { "apk_digest" }
            require(release.apkBytes in 1..MAX_APK_BYTES) { "apk_size" }
            val issued = Instant.parse(release.issuedAt)
            val expires = Instant.parse(release.expiresAt)
            require(issued <= now.plusSeconds(300) && expires > now && expires > issued &&
                expires <= issued.plusSeconds(90L * 86400)) { "release_time" }
            val url = URI(release.apkUrl)
            require(url.scheme == "https" && origin.scheme == "https" && url.host == origin.host && url.port == origin.port &&
                url.userInfo == null && url.query == null && url.fragment == null &&
                url.rawPath.matches(Regex("/updates/[A-Za-z0-9_-]+\\.apk"))) { "apk_origin" }
            return release
        } catch (error: IllegalArgumentException) { throw error }
        catch (error: Exception) { throw IllegalArgumentException("invalid_manifest", error) }
    }

    companion object { const val MAX_APK_BYTES = 100L * 1024 * 1024 }
}
