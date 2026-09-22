package com.aicodemax.tools.voice_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.voice.InMemoryVoicePort
import com.aicodemax.tools.voice.VoicePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for voice (actions: listen/speak/stop/status). */
class VoiceToolExecutor(private val voice: VoicePort = InMemoryVoicePort()) : ToolExecutor {
    override val toolId: String = "voice"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "listen" -> {
                    val lang = call.args["lang"] ?: "th-TH"
                    val timeout = call.args["timeoutMs"]?.toLongOrNull() ?: 15_000L
                    voice.listen(lang, timeout).fold(
                        onSuccess = { input ->
                            done(true, "ได้ยิน (${input.lang}): ${input.text}")
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "speak" -> {
                    val text = call.args["text"]
                        ?: return@withContext done(false, error = "missing arg: text")
                    val lang = call.args["lang"] ?: "th-TH"
                    voice.speak(text, lang).fold(
                        onSuccess = { done(true, "พูดแล้ว (${lang.take(2)}): ${text.take(80)}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "stop" -> voice.stop().fold(
                    onSuccess = { done(true, "หยุดเสียงแล้ว") },
                    onFailure = { done(false, error = it.message) },
                )
                "status" -> voice.status().fold(
                    onSuccess = { status ->
                        val stt = if (status.sttAvailable) "พร้อม" else "ไม่พร้อม"
                        val tts = if (status.ttsAvailable) "พร้อม" else "ไม่พร้อม"
                        done(true, "STT: $stt, TTS: $tts${if (status.detail.isNotBlank()) " (${status.detail})" else ""}")
                    },
                    onFailure = { done(false, error = it.message) },
                )
                else -> done(false, error = "unknown action '${call.action}' (have: listen/speak/stop/status)")
            }
        }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
