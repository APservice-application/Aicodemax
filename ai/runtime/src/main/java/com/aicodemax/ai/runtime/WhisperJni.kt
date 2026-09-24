package com.aicodemax.ai.runtime

/**
 * CP-140: Kotlin edge of the whisper JNI bridge
 * (`app/src/main/cpp/whisper_jni.cpp`).
 *
 * The native library exists only on Android (CI builds
 * `libaicode_whisper.so` for arm64). On plain JVM the load fails and
 * [available] is false — every call then fails honestly instead of
 * crashing.
 */
object WhisperJni {
    val available: Boolean by lazy {
        try {
            // Single self-contained lib (whisper + ggml statically linked).
            System.loadLibrary("aicode_whisper")
            // Sanity: the version symbol must resolve.
            nativeVersion()
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun version(): String = if (available) nativeVersion() else "jni unavailable (not on Android)"

    fun lastError(): String = if (available) nativeLastError() else "jni unavailable (not on Android)"

    // NOTE: these must stay public — Kotlin mangles `internal` member names,
    // which would break the fixed JNI symbols in whisper_jni.cpp.
    external fun nativeVersion(): String
    external fun nativeLastError(): String

    external fun nativeLoad(path: String, threads: Int): Long

    /**
     * Transcribe 16 kHz mono float PCM.
     * @param lang "th", "en", … or "auto" for language detection.
     * @return JSON `{"segments":[{"t0":ms,"t1":ms,"text":"..."}]}` or null on failure.
     */
    external fun nativeTranscribe(handle: Long, samples: FloatArray, lang: String, translate: Boolean): String?

    external fun nativeUnload(handle: Long)
}
