package com.aicodemax.tools.debug_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.runtime.LlamaServer
import com.aicodemax.tools.runtime.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CP-120: gateway executor for the on-device bootstrap model
 * (actions: status/download/serve/stop/ask).
 */
class ModelToolExecutor(
    private val modelsDir: String,
    private val nativeLibDir: String? = null,
    private val downloader: ModelStore.Downloader = ModelStore.urlDownloader(),
    private val proc: LlamaServer.ProcCtl = LlamaServer.ProcCtl { _, _ -> -1 },
) : ToolExecutor {
    override val toolId: String = "model"

    @Volatile
    private var server: LlamaServer.Handle? = null

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "status" -> {
                    val tools = com.aicodemax.tools.runtime.NativeToolchain.resolve(nativeLibDir)
                    val llama = tools["llama-server"]
                    val toolLine = if (llama != null && llama.isFile) "llama-server: ฝังแล้ว" else "llama-server: ยังไม่ฝัง"
                    val modelLine = when (val st = ModelStore.status(modelsDir)) {
                        is ModelStore.ModelStatus.Ready -> "โมเดล: พร้อม (${st.bytes} bytes)"
                        is ModelStore.ModelStatus.Missing -> "โมเดล: ยังไม่มี — ใช้ model.download (~400MB)"
                    }
                    val serveLine = server?.let {
                        if (health(it)) "เซิร์ฟเวอร์: รันอยู่ ${it.baseUrl}" else "เซิร์ฟเวอร์: เริ่มแล้วแต่ยังไม่ตอบ (รอโหลดโมเดล?)"
                    } ?: "เซิร์ฟเวอร์: ยังไม่รัน"
                    done(true, "$toolLine\n$modelLine\n$serveLine")
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
                "serve" -> {
                    server?.let {
                        if (health(it)) return@withContext done(true, "รันอยู่แล้ว ${it.baseUrl}")
                    }
                    val model = when (val st = ModelStore.status(modelsDir)) {
                        is ModelStore.ModelStatus.Ready -> st.path
                        is ModelStore.ModelStatus.Missing ->
                            return@withContext done(false, error = "ยังไม่มีโมเดล — ใช้ model.download ก่อน")
                    }
                    val opts = LlamaServer.ServeOpts(
                        port = call.args["port"]?.toIntOrNull() ?: LlamaServer.DEFAULT_PORT,
                        threads = call.args["threads"]?.toIntOrNull() ?: 4,
                        ctxSize = call.args["ctx"]?.toIntOrNull() ?: 2048,
                    )
                    LlamaServer.serve(llamaExe(), model, proc, opts).fold(
                        onSuccess = {
                            server = it
                            val ok = health(it)
                            done(true, if (ok) "เซิร์ฟเวอร์พร้อม ${it.baseUrl}" else "เริ่มแล้ว ${it.baseUrl} — รอโหลดโมเดลสักครู่แล้วลอง model.ask")
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "stop" -> {
                    val handle = server ?: return@withContext done(true, "เซิร์ฟเวอร์ไม่ได้รันอยู่")
                    LlamaServer.stop(handle, proc).fold(
                        onSuccess = {
                            server = null
                            done(true, "หยุดเซิร์ฟเวอร์แล้ว")
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "ask" -> {
                    val prompt = call.args["prompt"]
                        ?: return@withContext done(false, error = "missing arg: prompt")
                    val handle = server
                        ?: return@withContext done(false, error = "เซิร์ฟเวอร์ยังไม่รัน — ใช้ model.serve ก่อน")
                    LlamaServer.ask(handle.baseUrl, prompt).fold(
                        onSuccess = { done(true, it) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}' (have: status/download/serve/stop/ask)")
            }
        }

    private fun llamaExe(): String? =
        nativeLibDir?.trim()?.takeUnless { it.isEmpty() }?.let { "$it/libllama-server.so" }

    private fun health(handle: LlamaServer.Handle): Boolean =
        LlamaServer.health(handle.baseUrl)

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
