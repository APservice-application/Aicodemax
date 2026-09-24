package com.aicodemax.ai.runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.core.common.runOutcome
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lifecycle of the on-device STT engine. */
enum class WhisperState { UNLOADED, LOADING, READY, TRANSCRIBING, ERROR }

/**
 * CP-140: file-STT lifecycle (whisper model + JNI transcription).
 *
 * All work is blocking — callers must run [load]/[transcribe] off the UI
 * thread (the UI uses Dispatchers.IO). [transcribe] auto-loads the model
 * when needed.
 */
class WhisperEngine(
    private val manager: WhisperManager,
    private val edge: WhisperEdge = WhisperEdge.Real,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
) {
    /**
     * Sealed edge so unit tests can drive this class without native code.
     * [Real] delegates to [WhisperJni]; tests use fakes.
     */
    interface WhisperEdge {
        val available: Boolean
        fun load(path: String, threads: Int): Long
        fun transcribe(handle: Long, samples: FloatArray, lang: String, translate: Boolean): String?
        fun unload(handle: Long)
        fun lastError(): String

        object Real : WhisperEdge {
            override val available: Boolean get() = WhisperJni.available
            override fun load(path: String, threads: Int): Long = WhisperJni.nativeLoad(path, threads)
            override fun transcribe(
                handle: Long, samples: FloatArray, lang: String, translate: Boolean,
            ): String? = WhisperJni.nativeTranscribe(handle, samples, lang, translate)
            override fun unload(handle: Long) = WhisperJni.nativeUnload(handle)
            override fun lastError(): String = WhisperJni.lastError()
        }
    }

    private val _state = MutableStateFlow(WhisperState.UNLOADED)
    val state: StateFlow<WhisperState> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var loadedId: String? = null

    private val busy = AtomicBoolean(false)

    /** Load [profile] (blocking). No-op when already loaded. */
    fun load(profile: WhisperProfile = WhisperCatalog.BASE): Outcome<Unit> {
        if (handle != 0L && loadedId == profile.id) return Outcome.Success(Unit)
        if (!edge.available) return fail("STT ใช้ได้บน Android เท่านั้น (ไม่มี native lib)")
        if (!busy.compareAndSet(false, true)) {
            return Outcome.Failure(AppError("STT_BUSY", "กำลังถอดเสียงอยู่ — รอก่อน"))
        }
        try {
            _state.value = WhisperState.LOADING
            _error.value = null
            val file = when (val st = manager.status(profile)) {
                is WhisperStatus.Ready -> st.path
                is WhisperStatus.Missing -> return fail("ยังไม่ติดตั้งโมเดล ${profile.displayName} — ดาวน์โหลดก่อน (~148MB)")
                is WhisperStatus.Invalid -> return fail("โมเดลใช้ไม่ได้: ${st.reason}")
            }
            unloadLocked()
            val h = try {
                edge.load(file, threads)
            } catch (e: Exception) {
                return fail(e.message ?: "โหลดโมเดลไม่สำเร็จ")
            }
            if (h == 0L) return fail(errorOr("โหลดโมเดลไม่สำเร็จ"))
            handle = h
            loadedId = profile.id
            _state.value = WhisperState.READY
            return Outcome.Success(Unit)
        } finally {
            busy.set(false)
        }
    }

    fun unload() {
        if (!busy.compareAndSet(false, true)) return
        try {
            unloadLocked()
        } finally {
            busy.set(false)
        }
    }

    private fun unloadLocked() {
        val h = handle
        handle = 0L
        loadedId = null
        if (h != 0L) {
            try {
                edge.unload(h)
            } catch (_: Exception) {
            }
        }
        if (_state.value != WhisperState.ERROR) _state.value = WhisperState.UNLOADED
    }

    /**
     * Transcribe 16 kHz mono float PCM (blocking, auto-loads when needed).
     * @param lang "th", "en", … or "auto" for language detection.
     */
    fun transcribe(
        samples: FloatArray,
        lang: String = "auto",
        translate: Boolean = false,
        profile: WhisperProfile = WhisperCatalog.BASE,
    ): Outcome<List<WhisperSegment>> {
        if (samples.isEmpty()) {
            return Outcome.Failure(AppError("STT_EMPTY", "ไม่มีข้อมูลเสียงให้ถอด"))
        }
        if (handle == 0L) {
            val loaded = load(profile)
            if (loaded is Outcome.Failure) return loaded.cast()
        }
        if (!busy.compareAndSet(false, true)) {
            return Outcome.Failure(AppError("STT_BUSY", "กำลังถอดเสียงอยู่ — รอก่อน"))
        }
        try {
            _state.value = WhisperState.TRANSCRIBING
            _error.value = null
            val json = try {
                edge.transcribe(handle, samples, lang, translate)
            } catch (e: Exception) {
                return failSegments(e.message ?: "ถอดเสียงไม่สำเร็จ")
            }
            if (json == null) return failSegments(errorOr("ถอดเสียงไม่สำเร็จ"))
            return WhisperSegments.parse(json).fold(
                onSuccess = {
                    _state.value = WhisperState.READY
                    Outcome.Success(it)
                },
                onFailure = { failSegments(it.message) },
            )
        } finally {
            busy.set(false)
            if (_state.value == WhisperState.TRANSCRIBING) _state.value = WhisperState.READY
        }
    }

    private fun fail(message: String): Outcome<Unit> {
        _error.value = message
        _state.value = WhisperState.ERROR
        return Outcome.Failure(AppError("STT_ERROR", message))
    }

    private fun failSegments(message: String): Outcome<List<WhisperSegment>> {
        _error.value = message
        _state.value = WhisperState.ERROR
        return Outcome.Failure(AppError("STT_ERROR", message))
    }

    private fun errorOr(fallback: String): String {
        val detail = try {
            edge.lastError()
        } catch (_: Exception) {
            ""
        }
        return if (detail.isBlank()) fallback else detail
    }

    private fun Outcome<Unit>.cast(): Outcome<List<WhisperSegment>> = when (this) {
        is Outcome.Success -> Outcome.Success(emptyList())
        is Outcome.Failure -> Outcome.Failure(this.error)
    }
}
