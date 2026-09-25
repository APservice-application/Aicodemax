package com.aicodemax.app

import android.content.Context
import com.aicodemax.ai.runtime.AiRuntimeManager
import com.aicodemax.ai.runtime.BuiltinModelParts
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
 * The model ships INSIDE the APK as 512MiB parts + manifest
 * (`assets/ai/builtin-model-partNN.gguf` + `assets/ai/builtin-model.manifest`,
 * embedded by the Gradle build — CP-147: AGP cannot buffer one 2.5GB asset).
 * On first launch this joins them once into filesDir (local streaming join
 * with progress — NEVER a network download), validates GGUF, and loads it via
 * [AiRuntimeManager.loadBuiltin] (no ModelManager involved).
 *
 * Dev builds without the asset fall back honestly via [AiRuntimeManager.builtinMissing].
 */
class BuiltinAiProvisioner(
    private val appContext: Context,
    private val aiRuntime: AiRuntimeManager,
    private val destFile: File,
    private val scope: CoroutineScope,
) {
    fun provision(): Job = scope.launch(Dispatchers.IO) {
        // Read the manifest first — it also tells us when a provisioned file
        // belongs to an older model (size differs -> re-provision).
        val manifest = readManifest()
        if (manifest == null) {
            aiRuntime.builtinMissing()
            return@launch
        }
        // Already provisioned for THIS model? Load straight away.
        if (isValid(destFile) && destFile.length() == manifest.totalBytes) {
            aiRuntime.loadBuiltin(destFile.path)
            return@launch
        }
        val parts = try {
            val listed = appContext.assets.list(BuiltinModelParts.ASSET_DIR)?.toList().orEmpty()
            BuiltinModelParts.orderedParts(manifest, listed)
        } catch (_: Exception) {
            null
        }
        if (parts.isNullOrEmpty()) {
            aiRuntime.builtinMissing()
            return@launch
        }
        // Local streaming join with progress (first launch only).
        try {
            destFile.parentFile?.mkdirs()
            val tmp = File(destFile.path + ".part")
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                for (name in parts) {
                    appContext.assets.open("${BuiltinModelParts.ASSET_DIR}/$name").use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            aiRuntime.setProvisionProgress((done.toFloat() / manifest.totalBytes).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (tmp.length() != manifest.totalBytes) {
                tmp.delete()
                aiRuntime.setProvisionProgress(null)
                aiRuntime.provisionFailed("ไฟล์ AI ในตัวไม่ครบ (${tmp.length()}/${manifest.totalBytes} bytes)")
                return@launch
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

    private fun readManifest(): BuiltinModelParts.Manifest? = try {
        appContext.assets.open(BuiltinModelParts.MANIFEST_PATH).use { input ->
            BuiltinModelParts.parseManifest(input.bufferedReader().readText())
        }
    } catch (_: Exception) {
        null
    }

    private fun isValid(file: File): Boolean {
        if (!file.isFile || file.length() < 100_000_000L) return false
        return runCatching { Gguf.read(file) }.isSuccess
    }
}
