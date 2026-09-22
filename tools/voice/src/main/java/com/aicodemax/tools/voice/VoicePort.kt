package com.aicodemax.tools.voice

import com.aicodemax.core.common.Outcome

/** Transcript from speech recognition. */
data class VoiceInput(
    val text: String,
    /** BCP-47 tag the recognizer actually used (e.g. th-TH, en-US). */
    val lang: String,
    /** 0.0..1.0 when the engine reports confidence, else -1. */
    val confidence: Float = -1f,
)

/** Honest runtime status of the voice engines on this device. */
data class VoiceStatus(
    val sttAvailable: Boolean,
    val ttsAvailable: Boolean,
    val detail: String = "",
)

/**
 * CP-60 voice contract: speech-to-text input + text-to-speech output (TH/EN).
 * Android implementation lives in `:app` (SpeechRecognizer + TextToSpeech);
 * tests and headless builds use [InMemoryVoicePort].
 */
interface VoicePort {
    suspend fun status(): Outcome<VoiceStatus>

    /**
     * Listens once on the microphone and returns the transcript.
     * @param lang preferred BCP-47 tag (th-TH/en-US); engine may fall back.
     * @param timeoutMs max time to wait for speech before giving up.
     */
    suspend fun listen(lang: String = "th-TH", timeoutMs: Long = 15_000): Outcome<VoiceInput>

    /** Speaks [text] aloud. @param lang BCP-47 tag for voice selection. */
    suspend fun speak(text: String, lang: String = "th-TH"): Outcome<Unit>

    /** Stops any in-progress speech output. */
    suspend fun stop(): Outcome<Unit>

    /**
     * CP-83 §46: renders [text] to a WAV [path] (offline TTS file output).
     * Default: unsupported (headless/JVM ports have no TTS engine).
     */
    suspend fun speakToFile(text: String, lang: String = "th-TH", path: String = ""): Outcome<Unit> =
        Outcome.Failure(com.aicodemax.core.common.AppError("VOICE_NO_FILE", "port นี้บันทึกเสียงพูดเป็นไฟล์ไม่ได้"))
}

/** Scriptable fake: feeds transcripts to [listen], records [speak] calls. */
class InMemoryVoicePort(
    var available: VoiceStatus = VoiceStatus(sttAvailable = true, ttsAvailable = true),
    private val script: ArrayDeque<VoiceInput> = ArrayDeque(),
) : VoicePort {
    val spoken: MutableList<Pair<String, String>> = mutableListOf()
    var stopCalls: Int = 0
        private set

    fun feed(input: VoiceInput) {
        script.addLast(input)
    }

    override suspend fun status(): Outcome<VoiceStatus> = Outcome.Success(available)

    override suspend fun listen(lang: String, timeoutMs: Long): Outcome<VoiceInput> {
        if (!available.sttAvailable) {
            return Outcome.Failure(com.aicodemax.core.common.AppError("VOICE_NO_STT", "speech recognition not available"))
        }
        val next = script.removeFirstOrNull()
            ?: return Outcome.Failure(com.aicodemax.core.common.AppError("VOICE_TIMEOUT", "no speech heard"))
        return Outcome.Success(next)
    }

    override suspend fun speak(text: String, lang: String): Outcome<Unit> {
        if (!available.ttsAvailable) {
            return Outcome.Failure(com.aicodemax.core.common.AppError("VOICE_NO_TTS", "speech output not available"))
        }
        if (text.isBlank()) {
            return Outcome.Failure(com.aicodemax.core.common.AppError("VOICE_EMPTY", "nothing to speak"))
        }
        spoken.add(text to lang)
        return Outcome.Success(Unit)
    }

    override suspend fun stop(): Outcome<Unit> {
        stopCalls += 1
        return Outcome.Success(Unit)
    }
}
