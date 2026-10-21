package app.omymoney.core.diagnostics

import kotlinx.serialization.json.*

fun interface DiagnosticSink {
    val enabled: Boolean get() = true
    fun record(kind: String, fields: JsonObject)

    companion object { val NONE = object : DiagnosticSink {
        override val enabled = false
        override fun record(kind: String, fields: JsonObject) = Unit
    } }
}

fun DiagnosticSink.recordSafely(kind: String, fields: JsonObject = buildJsonObject {}) {
    if (!enabled) return
    try { record(kind, fields) } catch (_: Exception) { /* Diagnostics must not change the business outcome. */ }
}

inline fun DiagnosticSink.recordSafely(kind: String, fields: () -> JsonObject) {
    if (!enabled) return
    try { record(kind, fields()) } catch (_: Exception) { }
}

object DiagnosticRedactor {
    private val secretKey = Regex("(?i).*(password|passwd|otp|verification.?code|authorization|cookie|secret|token|pairing.?code).*")
    private val secretText = Regex("(?i)(password|passwd|\\botp\\b|verification\\s*code|authorization|bearer\\s|access[_ -]?token|refresh[_ -]?token|[?&]token=|\\btoken\\s*[:=]|验证码|驗證碼|动态码|動態碼|密[码碼]|校验码)")

    fun text(value: String): String = if (secretText.containsMatchIn(value)) "[REDACTED]" else value.take(4096)

    fun clean(value: JsonElement, depth: Int = 0): JsonElement {
        if (depth > 12) return JsonPrimitive("[DEPTH_LIMIT]")
        return when (value) {
            is JsonObject -> JsonObject(value.entries.take(128).associate { (key, item) ->
                key.take(128) to if (secretKey.matches(key)) JsonPrimitive("[REDACTED]") else clean(item, depth + 1)
            })
            is JsonArray -> JsonArray(value.take(1024).map { clean(it, depth + 1) })
            is JsonPrimitive -> if (value.isString) JsonPrimitive(text(value.content)) else value
        }
    }

    fun failure(error: Throwable): JsonObject = buildJsonObject {
        put("type", error.javaClass.name)
        put("stack", JsonArray(error.stackTrace.take(96).map { JsonPrimitive(it.toString()) }))
        put("message", text(error.message.orEmpty()))
        error.cause?.takeIf { it !== error }?.let { cause ->
            put("causeType", cause.javaClass.name)
            put("causeMessage", text(cause.message.orEmpty()))
            put("causeStack", JsonArray(cause.stackTrace.take(96).map { JsonPrimitive(it.toString()) }))
        }
    }
}
