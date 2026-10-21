package app.omymoney.platform.updates

import app.omymoney.core.updates.UpdateRelease
import app.omymoney.platform.remote.HttpTransport
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import app.omymoney.platform.remote.HttpFailure

class ApkDownload(private val directory: File, private val transport: HttpTransport) {
    fun partial(release: UpdateRelease): File {
        require(release.apkSha256.matches(Regex("[0-9a-f]{64}")))
        check(directory.isDirectory || directory.mkdirs())
        return File(directory, "${release.apkSha256}.part")
    }

    suspend fun fetch(release: UpdateRelease, progress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        val partial = partial(release)
        val ready = File(directory, "${release.apkSha256}.apk")
        require(release.apkBytes in 1..app.omymoney.core.updates.ManifestVerifier.MAX_APK_BYTES)
        if (ready.exists() && valid(ready, release)) return@withContext ready
        ready.delete()
        if (partial.length() > release.apkBytes) partial.delete()
        var offset = partial.length()
        check(directory.usableSpace >= release.apkBytes - offset + 64L * 1024 * 1024) { "disk_space" }
        val caller = currentCoroutineContext()
        if (offset < release.apkBytes) transport.request(release.apkUrl, rangeStart = offset.takeIf { it > 0 }) { response ->
            when (response.code) {
                200 -> offset = 0
                206 -> require(response.header("Content-Range") == "bytes $offset-${release.apkBytes - 1}/${release.apkBytes}") { "apk_range" }
                else -> throw HttpFailure(response.code)
            }
            require(response.body.contentLength() == -1L || response.body.contentLength() == release.apkBytes - offset) { "apk_size" }
            FileOutputStream(partial, offset > 0).use { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(32768)
                    var lastProgress = offset
                    while (true) {
                        caller.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(offset + count <= release.apkBytes) { "apk_size" }
                        output.write(buffer, 0, count); offset += count
                        if (offset - lastProgress >= 262144 || offset == release.apkBytes) { progress(offset); lastProgress = offset }
                    }
                }
                output.fd.sync()
            }
        }
        caller.ensureActive()
        if (!valid(partial, release)) { partial.delete(); throw IllegalArgumentException("apk_digest") }
        Files.move(partial.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        ready
    }

    fun valid(file: File, release: UpdateRelease): Boolean {
        if (file.length() != release.apkBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == release.apkSha256
    }
}
