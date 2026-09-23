package com.aicodemax.tools.debug_runtime

import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.runtime.LlamaServer
import com.aicodemax.tools.runtime.ModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelToolExecutorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun exec(): ModelToolExecutor = ModelToolExecutor(
        modelsDir = tmp.root.path,
        downloader = ModelStore.Downloader { _, _, _ -> throw IllegalStateException("no net in tests") },
        proc = LlamaServer.ProcCtl { _, _ -> 1 },
    )

    private fun run(exec: ModelToolExecutor, action: String, args: Map<String, String> = emptyMap()): ToolResult =
        runBlocking {
            (exec.execute(ToolCall(id = "m1", toolId = "model", action = action, args = args)) as Outcome.Success<ToolResult>).value
        }

    @Test
    fun statusReportsThreeLines() {
        val res = run(exec(), "status")
        assertTrue(res.ok)
        assertTrue(res.output.contains("llama-server"))
        assertTrue(res.output.contains("โมเดล"))
        assertTrue(res.output.contains("เซิร์ฟเวอร์"))
    }

    @Test
    fun askWithoutServeIsHonest() {
        val res = run(exec(), "ask", mapOf("prompt" to "hi"))
        assertTrue(!res.ok)
        assertTrue(res.error.contains("model.serve"))
    }

    @Test
    fun serveWithoutModelIsHonest() {
        val res = run(exec(), "serve")
        assertTrue(!res.ok)
        assertTrue(res.error.contains("model.download"))
    }

    @Test
    fun stopWhenIdleIsOk() {
        val res = run(exec(), "stop")
        assertTrue(res.ok)
    }

    @Test
    fun unknownActionIsHonest() {
        val res = run(exec(), "fly")
        assertTrue(!res.ok)
        assertTrue(res.error.contains("unknown action"))
    }
}
