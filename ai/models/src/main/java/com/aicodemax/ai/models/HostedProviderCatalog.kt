package com.aicodemax.ai.models

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Only pinned HTTPS hosts: entering an API key must never require entering a URL. */
enum class HostedWire { OPENAI, GEMINI, ANTHROPIC, COHERE }

data class HostedProviderSpec(val id: String, val name: String, val base: String, val wire: HostedWire)

object HostedProviderDirectory {
    val all: List<HostedProviderSpec> = listOf(
        HostedProviderSpec("openai", "OpenAI", "https://api.openai.com/v1", HostedWire.OPENAI),
        HostedProviderSpec("gemini", "Google Gemini API", "https://generativelanguage.googleapis.com/v1beta", HostedWire.GEMINI),
        HostedProviderSpec("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", HostedWire.ANTHROPIC),
        HostedProviderSpec("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", HostedWire.OPENAI),
        HostedProviderSpec("groq", "Groq", "https://api.groq.com/openai/v1", HostedWire.OPENAI),
        HostedProviderSpec("together", "Together AI", "https://api.together.ai/v1", HostedWire.OPENAI),
        HostedProviderSpec("mistral", "Mistral AI", "https://api.mistral.ai/v1", HostedWire.OPENAI),
        HostedProviderSpec("deepseek", "DeepSeek", "https://api.deepseek.com", HostedWire.OPENAI),
        HostedProviderSpec("xai", "xAI", "https://api.x.ai/v1", HostedWire.OPENAI),
        HostedProviderSpec("cerebras", "Cerebras", "https://api.cerebras.ai/v1", HostedWire.OPENAI),
        HostedProviderSpec("cohere", "Cohere", "https://api.cohere.com/v1", HostedWire.COHERE),
        HostedProviderSpec("novita", "Novita AI", "https://api.novita.ai/openai/v1", HostedWire.OPENAI),
    )

    fun find(id: String): HostedProviderSpec? = all.firstOrNull { it.id == id }
}

data class HostedHttpResponse(val code: Int, val body: String, val retryAfterSeconds: Long? = null)

/** Typed response preserves HTTP status for safe key failover, unlike the legacy transport. */
fun interface HostedHttpTransport {
    suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): HostedHttpResponse
}

/** Never follow redirects with Authorization/x-api-key. Never place secrets in URLs or error messages. */
class PinnedHttpsTransport : HostedHttpTransport {
    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HostedHttpResponse = withContext(Dispatchers.IO) {
        val target = URL(url)
        require(target.protocol == "https" && HostedProviderDirectory.all.any { target.host == URL(it.base).host }) {
            "Host is not an approved API provider"
        }
        val connection = target.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = if (method == "POST") 90_000 else 20_000
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val data = ByteArrayOutputStream()
            stream?.use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    check(data.size() + n <= 16 * 1024 * 1024) { "API response exceeds 16 MiB" }
                    data.write(chunk, 0, n)
                }
            }
            HostedHttpResponse(code, data.toString(Charsets.UTF_8.name()),
                connection.getHeaderField("Retry-After")?.toLongOrNull())
        } finally {
            connection.disconnect()
        }
    }
}

enum class HostedCapability { CHAT, VISION, IMAGE, AUDIO, VIDEO, EMBEDDING, RERANK, UNKNOWN }

data class HostedModel(
    val providerId: String,
    val id: String,
    val name: String,
    val capabilities: Set<HostedCapability>,
    /** Pricing is rarely returned by /models; NEVER assume missing pricing means free. */
    val priceNote: String? = null,
) {
    val canTryChat: Boolean get() = HostedCapability.CHAT in capabilities || HostedCapability.UNKNOWN in capabilities
}

data class HostedModelCatalog(val models: List<HostedModel>, val complete: Boolean, val note: String = "")

/**
 * Live, per-key model discovery. Do not hardcode model names or pretend all providers
 * publish entitlements/capabilities. Google, Anthropic, Cohere have paged catalogs.
 */
class HostedModelDiscovery(
    private val transport: HostedHttpTransport,
    private val keys: HostedKeyStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val maxPages = 100

    suspend fun list(providerId: String): Outcome<HostedModelCatalog> {
        val spec = HostedProviderDirectory.find(providerId)
            ?: return Outcome.Failure(AppError("HOSTED_UNKNOWN", "ไม่รู้จักผู้ให้บริการ"))
        val credentials = keys.keys(providerId)
        if (credentials.isEmpty()) return Outcome.Failure(AppError("HOSTED_NO_KEY", "เพิ่ม API key ก่อน"))
        // 401/403 alone indicates this credential cannot list models. 429 means wait;
        // never rotate same-provider keys to evade a rate limit.
        for (credential in credentials) {
            val secret = keys.secret(credential.id) ?: continue
            val fetched = try { fetchAll(spec, secret) }
                catch (_: Exception) {
                    return Outcome.Failure(AppError("HOSTED_NETWORK", "เชื่อมต่อหรืออ่านรายชื่อโมเดลไม่ได้; ตรวจอินเทอร์เน็ตแล้วลองใหม่"))
                }
            if (fetched is CatalogFetch.HttpError && fetched.code in setOf(401, 403)) continue
            return when (fetched) {
                is CatalogFetch.Success -> Outcome.Success(fetched.catalog)
                is CatalogFetch.HttpError -> Outcome.Failure(AppError("HOSTED_HTTP_${fetched.code}",
                    "${spec.name}: HTTP ${fetched.code} ขณะดึงรายชื่อโมเดล${if (fetched.code == 429) " — รอตามข้อกำหนดผู้ให้บริการ" else ""}"))
                is CatalogFetch.BadJson -> Outcome.Failure(AppError("HOSTED_CATALOG", "${spec.name}: รูปแบบรายชื่อโมเดลไม่รองรับ"))
            }
        }
        return Outcome.Failure(AppError("HOSTED_AUTH", "${spec.name}: คีย์ทั้งหมดถูกปฏิเสธ (401/403)"))
    }

    private sealed interface CatalogFetch {
        data class Success(val catalog: HostedModelCatalog) : CatalogFetch
        data class HttpError(val code: Int) : CatalogFetch
        data object BadJson : CatalogFetch
    }

    private suspend fun fetchAll(spec: HostedProviderSpec, secret: String): CatalogFetch {
        val entries = linkedMapOf<String, HostedModel>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var page = 0
        var next: String?
        do {
            if (++page > maxPages) return CatalogFetch.Success(HostedModelCatalog(entries.values.toList(), false,
                "รายชื่อยาวเกิน $maxPages หน้า: ยังไม่ครบ กรุณาลองใหม่ภายหลัง"))
            val suffix = when (spec.wire) {
                HostedWire.GEMINI -> "/models?pageSize=1000" + cursor?.let { "&pageToken=${encode(it)}" }.orEmpty()
                HostedWire.ANTHROPIC -> "/models?limit=100" + cursor?.let { "&after_id=${encode(it)}" }.orEmpty()
                HostedWire.COHERE -> "/models?page_size=1000" + cursor?.let { "&page_token=${encode(it)}" }.orEmpty()
                HostedWire.OPENAI -> "/models"
            }
            val res = transport.request("GET", spec.base + suffix, authHeaders(spec, secret), null)
            if (res.code !in 200..299) return CatalogFetch.HttpError(res.code)
            val root = try { json.parseToJsonElement(res.body) } catch (_: Exception) { return CatalogFetch.BadJson }
            val obj = root as? JsonObject
            val array = when {
                root is JsonArray -> root
                obj != null -> (obj[when (spec.wire) {
                    HostedWire.GEMINI, HostedWire.COHERE -> "models"
                    else -> "data"
                }] as? JsonArray) ?: (obj["models"] as? JsonArray)
                else -> null
            } ?: return CatalogFetch.BadJson
            for (entry in array) {
                val model = parseModel(spec.id, entry as? JsonObject ?: continue) ?: continue
                entries[model.id] = model
            }
            next = when (spec.wire) {
                HostedWire.GEMINI -> obj?.str("nextPageToken")
                HostedWire.COHERE -> obj?.str("next_page_token")
                HostedWire.ANTHROPIC -> if (obj?.get("has_more")?.jsonPrimitive?.booleanOrNull == true) {
                    obj.str("last_id")
                } else null
                HostedWire.OPENAI -> null // no documented pagination on these pinned /models endpoints
            }
            if (next != null && !seenCursors.add(next)) return CatalogFetch.Success(
                HostedModelCatalog(entries.values.toList(), false, "ผู้ให้บริการส่ง page token ซ้ำ: รายชื่ออาจไม่ครบ"))
            cursor = next
        } while (next != null)
        return CatalogFetch.Success(HostedModelCatalog(entries.values.toList(), true,
            if (entries.isEmpty()) "API ไม่ส่งรายชื่อโมเดลสำหรับคีย์นี้" else "รายชื่อจาก API ณ เวลาที่รีเฟรช; สิทธิ์ใช้งาน/ราคาอาจต่างกัน"))
    }

    companion object {
        fun authHeaders(spec: HostedProviderSpec, secret: String): Map<String, String> = when (spec.wire) {
            HostedWire.GEMINI -> mapOf("x-goog-api-key" to secret)
            HostedWire.ANTHROPIC -> mapOf("x-api-key" to secret, "anthropic-version" to "2023-06-01")
            else -> mapOf("Authorization" to "Bearer $secret")
        }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private fun parseModel(providerId: String, obj: JsonObject): HostedModel? {
    val id = obj.str("id") ?: obj.str("name") ?: return null
    if (id.length > 300) return null
    val name = obj.str("displayName") ?: obj.str("display_name") ?: obj.str("name") ?: id
    val caps = mutableSetOf<HostedCapability>()
    fun strings(value: JsonArray?): Set<String> = value?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }?.toSet().orEmpty()
    val endpoints = strings(obj.array("endpoints"))
    val methods = strings(obj.array("supportedGenerationMethods")) + strings(obj.array("supportedActions"))
    val type = (obj.str("type") ?: obj.str("task") ?: "").lowercase()
    val input = strings(obj.array("input_modalities")) + strings(obj.obj("architecture")?.array("input_modalities"))
    val output = strings(obj.array("output_modalities")) + strings(obj.obj("architecture")?.array("output_modalities"))
    val misc = strings(obj.array("capabilities"))
    val flags = obj.obj("capabilities")
    if (endpoints.any { it == "chat" } || type == "chat" || "generatecontent" in methods || "chat" in misc ||
        flags?.get("completion_chat")?.jsonPrimitive?.booleanOrNull == true) caps.add(HostedCapability.CHAT)
    if ("embed" in endpoints || "embedcontent" in methods || type.contains("embedding")) caps.add(HostedCapability.EMBEDDING)
    if ("rerank" in endpoints || type.contains("rerank")) caps.add(HostedCapability.RERANK)
    if ("image" in input || flags?.get("vision")?.jsonPrimitive?.booleanOrNull == true) caps.add(HostedCapability.VISION)
    if ("image" in output || type.contains("image") || "generateimages" in methods) caps.add(HostedCapability.IMAGE)
    if ("audio" in output || type.contains("audio") || type.contains("speech")) caps.add(HostedCapability.AUDIO)
    if ("video" in output || type.contains("video")) caps.add(HostedCapability.VIDEO)
    if ("text" in output && type !in setOf("embedding", "rerank")) caps.add(HostedCapability.CHAT)
    // If the provider gives no capability metadata, classify *candidates* conservatively.
    // Unknown is displayed, selectable with a warning, but never advertised as verified chat.
    val key = id.lowercase()
    if (caps.isEmpty()) {
        when {
            key.contains("embed") -> caps.add(HostedCapability.EMBEDDING)
            key.contains("rerank") || key.contains("moderation") -> caps.add(HostedCapability.RERANK)
            key.contains("sora") || key.contains("video") || key.contains("veo") -> caps.add(HostedCapability.VIDEO)
            key.contains("whisper") || key.contains("transcri") || key.contains("tts") ||
                key.contains("speech") || key.contains("audio") -> caps.add(HostedCapability.AUDIO)
            key.contains("dall-e") || key.contains("image") || key.contains("imagen") -> caps.add(HostedCapability.IMAGE)
            else -> caps.add(HostedCapability.UNKNOWN)
        }
    }
    val pricing = obj.obj("pricing")
    val priceNote = if (providerId == "openrouter" && pricing != null) {
        "ราคา API: prompt=${pricing.str("prompt") ?: "?"}, completion=${pricing.str("completion") ?: "?"} USD/token (ตรวจสอบก่อนใช้)"
    } else null
    return HostedModel(providerId, id, name.take(300), caps, priceNote)
}
