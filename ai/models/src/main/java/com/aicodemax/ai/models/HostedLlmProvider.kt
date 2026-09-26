package com.aicodemax.ai.models

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * BYOK brain: use the selected model/key, then remaining keys for auth failures,
 * then other configured providers on 402/404/429/502/503/504. Never retry
 * after a timeout, HTTP 500 or unparseable 2xx: the request may be billed.
 */
class HostedLlmProvider(
    private val keys: HostedKeyStore,
    private val transport: HostedHttpTransport = PinnedHttpsTransport(),
) : LlmProvider {
    override val id: String = "hosted-byok"
    private val json = Json { ignoreUnknownKeys = true }
    private val discovery = HostedModelDiscovery(transport, keys)
    private val catalogCache = mutableMapOf<String, HostedModelCatalog>()

    fun selectedRoute(): HostedRoute? = keys.primaryProvider()?.let { provider ->
        keys.model(provider)?.let { model -> HostedRoute(provider, model) }
    }

    fun routeLabels(): List<String> = routes().map { route ->
        "${HostedProviderDirectory.find(route.providerId)?.name ?: route.providerId} / ${route.modelId}"
    }

    fun storedKeys(providerId: String): List<HostedKeyRef> = keys.keys(providerId)
    fun addKey(providerId: String, value: String): HostedKeyRef = keys.add(providerId, value)
    fun removeKey(id: String) = keys.remove(id)
    fun selectedModel(providerId: String): String? = keys.model(providerId)
    fun primaryProvider(): String? = keys.primaryProvider()
    fun selectPrimary(providerId: String) = keys.selectPrimary(providerId)
    fun consent(): Boolean = keys.networkConsent()
    fun setConsent(allowed: Boolean) = keys.setNetworkConsent(allowed)

    fun selectModel(providerId: String, modelId: String) {
        val cached = synchronized(catalogCache) { catalogCache[providerId] }
        require(cached?.models?.any { it.id == modelId } == true) { "เลือกรุ่นจากรายการ API ที่รีเฟรชแล้วเท่านั้น" }
        keys.selectModel(providerId, modelId)
    }

    suspend fun discover(providerId: String): Outcome<HostedModelCatalog> {
        val result = discovery.list(providerId)
        if (result is Outcome.Success) {
            synchronized(catalogCache) { catalogCache[providerId] = result.value }
            // No model string input: prefer declared chat; some /models endpoints
            // provide IDs only, so select an unverified candidate with a visible warning.
            if (keys.model(providerId) == null) {
                val chosen = result.value.models.firstOrNull { HostedCapability.CHAT in it.capabilities }
                    ?: result.value.models.firstOrNull { HostedCapability.UNKNOWN in it.capabilities }
                chosen?.let { keys.selectModel(providerId, it.id) }
            }
        }
        return result
    }

    fun cached(providerId: String): HostedModelCatalog? = synchronized(catalogCache) { catalogCache[providerId] }

    override suspend fun health(): Outcome<String> = when {
        !keys.networkConsent() -> Outcome.Failure(AppError("HOSTED_CONSENT", "ยืนยันการส่งข้อมูลภายนอกและค่าใช้จ่ายก่อน"))
        selectedRoute() == null -> Outcome.Failure(AppError("HOSTED_NO_MODEL", "เพิ่ม API key และเลือกโมเดลก่อน"))
        else -> Outcome.Success("พร้อมลองใช้ ${routeLabels().size} เส้นทาง (ยังต้องตรวจสิทธิ์/ราคาเมื่อยิงจริง)")
    }

    override suspend fun chat(model: String, messages: List<LlmMessage>, maxTokens: Int): Outcome<LlmReply> {
        if (!keys.networkConsent()) return Outcome.Failure(AppError("HOSTED_CONSENT",
            "ยังไม่ได้อนุญาตให้ส่งข้อความ/ไฟล์ไป API ภายนอก (อาจมีค่าใช้จ่าย)"))
        val targets = routes()
        if (targets.isEmpty()) return Outcome.Failure(AppError("HOSTED_NO_MODEL", "เพิ่ม API key แล้วรีเฟรช/เลือกโมเดล"))
        val failures = mutableListOf<String>()
        for (route in targets) {
            val spec = HostedProviderDirectory.find(route.providerId) ?: continue
            // Non-chat entries remain visible in the full catalog, but are not routed
            // into a chat brain (no fake response from an image/audio/embedding model).
            val known = synchronized(catalogCache) { catalogCache[route.providerId]?.models?.find { it.id == route.modelId } }
            if (known != null && !known.canTryChat) {
                failures.add("${spec.name}: โมเดลไม่รองรับแชต")
                continue
            }
            var goToNextProvider = false
            for (key in keys.keys(spec.id)) {
                val secret = keys.secret(key.id) ?: continue
                val attempt = try { send(spec, route.modelId, secret, messages, maxTokens) }
                    catch (_: Exception) {
                        return Outcome.Failure(AppError("HOSTED_NETWORK",
                            "${spec.name}: เครือข่ายล้มเหลวหรือหมดเวลา; ไม่ยิงซ้ำเพื่อป้องกันการคิดเงินซ้ำ"))
                    }
                when (attempt) {
                    is Attempt.Done -> return Outcome.Success(LlmReply(attempt.text))
                    is Attempt.Auth -> failures.add("${spec.name}: คีย์ถูกปฏิเสธ (HTTP ${attempt.code})")
                    is Attempt.ProviderUnavailable -> {
                        failures.add("${spec.name}: HTTP ${attempt.code}${if (attempt.code == 429) " (จำกัดอัตรา; ไม่หมุนคีย์ของเจ้าเดิม)" else ""}")
                        goToNextProvider = true
                        break
                    }
                    is Attempt.Unsupported -> {
                        failures.add("${spec.name}: ${attempt.reason}")
                        goToNextProvider = true
                        break
                    }
                    is Attempt.Fatal -> return Outcome.Failure(AppError("HOSTED_HTTP_${attempt.code}",
                        "${spec.name}: HTTP ${attempt.code} — ตรวจโมเดล/สิทธิ์/คำขอ; ไม่ส่งซ้ำโดยอัตโนมัติ"))
                    Attempt.Unreadable -> return Outcome.Failure(AppError("HOSTED_REPLY",
                        "${spec.name}: อ่านคำตอบไม่ได้; ไม่ส่งซ้ำเพื่อป้องกันการคิดเงินซ้ำ"))
                }
            }
            if (goToNextProvider) continue
        }
        return Outcome.Failure(AppError("HOSTED_ALL_FAILED",
            "ไม่มี API ที่ตอบได้: ${failures.joinToString("; ").take(600).ifBlank { "ตรวจคีย์/โมเดลที่เลือก" }}"))
    }

    private fun routes(): List<HostedRoute> {
        val selected = selectedRoute()
        val others = HostedProviderDirectory.all.mapNotNull { spec ->
            val chosen = keys.model(spec.id)
            if (chosen == null || keys.keys(spec.id).isEmpty()) null else HostedRoute(spec.id, chosen)
        }
        return listOfNotNull(selected).plus(others).distinctBy { it.providerId }
    }

    private sealed interface Attempt {
        data class Done(val text: String) : Attempt
        data class Auth(val code: Int) : Attempt
        data class ProviderUnavailable(val code: Int) : Attempt
        data class Fatal(val code: Int) : Attempt
        data class Unsupported(val reason: String) : Attempt
        data object Unreadable : Attempt
    }

    private suspend fun send(
        spec: HostedProviderSpec, model: String, secret: String,
        messages: List<LlmMessage>, maxTokens: Int,
    ): Attempt {
        if (spec.wire == HostedWire.COHERE && messages.any { it.imageBase64 != null }) {
            return Attempt.Unsupported("รูปภาพต้องใช้ตัวเชื่อมแยก ไม่ทิ้งไฟล์ภาพจากคำขอ")
        }
        val responseMode = spec.id == "openai" &&
            listOf("gpt-5", "gpt-6", "o1", "o3", "o4").any { model.startsWith(it) }
        val (path, request) = when (spec.wire) {
            HostedWire.GEMINI -> {
                val safe = if (model.startsWith("models/")) model else "models/$model"
                if (!Regex("""models/[A-Za-z0-9._/\-]+""").matches(safe) || safe.contains("..")) {
                    return Attempt.Fatal(400)
                }
                "/$safe:generateContent" to geminiBody(messages, maxTokens)
            }
            HostedWire.ANTHROPIC -> "/messages" to anthropicBody(model, messages, maxTokens)
            HostedWire.COHERE -> "/chat" to cohereBody(model, messages, maxTokens)
            HostedWire.OPENAI -> if (responseMode) {
                "/responses" to responsesBody(model, messages, maxTokens)
            } else "/chat/completions" to openAiBody(model, messages, maxTokens)
        }
        val url = if (spec.wire == HostedWire.COHERE) "https://api.cohere.com/v2/chat" else spec.base + path
        val res = transport.request("POST", url, HostedModelDiscovery.authHeaders(spec, secret), request)
        if (res.code !in 200..299) return when (res.code) {
            401 -> Attempt.Auth(res.code)
            // 403 can be a policy/safety refusal. Never switch keys to evade it.
            403 -> Attempt.Fatal(res.code)
            402, 404, 429, 502, 503, 504 -> Attempt.ProviderUnavailable(res.code)
            // 500 may occur after upstream work began: avoid a potentially billed replay.
            else -> Attempt.Fatal(res.code)
        }
        val text = try { parseText(res.body, spec.wire, responseMode) } catch (_: Exception) { null }
        return if (text.isNullOrBlank()) Attempt.Unreadable else Attempt.Done(text)
    }

    private fun messagesJson(messages: List<LlmMessage>): JsonArray = JsonArray(messages.map { msg ->
        val content = if (msg.imageBase64 != null) {
            JsonArray(listOf(
                JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(msg.content))),
                JsonObject(mapOf("type" to JsonPrimitive("image_url"), "image_url" to JsonObject(mapOf(
                    "url" to JsonPrimitive("data:${imageMime(msg.imageBase64)};base64,${msg.imageBase64}"),
                )))),
            ))
        } else JsonPrimitive(msg.content)
        JsonObject(mapOf("role" to JsonPrimitive(msg.role), "content" to content))
    })

    private fun openAiBody(model: String, messages: List<LlmMessage>, maxTokens: Int): String =
        JsonObject(mapOf("model" to JsonPrimitive(model), "messages" to messagesJson(messages),
            "max_tokens" to JsonPrimitive(maxTokens.coerceIn(1, 8192)))).toString()

    private fun responsesBody(model: String, messages: List<LlmMessage>, maxTokens: Int): String {
        val input = messages.map { msg ->
            val content = msg.imageBase64?.let { image -> JsonArray(listOf(
                JsonObject(mapOf("type" to JsonPrimitive("input_text"), "text" to JsonPrimitive(msg.content))),
                JsonObject(mapOf("type" to JsonPrimitive("input_image"), "image_url" to
                    JsonPrimitive("data:${imageMime(image)};base64,$image"))),
            )) } ?: JsonPrimitive(msg.content)
            JsonObject(mapOf("role" to JsonPrimitive(msg.role), "content" to content))
        }
        return JsonObject(mapOf("model" to JsonPrimitive(model), "input" to JsonArray(input),
            "max_output_tokens" to JsonPrimitive(maxTokens.coerceIn(1, 8192)))).toString()
    }

    private fun geminiBody(messages: List<LlmMessage>, maxTokens: Int): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val contents = messages.filterNot { it.role == "system" }.map { msg ->
            val parts = mutableListOf<JsonObject>(JsonObject(mapOf("text" to JsonPrimitive(msg.content))))
            msg.imageBase64?.let { image ->
                parts.add(JsonObject(mapOf("inline_data" to JsonObject(mapOf(
                    "mime_type" to JsonPrimitive(imageMime(image)), "data" to JsonPrimitive(image),
                )))))
            }
            JsonObject(mapOf("role" to JsonPrimitive(if (msg.role == "assistant") "model" else "user"),
                "parts" to JsonArray(parts)))
        }
        return JsonObject(buildMap {
            put("contents", JsonArray(contents))
            if (system.isNotBlank()) put("systemInstruction", JsonObject(mapOf("parts" to JsonArray(listOf(
                JsonObject(mapOf("text" to JsonPrimitive(system))))))))
            put("generationConfig", JsonObject(mapOf("maxOutputTokens" to JsonPrimitive(maxTokens.coerceIn(1, 8192)))))
        }).toString()
    }

    private fun anthropicBody(model: String, messages: List<LlmMessage>, maxTokens: Int): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val chat = messages.filterNot { it.role == "system" }.map { msg ->
            val content = if (msg.imageBase64 != null) JsonArray(listOf(
                JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(msg.content))),
                JsonObject(mapOf("type" to JsonPrimitive("image"), "source" to JsonObject(mapOf(
                    "type" to JsonPrimitive("base64"), "media_type" to JsonPrimitive(imageMime(msg.imageBase64)),
                    "data" to JsonPrimitive(msg.imageBase64),
                )))),
            )) else JsonPrimitive(msg.content)
            JsonObject(mapOf("role" to JsonPrimitive(if (msg.role == "assistant") "assistant" else "user"),
                "content" to content))
        }
        return JsonObject(buildMap {
            put("model", JsonPrimitive(model))
            put("max_tokens", JsonPrimitive(maxTokens.coerceIn(1, 8192)))
            if (system.isNotBlank()) put("system", JsonPrimitive(system))
            put("messages", JsonArray(chat))
        }).toString()
    }

    private fun cohereBody(model: String, messages: List<LlmMessage>, maxTokens: Int): String {
        // Do not silently drop an image on a protocol that needs a different format.
        require(messages.none { it.imageBase64 != null }) { "Cohere image input requires a separate adapter" }
        return JsonObject(mapOf("model" to JsonPrimitive(model),
            "messages" to messagesJson(messages), "max_tokens" to JsonPrimitive(maxTokens.coerceIn(1, 8192)))).toString()
    }

    private fun parseText(body: String, wire: HostedWire, responses: Boolean): String? {
        val root = json.parseToJsonElement(body).jsonObject
        return when {
            responses -> (root["output"] as? JsonArray)?.flatMap { (it as? JsonObject)?.get("content") as? JsonArray
                ?: JsonArray(emptyList()) }
                ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
                ?.joinToString("\n")
            wire == HostedWire.GEMINI -> (root["candidates"] as? JsonArray)?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }?.joinToString("\n")
            wire == HostedWire.ANTHROPIC -> (root["content"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }?.joinToString("\n")
            wire == HostedWire.COHERE -> (root["message"] as? JsonObject)?.get("content")
                ?.jsonArray?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
                ?.joinToString("\n")
            else -> (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        }
    }

    private fun imageMime(data: String): String = if (data.startsWith("iVBOR")) "image/png" else "image/jpeg"
}
