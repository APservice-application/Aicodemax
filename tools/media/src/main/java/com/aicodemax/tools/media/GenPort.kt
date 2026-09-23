package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.MediaKind

/**
 * CP-83 §46 Generative Media: provider abstraction (Provider → Adapter → Engine → Asset → Timeline).
 * Offline-first: local providers generate real files; cloud slots (text2video/text2music/text2sfx)
 * are advertised honestly as unavailable until §29 Cloud AI lands.
 */
object GenKinds {
    const val POSTER = "poster" // Text → Image (title card PNG, offline)
    const val BACKGROUND = "background" // Text → Image (gradient/solid PNG, offline)
    const val STYLIZE = "stylize" // Image → Image (preset grade PNG, offline)
    const val TTS = "tts" // Text → Voice (WAV via local TTS, offline)
    const val THUMBNAIL = "thumbnail" // Video frame + title → cover PNG (offline)
    const val TEXT2VIDEO = "text2video" // slot: needs cloud provider (§29)
    const val TEXT2MUSIC = "text2music" // slot: needs cloud provider (§29)
    const val TEXT2SFX = "text2sfx" // slot: needs cloud provider (§29)
    val ALL = listOf(POSTER, BACKGROUND, STYLIZE, TTS, THUMBNAIL, TEXT2VIDEO, TEXT2MUSIC, TEXT2SFX)
}

data class GenCapability(
    val kind: String,
    val title: String,
    val available: Boolean,
    val note: String = "",
)

data class GenRequest(
    val kind: String,
    val prompt: String = "",
    /** Input file for image→image kinds. */
    val inputPath: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    val style: String = "",
    val lang: String = "th-TH",
    /** CP-101: frame position for thumbnail (-1 = middle). */
    val atMs: Long = -1,
)

data class GenResult(
    val path: String,
    val assetKind: MediaKind,
    val note: String = "",
)

interface GenPort {
    fun list(): List<GenCapability>
    suspend fun generate(request: GenRequest): Outcome<GenResult>
}

/** JVM/test double: advertises offline kinds, returns mem:// placeholders honestly. */
class InMemoryGenPort(
    private val cloud: CloudGenRegistry = CloudGenRegistry(),
) : GenPort {
    private fun cloudCap(kind: String, title: String): GenCapability {
        val providers = cloud.providersFor(kind)
        return if (providers.isEmpty()) {
            GenCapability(kind, title, false, "ต้องมี cloud provider (§29)")
        } else {
            GenCapability(kind, title, true, "ผ่าน ${providers.first().label}")
        }
    }

    override fun list(): List<GenCapability> = listOf(
        GenCapability(GenKinds.POSTER, "โปสเตอร์ข้อความ", true),
        GenCapability(GenKinds.BACKGROUND, "พื้นหลังไล่สี", true),
        GenCapability(GenKinds.STYLIZE, "แต่งรูปพรีเซ็ต", true),
        GenCapability(GenKinds.TTS, "เสียงพูด", true),
        GenCapability(GenKinds.THUMBNAIL, "ปกคลิป", true),
        cloudCap(GenKinds.TEXT2VIDEO, "ข้อความ→วิดีโอ"),
        cloudCap(GenKinds.TEXT2MUSIC, "ข้อความ→ดนตรี"),
        cloudCap(GenKinds.TEXT2SFX, "ข้อความ→เอฟเฟกต์เสียง"),
    )

    /** Cloud-kind path shared with Android (fake dstDir — providers decide). */
    suspend fun generateCloud(kind: String, request: GenRequest): Outcome<GenResult> {
        val provider = cloud.providersFor(kind).firstOrNull()
            ?: return Outcome.Failure(
                com.aicodemax.core.common.AppError("GEN_UNAVAILABLE", "$kind: ต้องมี cloud provider (§29)"),
            )
        return provider.generate(request, "mem://cloud")
    }

    fun cloudStatus(): String = cloud.statusLine()

    override suspend fun generate(request: GenRequest): Outcome<GenResult> {
        if (request.kind == GenKinds.TEXT2VIDEO || request.kind == GenKinds.TEXT2MUSIC || request.kind == GenKinds.TEXT2SFX) {
            return generateCloud(request.kind, request)
        }
        val cap = list().find { it.kind == request.kind }
            ?: return Outcome.Failure(com.aicodemax.core.common.AppError("GEN_KIND", "kind ไม่รู้จัก (${request.kind})"))
        if (!cap.available) {
            return Outcome.Failure(com.aicodemax.core.common.AppError("GEN_UNAVAILABLE", "${cap.title}: ${cap.note}"))
        }
        if (request.kind == GenKinds.STYLIZE && request.inputPath.isBlank()) {
            return Outcome.Failure(com.aicodemax.core.common.AppError("GEN_INPUT", "stylize ต้องมี path รูปต้นฉบับ"))
        }
        if (request.kind == GenKinds.THUMBNAIL && request.inputPath.isBlank()) {
            return Outcome.Failure(com.aicodemax.core.common.AppError("GEN_INPUT", "ปกคลิปต้องมี path วิดีโอต้นฉบับ"))
        }
        if ((request.kind == GenKinds.POSTER || request.kind == GenKinds.TTS) && request.prompt.isBlank()) {
            return Outcome.Failure(com.aicodemax.core.common.AppError("GEN_PROMPT", "${cap.title} ต้องมี prompt/ข้อความ"))
        }
        val (path, kind) = when (request.kind) {
            GenKinds.TTS -> "mem://gen-tts.wav" to MediaKind.AUDIO
            else -> "mem://gen-${request.kind}.png" to MediaKind.IMAGE
        }
        return Outcome.Success(GenResult(path, kind, "ตัวอย่างทดสอบ (ไม่ได้สร้างไฟล์จริง)"))
    }
}
