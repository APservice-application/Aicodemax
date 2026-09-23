package com.aicodemax.tools.media

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

/**
 * CP-108 §29 Cloud AI: provider slots for generation kinds that cannot run
 * on-device (text2video/text2music/text2sfx). Empty registry = honest
 * "unavailable" errors; a registered provider takes over transparently.
 */
interface CloudGenProvider {
    val id: String
    val label: String
    fun supports(kind: String): Boolean
    suspend fun generate(request: GenRequest, dstDir: String): Outcome<GenResult>
}

class CloudGenRegistry {
    private val providers = mutableListOf<CloudGenProvider>()

    fun register(provider: CloudGenProvider) {
        providers.removeAll { it.id == provider.id }
        providers.add(provider)
    }

    fun unregister(id: String) {
        providers.removeAll { it.id == id }
    }

    fun providersFor(kind: String): List<CloudGenProvider> = providers.filter { it.supports(kind) }

    fun statusLine(): String =
        if (providers.isEmpty()) {
            "คลาวด์: ยังไม่มี provider (text2video/text2music/text2sfx ใช้ไม่ได้)"
        } else {
            "คลาวด์: " + providers.joinToString(", ") { it.label }
        }
}

/**
 * Honest placeholder: advertises [kinds] in listings but fails every call
 * with setup instructions. Used until a real keyed provider is added.
 */
class StubCloudGenProvider(
    private val kinds: Set<String>,
    private val reason: String = "ยังไม่ตั้งค่า cloud provider (ใส่คีย์ที่หน้า Models → Cloud)",
) : CloudGenProvider {
    override val id: String = "stub"
    override val label: String = "stub (ยังไม่ตั้งค่า)"

    override fun supports(kind: String): Boolean = kind in kinds

    override suspend fun generate(request: GenRequest, dstDir: String): Outcome<GenResult> =
        Outcome.Failure(AppError("CLOUD_UNCONFIGURED", "${request.kind}: $reason"))
}
