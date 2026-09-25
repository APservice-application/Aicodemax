package com.aicodemax.ai.runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold

/**
 * CP-124: built-in [ModelProvider] implementations.
 *
 * - [LocalModelProvider]: on-device model via [AiRuntime] (the default path).
 * - [CloudModelProvider]: hosted API (stub until credentials/endpoint exist).
 * - [RemoteModelProvider]: self-hosted endpoint (stub until configured).
 * - [HybridModelProvider]: routes between local + cloud by [HybridStrategy].
 */

/** Local inference through an [AiRuntime] (Fake/JNI/interim-server). */
class LocalModelProvider(
    private val runtime: AiRuntime,
    private val template: (List<ChatMessage>) -> String = ChatTemplate::qwen3,
) : ModelProvider {
    override val providerId: String = "local"
    override val displayName: String = "โมเดลบนเครื่อง"

    override fun status(): ProviderStatus =
        if (runtime.isModelLoaded()) ProviderStatus.Available
        else ProviderStatus.Unavailable("ยังไม่โหลดโมเดล")

    override fun chat(req: ChatRequest, onToken: TokenSink): Outcome<ChatReply> {
        if (req.messages.none { it.role == ChatRole.USER || it.role == ChatRole.TOOL }) {
            return Outcome.Failure(AppError("PROVIDER_CHAT", "missing user message"))
        }
        val prompt = template(req.messages)
        return runtime.generate(prompt, req.params, onToken).fold(
            // CP-147: Qwen3 must never leak raw <think> into chat.
            onSuccess = { Outcome.Success(ChatReply(ChatTemplate.stripThinking(it.text), it.stoppedEarly, via = providerId)) },
            onFailure = { Outcome.Failure(it) },
        )
    }

    override fun close() {
        // The runtime lifecycle belongs to AiRuntimeManager (CP-126), not us.
    }
}

/** Injectable cloud sender so the stub is testable and swappable later. */
fun interface CloudSender {
    fun send(req: ChatRequest, onToken: TokenSink): Outcome<String>
}

/** Hosted model API. Honest stub until a [CloudSender] is configured. */
class CloudModelProvider(
    private val sender: CloudSender? = null,
    override val displayName: String = "โมเดลคลาวด์",
) : ModelProvider {
    override val providerId: String = "cloud"

    override fun status(): ProviderStatus =
        if (sender != null) ProviderStatus.Available
        else ProviderStatus.NeedsSetup("ยังไม่ได้ตั้งค่า cloud provider (API key/endpoint)")

    override fun chat(req: ChatRequest, onToken: TokenSink): Outcome<ChatReply> {
        val s = sender ?: return Outcome.Failure(
            AppError("PROVIDER_CHAT", "ยังไม่ได้ตั้งค่า cloud provider — ใช้โมเดลบนเครื่องก่อน"),
        )
        return s.send(req, onToken).fold(
            onSuccess = { Outcome.Success(ChatReply(it, via = providerId)) },
            onFailure = { Outcome.Failure(it) },
        )
    }

    override fun close() = Unit
}

/** Self-hosted OpenAI-compatible endpoint. Honest stub until configured. */
class RemoteModelProvider(
    private val endpoint: String? = null,
    private val sender: CloudSender? = null,
    override val displayName: String = "โมเดลรีโมต",
) : ModelProvider {
    override val providerId: String = "remote"

    override fun status(): ProviderStatus =
        if (endpoint != null && sender != null) ProviderStatus.Available
        else ProviderStatus.NeedsSetup("ยังไม่ได้ตั้งค่า remote endpoint")

    override fun chat(req: ChatRequest, onToken: TokenSink): Outcome<ChatReply> {
        val s = sender ?: return Outcome.Failure(
            AppError("PROVIDER_CHAT", "ยังไม่ได้ตั้งค่า remote endpoint"),
        )
        return s.send(req, onToken).fold(
            onSuccess = { Outcome.Success(ChatReply(it, via = providerId)) },
            onFailure = { Outcome.Failure(it) },
        )
    }

    override fun close() = Unit
}

enum class HybridStrategy { LOCAL_FIRST, CLOUD_FIRST, LOCAL_ONLY, CLOUD_ONLY }

/**
 * Routes between [local] and [cloud]. LOCAL_FIRST tries local, then falls
 * back to cloud only when local fails AND cloud is available.
 */
class HybridModelProvider(
    private val local: ModelProvider,
    private val cloud: ModelProvider,
    var strategy: HybridStrategy = HybridStrategy.LOCAL_FIRST,
) : ModelProvider {
    override val providerId: String = "hybrid"
    override val displayName: String = "ไฮบริด (เครื่อง+คลาวด์)"

    override fun status(): ProviderStatus {
        val localOk = local.status() is ProviderStatus.Available
        val cloudOk = cloud.status() is ProviderStatus.Available
        return when {
            localOk || cloudOk -> ProviderStatus.Available
            else -> ProviderStatus.Unavailable("ทั้ง local และ cloud ไม่พร้อม")
        }
    }

    override fun chat(req: ChatRequest, onToken: TokenSink): Outcome<ChatReply> {
        val order = when (strategy) {
            HybridStrategy.LOCAL_FIRST -> listOf(local, cloud)
            HybridStrategy.CLOUD_FIRST -> listOf(cloud, local)
            HybridStrategy.LOCAL_ONLY -> listOf(local)
            HybridStrategy.CLOUD_ONLY -> listOf(cloud)
        }
        var lastError: AppError? = null
        for (provider in order) {
            if (provider.status() !is ProviderStatus.Available) {
                lastError = AppError("PROVIDER_CHAT", "${provider.providerId} ไม่พร้อม")
                continue
            }
            when (val res = provider.chat(req, onToken)) {
                is Outcome.Success -> return res
                is Outcome.Failure -> lastError = res.error
            }
        }
        return Outcome.Failure(lastError ?: AppError("PROVIDER_CHAT", "no provider available"))
    }

    override fun close() {
        local.close()
        cloud.close()
    }
}

/** Spec §1 Provider Manager: registry + default provider. */
class ProviderRegistry {
    private val providers = LinkedHashMap<String, ModelProvider>()
    private var defaultId: String? = null

    fun register(provider: ModelProvider, makeDefault: Boolean = false): ProviderRegistry {
        providers[provider.providerId] = provider
        if (makeDefault || defaultId == null) defaultId = provider.providerId
        return this
    }

    fun get(id: String): ModelProvider? = providers[id]

    fun list(): List<ModelProvider> = providers.values.toList()

    fun default(): ModelProvider? = defaultId?.let { providers[it] }

    fun setDefault(id: String): Boolean {
        if (!providers.containsKey(id)) return false
        defaultId = id
        return true
    }

    fun statusLines(): List<String> = providers.values.map { p ->
        val st = when (val s = p.status()) {
            is ProviderStatus.Available -> "พร้อม"
            is ProviderStatus.Unavailable -> "ไม่พร้อม: ${s.reason}"
            is ProviderStatus.NeedsSetup -> "ต้องตั้งค่า: ${s.reason}"
        }
        "${p.providerId} (${p.displayName}): $st"
    }
}
