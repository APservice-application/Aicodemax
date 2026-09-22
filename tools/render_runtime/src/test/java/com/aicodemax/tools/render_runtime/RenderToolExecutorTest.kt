package com.aicodemax.tools.render_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.media.InMemoryMediaProject
import com.aicodemax.tools.render.InMemoryRender
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderToolExecutorTest {
    private fun call(action: String, args: Map<String, String> = emptyMap()) =
        ToolCall(id = "t", toolId = "render", action = action, args = args)

    @Test
    fun fullFlowWithFallbacks() = runBlocking {
        val media = InMemoryMediaProject()
        val project = (media.createProject("demo") as Outcome.Success).value
        val exec = RenderToolExecutor(InMemoryRender(), media)

        val enqueued = exec.execute(call("enqueue")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("enqueue failed") },
        )
        assertTrue(enqueued.output, enqueued.ok)

        val run = exec.execute(call("run")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("run failed") },
        )
        assertTrue(run.output, run.ok && run.output.contains("QCผ่าน"))

        val approved = exec.execute(call("approve")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("approve failed") },
        )
        assertTrue(approved.output, approved.ok)

        val exported = exec.execute(call("export")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("export failed") },
        )
        assertTrue(exported.output, exported.ok && exported.output.contains("ส่งออก"))

        val listed = exec.execute(call("list")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("list failed") },
        )
        assertTrue(listed.ok && listed.output.contains(project.id))
    }

    @Test
    fun emptyStatesAreHonest() = runBlocking {
        val exec = RenderToolExecutor(InMemoryRender(), InMemoryMediaProject())
        val noProject = exec.execute(call("runNow")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("runNow failed") },
        )
        assertTrue(!noProject.ok && noProject.error.contains("โปรเจกต์"))
        val media = InMemoryMediaProject()
        media.createProject("demo")
        val exec2 = RenderToolExecutor(InMemoryRender(), media)
        val noJob = exec2.execute(call("approve")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("approve failed") },
        )
        assertTrue(!noJob.ok)
        val unknown = exec2.execute(call("explode")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("explode failed") },
        )
        assertTrue(!unknown.ok && unknown.error.contains("unknown action"))
    }
}
