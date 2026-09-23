package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CP-121: deterministic in-memory [AiRuntime] for unit tests and UI previews.
 * Replies with a scripted echo (never claims to be a real model).
 */
class FakeAiRuntime(
    private val script: (prompt: String) -> String = { prompt -> "สมมติ: ได้รับ “${prompt.take(80)}”" },
    private val streamPieces: Int = 3,
) : AiRuntime {
    override val engineId: String = "fake/1.0"

    @Volatile
    private var model: ModelInfo? = null
    private val generating = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var params: GenParams = GenParams()

    override fun loadModel(path: String, opts: LoadOpts): Outcome<ModelInfo> = runOutcome("AI_LOAD") {
        val file = File(path)
        if (!file.isFile) throw IllegalArgumentException("ไม่พบไฟล์โมเดล: $path")
        ModelInfo(path, file.name, file.length(), opts.ctxSize).also { model = it }
    }

    override fun generate(prompt: String, params: GenParams, onToken: TokenSink): Outcome<GenResult> =
        runOutcome("AI_GENERATE") {
            val current = model ?: throw IllegalStateException("ยังไม่โหลดโมเดล")
            if (prompt.isBlank()) throw IllegalArgumentException("missing prompt")
            if (!generating.compareAndSet(false, true)) throw IllegalStateException("กำลัง generate อยู่แล้ว")
            try {
                stopRequested.set(false)
                val full = script(prompt).take(params.maxTokens.coerceAtLeast(1) * 4)
                val step = (full.length / streamPieces.coerceAtLeast(1)).coerceAtLeast(1)
                val sb = StringBuilder()
                var i = 0
                while (i < full.length) {
                    if (stopRequested.get()) {
                        return@runOutcome GenResult(sb.toString(), stoppedEarly = true)
                    }
                    val next = minOf(i + step, full.length)
                    val piece = full.substring(i, next)
                    sb.append(piece)
                    onToken.onToken(piece)
                    i = next
                }
                GenResult(sb.toString(), stoppedEarly = false)
            } finally {
                generating.set(false)
            }
        }

    override fun stopGeneration() {
        stopRequested.set(true)
    }

    override fun unloadModel(): Outcome<Unit> = runOutcome("AI_UNLOAD") {
        stopGeneration()
        model = null
    }

    override fun isModelLoaded(): Boolean = model != null

    override fun getModelInfo(): ModelInfo? = model

    override fun getRuntimeInfo(): RuntimeInfo =
        RuntimeInfo(engineId, isModelLoaded(), generating.get())

    override fun setParams(params: GenParams) {
        this.params = params
    }

    fun currentParams(): GenParams = params
}
