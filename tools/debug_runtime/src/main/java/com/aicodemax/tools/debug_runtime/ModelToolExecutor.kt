package com.aicodemax.tools.debug_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.runtime.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gateway executor for on-device model files (actions: status/download).
 *
 * CP-128: the localhost serve/stop/ask path is REMOVED per spec §42.4/42.5 —
 * inference runs in-process via JNI (AiRuntimeManager). This tool only
 * delivers and reports model files.
 */
class ModelToolExecutor(
    private val modelsDir: String,
    private val downloader: ModelStore.Downloader = ModelStore.urlDownloader(),
) : ToolExecutor {
    override val toolId: String = "model"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "status" -> {
                    val jniLine = "รันไทม์: JNI in-process (AiRuntimeManager)"
                    val modelLine = when (val st = ModelStore.status(modelsDir)) {
                        is ModelStore.ModelStatus.Ready -> "โมเดล: พร้อม (${st.bytes} bytes)"
                        is ModelStore.ModelStatus.Missing -> "โมเดล: ยังไม่มี — ใช้ model.download (~400MB)"
                    }
                    done(true, "$jniLine\n$modelLine")
                }
                "download" -> {
                    var lastLine = ""
                    ModelStore.download(modelsDir, downloader, onProgress = { dn, total ->
                        val pct = total?.let { if (it > 0) " ${(dn * 100 / it)}%" else "" }.orEmpty()
                        lastLine = "โหลดแล้ว ${dn / 1_048_576}MB$pct"
                    }).fold(
                        onSuccess = { done(true, "โมเดลพร้อม: ${it.path} (${it.length()} bytes)") },
                        onFailure = { done(false, error = it.message + if (lastLine.isNotEmpty()) " ($lastLine)" else "") },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}' (have: status/download)")
            }
        }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
