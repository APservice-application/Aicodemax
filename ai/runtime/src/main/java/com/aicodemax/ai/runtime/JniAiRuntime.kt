package com.aicodemax.ai.runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CP-123: [AiRuntime] over the in-process JNI bridge (spec §3–§4 — the real
 * architecture, no localhost, no subprocess).
 *
 * Chat framing uses the Qwen2.5 ChatML template (the default model family);
 * the native side tokenizes with parse_special so the control tokens work.
 */
class JniAiRuntime(
    private val jni: JniEdge = JniEdge.Real,
) : AiRuntime {
    override val engineId: String = "jni-llama/1.0"

    /**
     * Sealed edge so unit tests can drive this class without native code.
     * [Real] delegates to [AicodeJni]; tests use [Fake].
     */
    interface JniEdge {
        val available: Boolean
        fun load(path: String, ctxSize: Int, threads: Int): Long
        fun generate(
            handle: Long, prompt: String, maxTokens: Int, temperature: Float,
            topP: Float, topK: Int, stops: Array<String>, sink: AicodeJni.TokenCallback?,
        ): String
        fun stop(handle: Long)
        fun unload(handle: Long)
        fun info(handle: Long): String
        fun lastError(): String

        object Real : JniEdge {
            override val available: Boolean get() = AicodeJni.available
            override fun load(path: String, ctxSize: Int, threads: Int): Long =
                AicodeJni.nativeLoad(path, ctxSize, threads)
            override fun generate(
                handle: Long, prompt: String, maxTokens: Int, temperature: Float,
                topP: Float, topK: Int, stops: Array<String>, sink: AicodeJni.TokenCallback?,
            ): String = AicodeJni.nativeGenerate(handle, prompt, maxTokens, temperature, topP, topK, stops, sink)
            override fun stop(handle: Long) = AicodeJni.nativeStop(handle)
            override fun unload(handle: Long) = AicodeJni.nativeUnload(handle)
            override fun info(handle: Long): String = AicodeJni.nativeInfo(handle)
            override fun lastError(): String = AicodeJni.lastError()
        }
    }

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var model: ModelInfo? = null
    private val generating = AtomicBoolean(false)

    @Volatile
    private var params: GenParams = GenParams()

    override fun loadModel(path: String, opts: LoadOpts): Outcome<ModelInfo> = runOutcome("AI_LOAD") {
        if (!jni.available) throw IllegalStateException("JNI runtime ใช้ได้บน Android เท่านั้น (JVM นี้ไม่มี native lib)")
        val file = File(path)
        if (!file.isFile) throw IllegalArgumentException("ไม่พบไฟล์โมเดล: $path")
        unloadModel()
        val h = jni.load(path, opts.ctxSize, opts.threads)
        if (h == 0L) throw IllegalStateException("โหลดโมเดลไม่สำเร็จ: ${jni.lastError()}")
        handle = h
        ModelInfo(path, file.name, file.length(), opts.ctxSize, mapOf("native" to jni.info(h))).also { model = it }
    }

    override fun generate(prompt: String, params: GenParams, onToken: TokenSink): Outcome<GenResult> =
        runOutcome("AI_GENERATE") {
            val h = handle
            if (h == 0L || model == null) throw IllegalStateException("ยังไม่โหลดโมเดล")
            if (prompt.isBlank()) throw IllegalArgumentException("missing prompt")
            if (!generating.compareAndSet(false, true)) throw IllegalStateException("กำลัง generate อยู่แล้ว")
            try {
                // CP-144: prompt arrives FULLY templated from the caller
                // (AiRuntimeManager/LocalModelProvider apply ChatTemplate::qwen25).
                // Never wrap again — double ChatML breaks the model (audit W1).
                val stops = (params.stopSequences + "<|im_end|>").toSet().toTypedArray()
                val sink = AicodeJni.TokenCallback { piece -> onToken.onToken(piece) }
                val text = jni.generate(h, prompt, params.maxTokens, params.temperature, params.topP, params.topK, stops, sink)
                val err = jni.lastError()
                if (text.isEmpty() && err.isNotEmpty()) throw IllegalStateException("generate ล้มเหลว: $err")
                if (text.isBlank()) throw IllegalStateException("โมเดลตอบว่าง — ลองใหม่ (ถ้าเป็นซ้ำให้ recover)")
                GenResult(text)
            } finally {
                generating.set(false)
            }
        }

    override fun stopGeneration() {
        val h = handle
        if (h != 0L && jni.available) {
            try {
                jni.stop(h)
            } catch (_: Exception) {
                // Best-effort only.
            }
        }
    }

    override fun unloadModel(): Outcome<Unit> = runOutcome("AI_UNLOAD") {
        val h = handle
        handle = 0L
        model = null
        if (h != 0L && jni.available) jni.unload(h)
    }

    override fun isModelLoaded(): Boolean = model != null && handle != 0L

    override fun getModelInfo(): ModelInfo? = model

    override fun getRuntimeInfo(): RuntimeInfo =
        RuntimeInfo(engineId, isModelLoaded(), generating.get())

    override fun setParams(params: GenParams) {
        this.params = params
    }
}
