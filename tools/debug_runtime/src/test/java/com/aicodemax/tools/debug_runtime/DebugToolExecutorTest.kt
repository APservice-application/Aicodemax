package com.aicodemax.tools.debug_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugToolExecutorTest {
    private val executor = DebugToolExecutor()

    @Test
    fun analyzeFindsCause() = runBlocking {
        val trace = "java.lang.NullPointerException: boom\n" +
            "\tat com.example.App.run(App.kt:42)\n" +
            "\tat android.app.Activity.main(Activity.java:1)\n"
        val result = executor.execute(ToolCall("1", "debug", "analyze", mapOf("error" to trace)))
        assertTrue(result is Outcome.Success)
        val value = (result as Outcome.Success<ToolResult>).value
        assertTrue(value.ok)
        assertTrue(value.output.contains("NullPointerException"))
        assertTrue(value.output.contains("com.example.App"))
    }

    @Test
    fun benchReports() = runBlocking {
        val result = executor.execute(ToolCall("9", "debug", "bench", mapOf("quick" to "true")))
        val value = (result as Outcome.Success<ToolResult>).value
        assertTrue(value.output.ifBlank { value.error }, value.ok && value.output.contains("เบนช์มาร์ก"))
    }

    @Test
    fun missingArgIsHonest() = runBlocking {
        val result = executor.execute(ToolCall("2", "debug", "analyze"))
        val value = (result as Outcome.Success<ToolResult>).value
        assertTrue(!value.ok)
        assertTrue(value.error.contains("missing arg"))
    }

    @Test
    fun unknownActionIsHonest() = runBlocking {
        val result = executor.execute(ToolCall("3", "debug", "fix"))
        val value = (result as Outcome.Success<ToolResult>).value
        assertTrue(!value.ok)
        assertTrue(value.error.contains("unknown action"))
    }
}
