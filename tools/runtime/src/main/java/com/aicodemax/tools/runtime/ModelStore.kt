package com.aicodemax.tools.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * CP-120: on-device bootstrap LLM (Qwen2.5-0.5B Q4_K_M, same as the owner's
 * previous app). The ~400MB .gguf is NEVER in git nor in the APK — the app
 * downloads it once into filesDir/models on user request, then serves it via
 * the embedded llama-server binary ([LlamaServer]).
 *
 * Pure JVM: [Downloader] is injected (HttpURLConnection impl included).
 */
object ModelStore {
    data class ModelSpec(
        val name: String,
        val url: String,
        val fileName: String,
        val expectedBytes: Long? = null,
    )

    val DEFAULT_MODEL = ModelSpec(
        name = "qwen2.5-0.5b-q4_k_m",
        url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        expectedBytes = 397_000_000L,
    )

    sealed interface ModelStatus {
        data class Ready(val path: String, val bytes: Long) : ModelStatus
        data class Missing(val path: String, val url: String) : ModelStatus
    }

    fun modelFile(modelsDir: String, spec: ModelSpec = DEFAULT_MODEL): File =
        File(modelsDir, spec.fileName)

    fun status(modelsDir: String, spec: ModelSpec = DEFAULT_MODEL): ModelStatus {
        val file = modelFile(modelsDir, spec)
        return if (file.isFile && file.length() > 0) ModelStatus.Ready(file.path, file.length())
        else ModelStatus.Missing(file.path, spec.url)
    }

    fun interface Downloader {
        /** Fetch [url] into [dest] (temp file); calls [onProgress] with (doneBytes, totalBytes?). */
        fun fetch(url: String, dest: File, onProgress: (done: Long, total: Long?) -> Unit)
    }

    /** Real downloader (HttpURLConnection, resume via Range when possible). */
    fun urlDownloader(connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 30_000): Downloader =
        Downloader { url, dest, onProgress ->
            dest.parentFile?.mkdirs()
            val startAt = if (dest.isFile) dest.length() else 0L
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", "Aicodemax/1.0")
                if (startAt > 0) setRequestProperty("Range", "bytes=$startAt-")
                instanceFollowRedirects = true
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..206) throw IllegalStateException("download ล้มเหลว HTTP $code ($url)")
            val append = code == 206 && startAt > 0
            val total = conn.getHeaderFieldLong("Content-Length", -1)
                .takeIf { it > 0 }?.let { if (append) it + startAt else it }
            conn.inputStream.use { input ->
                java.io.FileOutputStream(dest, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = if (append) startAt else 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            conn.disconnect()
        }

    fun download(
        modelsDir: String,
        downloader: Downloader,
        spec: ModelSpec = DEFAULT_MODEL,
        onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): Outcome<File> = runOutcome("MODEL_DOWNLOAD") {
        val dest = modelFile(modelsDir, spec)
        if (dest.isFile && dest.length() > 0) {
            val min = spec.expectedBytes?.let { (it * 0.95).toLong() }
            if (min == null || dest.length() >= min) return@runOutcome dest
            dest.delete()
        }
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        downloader.fetch(spec.url, part, onProgress)
        if (!part.isFile || part.length() == 0L) throw IllegalStateException("โหลดเสร็จแต่ไม่มีไฟล์")
        val min = spec.expectedBytes?.let { (it * 0.95).toLong() }
        if (min != null && part.length() < min) {
            throw IllegalStateException("ไฟล์ไม่ครบ (${part.length()} bytes) — ลองใหม่ (resume ต่อจากเดิม)")
        }
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        dest
    }
}
