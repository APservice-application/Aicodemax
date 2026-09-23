package com.aicodemax.tools.debug_runtime

import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.core.common.Outcome
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
    )

    private fun run(exec: ModelToolExecutor, action: String, args: Map<String, String> = emptyMap()): ToolResult =
        runBlocking {
            (exec.execute(ToolCall(id = "m1", toolId = "model", action = action, args = args)) as Outcome.Success<ToolResult>).value
        }

    @Test
    fun statusReportsRuntimeAndModel() {
        val res = run(exec(), "status")
        assertTrue(res.ok)
        assertTrue(res.output.contains("JNI"))
        assertTrue(res.output.contains("โมเดล"))
    }

    @Test
    fun downloadErrorIsHonest() {
        val res = run(exec(), "download")
        assertTrue(!res.ok)
        assertTrue(res.error.contains("no net"))
    }

    @Test
    fun unknownActionIsHonest() {
        val res = run(exec(), "ask")
        assertTrue(!res.ok)
        assertTrue(res.error.contains("unknown action"))
    }
}
