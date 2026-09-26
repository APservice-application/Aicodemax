package com.aicodemax.app

import android.content.Context
import com.aicodemax.ai.runtime.AiRuntimeManager
import com.aicodemax.ai.runtime.BuiltinModelParts
import com.aicodemax.ai.runtime.Gguf
import com.aicodemax.ai.runtime.ModelCatalog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
        val expected = ModelCatalog.DEFAULT_MODEL
        if (manifest.modelId != expected.id || manifest.totalBytes != expected.expectedBytes ||
            manifest.sha256 != expected.expectedSha256) {
            aiRuntime.provisionFailed("โมเดลใน APK ไม่ตรงกับ Qwen3-1.7B ที่ตรวจสอบไว้ — ติดตั้ง APK ที่สมบูรณ์")
            return@launch
        }
        // Already provisioned for THIS model? Never load the previous 4B by accident.
        if (isValid(destFile) && destFile.length() == manifest.totalBytes) {
            removeOldBundledModels()
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
        // Valid new APK and all parts present. Free the obsolete 2.5GB 4B
        // provision before writing 1.1GB, or low-storage upgrades can fail.
        removeOldBundledModels()
        val tmp = File(destFile.path + ".part")
        // Local streaming join with progress (first launch only).
        try {
            destFile.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                for (name in parts) {
                    appContext.assets.open("${BuiltinModelParts.ASSET_DIR}/$name").use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            done += n
                            aiRuntime.setProvisionProgress((done.toFloat() / manifest.totalBytes).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            val receivedBytes = tmp.length()
            if (receivedBytes != manifest.totalBytes) {
                tmp.delete()
                aiRuntime.provisionFailed("ไฟล์ AI ในตัวไม่ครบ ($receivedBytes/${manifest.totalBytes} bytes)")
                return@launch
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (actualHash != manifest.sha256) {
                tmp.delete()
                aiRuntime.provisionFailed("ไฟล์โมเดลใน APK ไม่ผ่าน SHA-256 — หยุดโหลดเพื่อความปลอดภัย")
                return@launch
            }
            if (!tmp.renameTo(destFile)) {
                tmp.copyTo(destFile, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete() // interrupted/corrupt local join must not consume another 1.1GB
            aiRuntime.provisionFailed("แตกไฟล์ AI ในตัวไม่สำเร็จ: ${e.message}")
            return@launch
        }
        aiRuntime.setProvisionProgress(null)
        if (!isValid(destFile)) {
            aiRuntime.provisionFailed("ไฟล์ AI ในตัวไม่สมบูรณ์หลังแตกไฟล์")
            return@launch
        }
        aiRuntime.loadBuiltin(destFile.path)
    }

    private fun removeOldBundledModels() {
        for (name in listOf("builtin-qwen3-4b-q4_k_m.gguf", "builtin-qwen2.5-0.5b-q4_k_m.gguf")) {
            val legacy = destFile.resolveSibling(name)
            if (legacy.path != destFile.path && legacy.isFile) runCatching { legacy.delete() }
        }
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
