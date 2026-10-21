package app.omymoney.platform.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class HttpFailure(val status: Int) : IOException("http_$status")

class HttpTransport(origin: String, client: OkHttpClient = OkHttpClient()) {
    private val origin = origin.toHttpUrl().also {
        require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" && it.query == null)
    }
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES).build()

    fun cancel() { client.dispatcher.cancelAll() }

    suspend fun bytes(path: String, token: String? = null, body: RequestBody? = null, maxBytes: Int = 32768, sha256: String? = null): ByteArray =
        request(path, token, body, sha256 = sha256) { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code)
            val content = response.body
            require(content.contentLength() <= maxBytes) { "response_size" }
            val output = ByteArrayOutputStream()
            content.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maxBytes) { "response_size" }
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        }

    suspend fun <T> request(path: String, token: String? = null, body: RequestBody? = null, rangeStart: Long? = null,
        sha256: String? = null, consume: (Response) -> T): T {
        val url = if (path.startsWith("/")) origin.resolve(path) ?: error("invalid_path") else path.toHttpUrl()
        require(url.isHttps && url.host == origin.host && url.port == origin.port && url.username.isEmpty() &&
            url.password.isEmpty() && url.fragment == null && url.query == null) { "foreign_origin" }
        val request = Request.Builder().url(url).apply {
            body?.let { post(it) }
            token?.let { header("Authorization", "Bearer $it") }
            rangeStart?.let { header("Range", "bytes=$it-") }
            sha256?.let { require(it.matches(Regex("[0-9a-f]{64}"))); header("X-Content-SHA256", it) }
        }.build()
        val call = client.newCall(request)
        val closed = CompletableDeferred<Unit>()
        try { return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    try { if (continuation.isActive) continuation.resumeWithException(e) }
                    finally { closed.complete(Unit) }
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { consume(it) }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally { closed.complete(Unit) }
                }
            })
        } } finally {
            // A cancelled caller must not reuse a download file until its consumer closes it.
            withContext(NonCancellable) { closed.await() }
        }
    }
}
