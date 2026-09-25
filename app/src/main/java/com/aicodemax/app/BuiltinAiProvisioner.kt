package com.aicodemax.app

import android.content.Context
import com.aicodemax.ai.runtime.AiRuntimeManager
import com.aicodemax.ai.runtime.Gguf
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * CP-144 (BUILT-IN AI §2/§18): first-launch provisioner for the bundled AI.
 *
 * The model ships INSIDE the APK (`assets/ai/builtin-model.gguf`, embedded
 * by the Gradle build). On first launch this copies it once into filesDir (local copy with
 * progress — NEVER a network download), validates GGUF, and loads it via
 * [AiRuntimeManager.loadBuiltin] (no ModelManager involved).
 *
 * Dev builds without the asset fall back honestly via [AiRuntimeManager.builtinMissing].
 */
class BuiltinAiProvisioner(
    private val appContext: Context,
    private val aiRuntime: AiRuntimeManager,
    private val destFile: File,
    private val scope: CoroutineScope,
    private val assetPath: String = "ai/builtin-model.gguf",
) {
    fun provision(): Job = scope.launch(Dispatchers.IO) {
        // Already provisioned? Load straight away.
        if (isValid(destFile)) {
            aiRuntime.loadBuiltin(destFile.path)
            return@launch
        }
        // Bundled asset present? (openFd fails cleanly when absent.)
        val assetLen = try {
            appContext.assets.openFd(assetPath).use { it.length }
        } catch (_: Exception) {
            -1L
        }
        if (assetLen <= 0) {
            aiRuntime.builtinMissing()
            return@launch
        }
        // Local copy with progress (first launch only).
        try {
            destFile.parentFile?.mkdirs()
            val tmp = File(destFile.path + ".part")
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        aiRuntime.setProvisionProgress((done.toFloat() / assetLen).coerceIn(0f, 1f))
                    }
                }
            }
            if (!tmp.renameTo(destFile)) {
                tmp.copyTo(destFile, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            aiRuntime.setProvisionProgress(null)
            aiRuntime.provisionFailed("แตกไฟล์ AI ในตัวไม่สำเร็จ: ${e.message}")
            return@launch
        }
        aiRuntime.setProvisionProgress(null)
        if (!isValid(destFile)) {
            aiRuntime.provisionFailed("ไฟล์ AI ในตัวไม่สมบูรณ์หลังแตกไฟล์")
            return@launch
        }
        // CP-147: model swap 0.5B -> Qwen3-4B — remove the orphaned legacy
        // provision (~491MB) so upgraders don't lose storage to a dead file.
        runCatching {
            val legacy = destFile.resolveSibling("builtin-qwen2.5-0.5b-q4_k_m.gguf")
            if (legacy.isFile && legacy.path != destFile.path) legacy.delete()
        }
        aiRuntime.loadBuiltin(destFile.path)
    }

    private fun isValid(file: File): Boolean {
        if (!file.isFile || file.length() < 100_000_000L) return false
        return runCatching { Gguf.read(file) }.isSuccess
    }
}
