package com.aicodemax.tools.render_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.media.InMemoryMediaProject
import com.aicodemax.tools.render.InMemoryRender
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test
    fun cp103hwinfoFlow() = runBlocking {
        val out = RenderToolExecutor(InMemoryRender(), InMemoryMediaProject()).execute(call("hwinfo")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("hwinfo failed") },
        )
        assertTrue(out.output, out.ok)
        assertTrue(out.output.contains("เอนโค้ด"))
        val report = com.aicodemax.tools.render.GpuReport(
            listOf(com.aicodemax.tools.render.CodecInfo("OMX.qcom.enc", "video/avc", true, true)),
            listOf(com.aicodemax.tools.render.CodecInfo("OMX.google.dec", "video/avc", false, false)),
        )
        assertEquals("OMX.qcom.enc", report.hwEncoder)
        assertTrue(report.summary.contains("HW") && report.summary.contains("SW"))
    }

    @Test
    fun directorFlow() = runBlocking {
        val media = InMemoryMediaProject()
        (media.createProject("d") as Outcome.Success<com.aicodemax.data.media.Project>)
        val exec = RenderToolExecutor(InMemoryRender(), media)
        val out = exec.execute(call("director")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("director failed") },
        )
        assertTrue(out.output, out.ok && out.output.contains("ผู้กำกับ"))
        val bare = RenderToolExecutor(InMemoryRender(), InMemoryMediaProject())
        val none = bare.execute(call("director")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("director failed") },
        )
        assertTrue(!none.ok)
    }

    @Test
    fun cacheFlow() = runBlocking {
        val media = InMemoryMediaProject()
        (media.createProject("c") as Outcome.Success<com.aicodemax.data.media.Project>)
        val exec = RenderToolExecutor(InMemoryRender(), media)
        val st = exec.execute(call("cache.status")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("cache.status failed") },
        )
        assertTrue(st.output, st.ok && st.output.contains("แคช"))
        val cl = exec.execute(call("cache.clear")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("cache.clear failed") },
        )
        assertTrue(cl.ok)
    }

    @Test
    fun batchFlow() = runBlocking {
        val media = InMemoryMediaProject()
        val p1 = ((media.createProject("one") as Outcome.Success<com.aicodemax.data.media.Project>).value.id)
        val p2 = ((media.createProject("two") as Outcome.Success<com.aicodemax.data.media.Project>).value.id)
        val exec = RenderToolExecutor(InMemoryRender(), media)
        val batch = exec.execute(call("batch", mapOf("projectIds" to "$p1,$p2"))).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("batch failed") },
        )
        assertTrue(batch.output, batch.ok && batch.output.contains(p1) && batch.output.contains(p2))
        val all = exec.execute(call("batch", mapOf("all" to "true"))).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("batch all failed") },
        )
        assertTrue(all.ok)
        val empty = exec.execute(call("batch")).fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("batch empty failed") },
        )
        assertTrue(!empty.ok)
    }
}
