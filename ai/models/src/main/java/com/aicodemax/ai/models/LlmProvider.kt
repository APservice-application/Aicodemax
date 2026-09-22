package com.aicodemax.ai.models

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CP-59: LLM provider seam (OpenAI-compatible — never locked to one vendor).
 * Works with OpenAI, llama-server, Ollama, or any /chat/completions endpoint.
 * Keys are memory-only (held by the UI layer, never persisted here).
 */
data class LlmMessage(
    val role: String,
    val content: String,
    /** JPEG/PNG bytes as base64 — sent as image_url when present (vision). */
    val imageBase64: String? = null,
)

data class LlmReply(val content: String)

interface LlmProvider {
    val id: String
    suspend fun chat(
        model: String,
        messages: List<LlmMessage>,
        maxTokens: Int = 1024,
    ): Outcome<LlmReply>

    suspend fun health(): Outcome<String>
}

/** Transport seam: real HTTP in production, canned responses in tests. */
interface LlmTransport {
    suspend fun postJson(url: String, headers: Map<String, String>, body: String): Outcome<String>
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Outcome<String>
}

class JavaNetLlmTransport(private val timeoutMs: Int = 60_000) : LlmTransport {
    override suspend fun postJson(url: String, headers: Map<String, String>, body: String): Outcome<String> =
        withContext(Dispatchers.IO) {
            runOutcome("LLM_HTTP") {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = timeoutMs
                    connection.readTimeout = timeoutMs
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                    connection.outputStream.bufferedWriter().use { it.write(body) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader()?.readText().orEmpty()
                    if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(300)}")
                    text
                } finally {
                    connection.disconnect()
                }
            }
        }

    override suspend fun get(url: String, headers: Map<String, String>): Outcome<String> =
        withContext(Dispatchers.IO) {
            runOutcome("LLM_HTTP") {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader()?.readText().orEmpty()
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    text
                } finally {
                    connection.disconnect()
                }
            }
        }
}

class OpenAiCompatProvider(
    private val baseUrl: String,
    private val key: () -> String?,
    private val transport: LlmTransport = JavaNetLlmTransport(),
    override val id: String = "openai-compat",
) : LlmProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chat(
        model: String,
        messages: List<LlmMessage>,
        maxTokens: Int,
    ): Outcome<LlmReply> {
        if (model.isBlank()) return Outcome.Failure(AppError("LLM_NO_MODEL", "model is blank"))
        val body = JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "max_tokens" to JsonPrimitive(maxTokens),
                "messages" to JsonArray(messages.map { it.toJson() }),
            ),
        ).toString()
        val headers = buildMap {
            key()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        return when (val posted = transport.postJson(base().trimEnd('/') + "/chat/completions", headers, body)) {
            is Outcome.Failure -> posted
            is Outcome.Success -> runOutcome("LLM_PARSE") { parseReply(posted.value) }
        }
    }

    override suspend fun health(): Outcome<String> =
        when (val got = transport.get(base().trimEnd('/') + "/models")) {
            is Outcome.Failure -> Outcome.Failure(AppError("LLM_UNREACHABLE", "ต่อไม่ได้: ${got.error.message}"))
            is Outcome.Success -> runOutcome("LLM_PARSE") {
                val count = json.parseToJsonElement(got.value).jsonObject["data"]?.jsonArray?.size ?: 0
                "ok ($count models)"
            }
        }

    private fun base(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        // Accept bare hosts and trailing /v1.
        return trimmed.ifBlank { "https://api.openai.com/v1" }
    }

    private fun LlmMessage.toJson(): JsonObject {
        val contentElement = if (imageBase64 == null) {
            JsonPrimitive(content)
        } else {
            JsonArray(
                listOf(
                    JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(content))),
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("image_url"),
                            "image_url" to JsonObject(
                                mapOf("url" to JsonPrimitive("data:image/jpeg;base64,$imageBase64")),
                            ),
                        ),
                    ),
                ),
            )
        }
        return JsonObject(mapOf("role" to JsonPrimitive(role), "content" to contentElement))
    }

    private fun parseReply(body: String): LlmReply {
        val root = json.parseToJsonElement(body).jsonObject
        root["error"]?.let {
            val message = it.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: it.toString()
            throw IllegalStateException("provider error: ${message.take(300)}")
        }
        val content = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("no choices[0].message.content in reply")
        return LlmReply(content)
    }
}

/** Probes OpenAI-compatible local endpoints (llama-server :8080, Ollama :11434). */
object LocalEndpointDetector {
    val CANDIDATES = listOf("http://127.0.0.1:11434/v1", "http://127.0.0.1:8080/v1")

    suspend fun probe(transport: LlmTransport, baseUrl: String): Outcome<String> =
        OpenAiCompatProvider(baseUrl, { null }, transport, id = "local-probe").health()

    suspend fun detect(transport: LlmTransport): Outcome<String> {
        val failures = mutableListOf<String>()
        for (candidate in CANDIDATES) {
            when (val result = probe(transport, candidate)) {
                is Outcome.Success -> return Outcome.Success("$candidate — ${result.value}")
                is Outcome.Failure -> failures.add("$candidate (${result.error.message})")
            }
        }
        return Outcome.Failure(
            AppError("LLM_NO_LOCAL", "ไม่พบ llama-server/ollama: ${failures.joinToString("; ")}"),
        )
    }
}
