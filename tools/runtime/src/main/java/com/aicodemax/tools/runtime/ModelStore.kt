package com.aicodemax.tools.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * CP-120: model-download compatibility path. The current Qwen3-1.7B Q4_K_M
 * ships in the APK via BuiltinAiProvisioner; this optional download path
 * remains for repair/installation. Inference runs in-process via JNI.
 * Pure JVM: [Downloader] is injected (HttpURLConnection impl included).
 */
object ModelStore {
    data class ModelSpec(
        val name: String,
        val url: String,
        val fileName: String,
        val expectedBytes: Long? = null,
        val expectedSha256: String? = null,
    )

    val DEFAULT_MODEL = ModelSpec(
        name = "qwen3-1.7b-q4_k_m",
        url = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/" +
            "cc27747d7419139e44ba97777c2f2fd5dca92ee1/Qwen3-1.7B-Q4_K_M.gguf",
        fileName = "Qwen3-1.7B-Q4_K_M.gguf",
        expectedBytes = 1_107_409_376L,
        expectedSha256 = "ba491cf470c3cadc624e4c8d6c9a27c998809e8ba8eb938d1689ae87e024b6b7",
    )

    private fun matchesHash(file: File, expected: String?): Boolean {
        if (expected == null) return true
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return actual == expected
    }

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
            if ((min == null || dest.length() >= min) && matchesHash(dest, spec.expectedSha256)) {
                return@runOutcome dest
            }
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
        if (!matchesHash(part, spec.expectedSha256)) {
            part.delete() // never resume a complete but corrupt model next time
            throw IllegalStateException("SHA-256 ของโมเดลไม่ตรงกับไฟล์ที่ตรึงไว้")
        }
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        dest
    }
}
