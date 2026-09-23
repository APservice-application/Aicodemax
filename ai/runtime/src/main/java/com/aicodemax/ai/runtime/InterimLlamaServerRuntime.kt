package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.core.common.runOutcome
import com.aicodemax.tools.runtime.LlamaServer
import java.io.File

/**
 * CP-121: [AiRuntime] adapter over the CP-120 llama-server transport.
 *
 * **INTERIM ONLY — scheduled for removal at CP-128.** localhost + external
 * process violates spec §42.4/42.5 as the main inference architecture; this
 * class exists only so providers/chat can be built against [AiRuntime] before
 * the JNI runtime (CP-123) lands.
 *
 * @param baseUrl supplier for the running server (e.g. from ModelToolExecutor state).
 */
class InterimLlamaServerRuntime(
    private val baseUrl: () -> String?,
) : AiRuntime {
    override val engineId: String = "interim-llama-server/1.0"

    @Volatile
    private var model: ModelInfo? = null

    @Volatile
    private var params: GenParams = GenParams()

    override fun loadModel(path: String, opts: LoadOpts): Outcome<ModelInfo> = runOutcome("AI_LOAD") {
        val file = File(path)
        if (!file.isFile) throw IllegalArgumentException("ไม่พบไฟล์โมเดล: $path")
        val url = baseUrl() ?: throw IllegalStateException("เซิร์ฟเวอร์ยังไม่รัน")
        if (!LlamaServer.health(url)) throw IllegalStateException("เซิร์ฟเวอร์ไม่ตอบ ($url)")
        ModelInfo(path, file.name, file.length(), opts.ctxSize, mapOf("transport" to "interim-http")).also { model = it }
    }

    override fun generate(prompt: String, params: GenParams, onToken: TokenSink): Outcome<GenResult> {
        val current = model ?: return Outcome.Failure(
            com.aicodemax.core.common.AppError("AI_GENERATE", "ยังไม่โหลดโมเดล"),
        )
        val url = baseUrl() ?: return Outcome.Failure(
            com.aicodemax.core.common.AppError("AI_GENERATE", "เซิร์ฟเวอร์ยังไม่รัน"),
        )
        return LlamaServer.ask(url, prompt, params.systemPrompt).fold(
            onSuccess = {
                onToken.onToken(it)
                Outcome.Success(GenResult(it))
            },
            onFailure = { Outcome.Failure(it) },
        )
    }

    override fun stopGeneration() {
        // Interim HTTP transport has no cancellation — documented limitation.
    }

    override fun unloadModel(): Outcome<Unit> = runOutcome("AI_UNLOAD") {
        model = null
    }

    override fun isModelLoaded(): Boolean = model != null

    override fun getModelInfo(): ModelInfo? = model

    override fun getRuntimeInfo(): RuntimeInfo =
        RuntimeInfo(engineId, isModelLoaded(), generating = false)

    override fun setParams(params: GenParams) {
        this.params = params
    }
}
