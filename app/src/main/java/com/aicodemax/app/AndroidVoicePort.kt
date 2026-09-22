package com.aicodemax.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.voice.VoiceInput
import com.aicodemax.tools.voice.VoicePort
import com.aicodemax.tools.voice.VoiceStatus
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * CP-60 Android voice: SpeechRecognizer (STT) + TextToSpeech (TTS), TH/EN.
 * Callers must hold RECORD_AUDIO before [listen]; failures are honest Thai errors.
 */
class AndroidVoicePort(appContext: Context) : VoicePort {
    private val context = appContext.applicationContext
    private val ttsReady = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null

    init {
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady.complete(true)
                } else {
                    ttsReady.complete(false)
                }
            }
        } catch (_: Exception) {
            ttsReady.complete(false)
        }
    }

    override suspend fun status(): Outcome<VoiceStatus> =
        withContext(Dispatchers.IO) {
            val stt = try {
                SpeechRecognizer.isRecognitionAvailable(context)
            } catch (_: Exception) {
                false
            }
            val ttsOk = withTimeoutOrNull(3_000) { ttsReady.await() } == true
            val detail = buildList {
                if (!stt) add("no recognizer")
                if (!ttsOk) add("no tts engine")
            }.joinToString(", ")
            Outcome.Success(VoiceStatus(sttAvailable = stt, ttsAvailable = ttsOk, detail = detail))
        }

    override suspend fun listen(lang: String, timeoutMs: Long): Outcome<VoiceInput> {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return Outcome.Failure(AppError("VOICE_NO_STT", "เครื่องนี้ไม่มีตัวฟังเสียง (ต้องมีแอป Google / speech engine)"))
        }
        val heard = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                try {
                    suspendCancellableCoroutine<Outcome<VoiceInput>> { cont ->
                    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    cont.invokeOnCancellation {
                        try {
                            recognizer.cancel()
                            recognizer.destroy()
                        } catch (_: Exception) {
                        }
                    }
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        override fun onResults(results: Bundle?) {
                            val heard = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull().orEmpty()
                            val conf = results
                                ?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                                ?.firstOrNull() ?: -1f
                            recognizer.destroy()
                            if (heard.isBlank()) {
                                cont.resume(Outcome.Failure(AppError("VOICE_EMPTY", "ไม่ได้ยินเสียงพูด ลองใหม่อีกครั้ง")))
                            } else {
                                cont.resume(Outcome.Success(VoiceInput(heard, lang, conf)))
                            }
                        }

                        override fun onError(error: Int) {
                            recognizer.destroy()
                            cont.resume(Outcome.Failure(AppError("VOICE_LISTEN", sttErrorThai(error))))
                        }
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    recognizer.startListening(intent)
                }
                } catch (e: SecurityException) {
                    Outcome.Failure(AppError("VOICE_NO_MIC", "แอปยังไม่ได้รับสิทธิ์ไมโครโฟน: ${e.message}"))
                } catch (e: Exception) {
                    Outcome.Failure(AppError("VOICE_LISTEN", "ฟังเสียงไม่ได้: ${e.message}"))
                }
            }
        }
        return heard ?: Outcome.Failure(
            AppError("VOICE_TIMEOUT", "หมดเวลารอเสียงพูด ($timeoutMs ms)"),
        )
    }

    override suspend fun speak(text: String, lang: String): Outcome<Unit> {
        if (text.isBlank()) {
            return Outcome.Failure(AppError("VOICE_EMPTY", "ไม่มีข้อความให้พูด"))
        }
        val engine = tts
        if (withTimeoutOrNull(3_000) { ttsReady.await() } != true || engine == null) {
            return Outcome.Failure(AppError("VOICE_NO_TTS", "เครื่องนี้ไม่มีตัวอ่านออกเสียง (TTS engine)"))
        }
        return withContext(Dispatchers.Main) {
            try {
                val locale = Locale.forLanguageTag(lang.ifBlank { "th-TH" })
                val langResult = engine.setLanguage(locale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.language = Locale.ENGLISH
                }
                suspendCancellableCoroutine<Outcome<Unit>> { cont ->
                    val utteranceId = UUID.randomUUID().toString()
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onError(utteranceId: String?) {
                            if (cont.isActive) {
                                cont.resume(Outcome.Failure(AppError("VOICE_SPEAK", "อ่านออกเสียงไม่สำเร็จ")))
                            }
                        }

                        override fun onDone(doneId: String?) {
                            if (cont.isActive) cont.resume(Outcome.Success(Unit))
                        }
                    })
                    cont.invokeOnCancellation { engine.stop() }
                    val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                    if (queued != TextToSpeech.SUCCESS && cont.isActive) {
                        cont.resume(Outcome.Failure(AppError("VOICE_SPEAK", "เริ่มพูดไม่ได้ (engine error)")))
                    }
                }
            } catch (e: Exception) {
                Outcome.Failure(AppError("VOICE_SPEAK", "พูดไม่ได้: ${e.message}"))
            }
        }
    }

    override suspend fun stop(): Outcome<Unit> {
        return try {
            withContext(Dispatchers.Main) { tts?.stop() }
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Failure(AppError("VOICE_STOP", "หยุดเสียงไม่ได้: ${e.message}"))
        }
    }

    private fun sttErrorThai(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ไมโครโฟนมีปัญหา"
        SpeechRecognizer.ERROR_CLIENT -> "แอปขัดจังหวะการฟัง"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ไม่มีสิทธิ์ใช้ไมโครโฟน"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "ต้องต่อเน็ตเพื่อถอดเสียง (หรือตั้งค่า offline voice)"
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "ไม่ได้ยินเสียงพูด ลองพูดให้ชัดแล้วลองใหม่"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ตัวฟังเสียงกำลังไม่ว่าง ลองใหม่ในครู่เดียว"
        SpeechRecognizer.ERROR_SERVER -> "เซิร์ฟเวอร์เสียงมีปัญหา"
        else -> "ฟังเสียงไม่ได้ (code $code)"
    }
}
