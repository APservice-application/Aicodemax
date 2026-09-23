package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome

/**
 * CP-121 (spec Phase 2): abstraction over the local inference engine.
 *
 * Implementations: [FakeAiRuntime] (tests/UI previews),
 * [InterimLlamaServerRuntime] (CP-120 localhost transport — INTERIM ONLY,
 * violates spec §42.4/42.5 as a main path, removed at CP-128),
 * JniAiRuntime (CP-123, in-process JNI — the real architecture).
 *
 * All calls are blocking-friendly (implementations run inference on their own
 * threads); callers must never invoke [loadModel]/[generate] on the UI thread.
 */
interface AiRuntime {
    /** Human-readable engine id, e.g. "fake/1.0", "jni-llama/b1234". */
    val engineId: String

    fun loadModel(path: String, opts: LoadOpts = LoadOpts()): Outcome<ModelInfo>

    /**
     * Generate a completion. Calls [onToken] for each streamed piece (at least
     * once on success, even for non-streaming engines). Honors [stopGeneration].
     */
    fun generate(prompt: String, params: GenParams = GenParams(), onToken: TokenSink = TokenSink {}): Outcome<GenResult>

    /** Request cancellation of an in-flight [generate]; best-effort. */
    fun stopGeneration()

    fun unloadModel(): Outcome<Unit>

    fun isModelLoaded(): Boolean

    fun getModelInfo(): ModelInfo?

    fun getRuntimeInfo(): RuntimeInfo

    fun setParams(params: GenParams)
}

/** Callback for streamed generation pieces. Return value is ignored. */
fun interface TokenSink {
    fun onToken(piece: String)
}

data class LoadOpts(
    val ctxSize: Int = 2048,
    val threads: Int = 4,
    val batchSize: Int = 512,
)

data class GenParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val maxTokens: Int = 512,
    val stopSequences: List<String> = emptyList(),
    val systemPrompt: String = "You are a helpful assistant. Reply in Thai.",
)

data class ModelInfo(
    val path: String,
    val name: String,
    val bytes: Long,
    val ctxSize: Int,
    val extra: Map<String, String> = emptyMap(),
)

data class RuntimeInfo(
    val engineId: String,
    val modelLoaded: Boolean,
    val generating: Boolean,
)

data class GenResult(
    val text: String,
    val stoppedEarly: Boolean = false,
    val promptTokens: Int = -1,
    val completionTokens: Int = -1,
)
