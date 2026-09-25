package com.aicodemax.ai.runtime

/**
 * CP-123: Kotlin edge of the JNI bridge (`app/src/main/cpp/aicode_jni.cpp`).
 *
 * The native library exists only on Android (the Gradle build compiles
 * `libaicode_jni.so` for arm64 via CMake/NDK). On plain JVM the load fails and [available] is
 * false — every call then fails honestly instead of crashing.
 */
object AicodeJni {
    /** Sink interface the native code calls back per streamed piece. */
    fun interface TokenCallback {
        fun onToken(piece: String)
    }

    /**
     * CP-145: why the native lib failed to load (null when [available]).
     * Surfaced in diagnostics so local builds explain themselves instead of
     * claiming "Android only" on a real Android device.
     */
    var loadError: String? = null
        private set

    val available: Boolean by lazy {
        try {
            // Single self-contained lib (llama + ggml statically linked).
            System.loadLibrary("aicode_jni")
            // Sanity: the version symbol must resolve.
            nativeVersion()
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message
            false
        } catch (e: SecurityException) {
            loadError = e.message
            false
        }
    }

    fun version(): String = if (available) nativeVersion() else "jni unavailable (not on Android)"

    fun lastError(): String = if (available) nativeLastError() else "jni unavailable (not on Android)"

    // NOTE: these must stay public — Kotlin mangles `internal` member names,
    // which would break the fixed JNI symbols in aicode_jni.cpp.
    external fun nativeVersion(): String
    external fun nativeLastError(): String

    external fun nativeLoad(path: String, ctxSize: Int, threads: Int): Long
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        stops: Array<String>,
        sink: TokenCallback?,
    ): String
    external fun nativeStop(handle: Long)
    external fun nativeUnload(handle: Long)
    external fun nativeInfo(handle: Long): String
}
