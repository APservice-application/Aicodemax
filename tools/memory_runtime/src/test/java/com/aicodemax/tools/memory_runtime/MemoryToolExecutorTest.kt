package com.aicodemax.tools.memory_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.memory.FileMemoryStore
import com.aicodemax.data.memory.MemoryEngine
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryToolExecutorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun executor() = MemoryToolExecutor(MemoryEngine(FileMemoryStore(tmp.root)))

    @Test
    fun saveThenRecall() = runBlocking {
        val executor = executor()
        val saved = executor.execute(
            ToolCall("1", "memory", "save", mapOf("key" to "wifi", "value" to "รหัส 1234")),
        )
        assertTrue((saved as Outcome.Success<ToolResult>).value.ok)
        val recalled = executor.execute(ToolCall("2", "memory", "recall", mapOf("key" to "wifi")))
        val value = (recalled as Outcome.Success<ToolResult>).value
        assertTrue(value.ok)
        assertTrue(value.output.contains("รหัส 1234"))
    }

    @Test
    fun unknownKeyIsHonest() = runBlocking {
        val result = executor().execute(ToolCall("3", "memory", "recall", mapOf("key" to "nope")))
        val value = (result as Outcome.Success<ToolResult>).value
        assertTrue(!value.ok)
        assertTrue(value.error.contains("จำไม่ได้"))
    }
}
