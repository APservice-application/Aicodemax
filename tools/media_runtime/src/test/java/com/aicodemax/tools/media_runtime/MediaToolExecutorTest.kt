package com.aicodemax.tools.media_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.media.InMemoryMediaProject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaToolExecutorTest {
    private val media = InMemoryMediaProject()
    private val exec = MediaToolExecutor(media)

    private fun run(action: String, args: Map<String, String> = emptyMap()): ToolResult =
        runBlocking {
            (exec.execute(ToolCall(id = "c1", toolId = "media", action = action, args = args)) as Outcome.Success<ToolResult>).value
        }

    @Test
    fun projectLifecycle() {
        val created = run("project.create", mapOf("name" to "demo"))
        assertTrue(created.ok)
        val listed = run("project.list")
        assertTrue(listed.ok && listed.output.contains("demo"))
    }

    @Test
    fun assetTimelineVersionFlow(): Unit = runBlocking {
        val projectId = (media.createProject("flow") as Outcome.Success<com.aicodemax.data.media.Project>).value.id
        val imported = run("asset.import", mapOf("projectId" to projectId, "path" to "a.mp4"))
        assertTrue(imported.ok)
        val assets = run("asset.list", mapOf("projectId" to projectId))
        assertTrue(assets.ok && assets.output.contains("a.mp4"))
        val assetId = assets.output.substringBefore(" |")
        val clip = run(
            "timeline.addClip",
            mapOf("projectId" to projectId, "assetId" to assetId, "startMs" to "0", "endMs" to "2000", "atMs" to "0"),
        )
        assertTrue(clip.ok)
        val timeline = run("timeline.get", mapOf("projectId" to projectId))
        assertTrue(timeline.ok && timeline.output.contains("2000ms"))
        assertTrue(run("version.save", mapOf("projectId" to projectId)).ok)
        val versions = run("version.list", mapOf("projectId" to projectId))
        assertTrue(versions.ok && versions.output.contains("1"))
        assertTrue(run("version.restore", mapOf("projectId" to projectId, "version" to "1")).ok)
    }

    @Test
    fun cp71UndoCheckpointMgmtFlow(): Unit = runBlocking {
        val projectId = (media.createProject("undo-flow") as Outcome.Success<com.aicodemax.data.media.Project>).value.id
        assertTrue(run("project.rename", mapOf("projectId" to projectId, "name" to "renamed")).ok)
        assertTrue(run("edit.undo", mapOf("projectId" to projectId)).output.contains("เปลี่ยนชื่อ"))
        assertTrue(run("edit.redo", mapOf("projectId" to projectId)).ok)
        assertTrue(run("checkpoint.save", mapOf("projectId" to projectId, "reason" to "t")).ok)
        assertTrue(run("checkpoint.list", mapOf("projectId" to projectId)).output.contains("(t)"))
        assertTrue(run("checkpoint.recover", mapOf("projectId" to projectId)).ok)
        val history = run("edit.history", mapOf("projectId" to projectId))
        assertTrue(history.ok && history.output.contains("PROJECT_CREATED"))
        assertTrue(run("project.duplicate", mapOf("projectId" to projectId)).ok)
        assertTrue(run("project.backup", mapOf("projectId" to projectId)).ok)
        assertTrue(run("project.delete", mapOf("projectId" to projectId)).ok)
        val trash = run("project.trash")
        assertTrue(trash.ok && trash.output.contains(projectId))
        val trashId = trash.output.lines().first { it.contains(projectId) }.trim()
        assertTrue(run("project.restore", mapOf("trashId" to trashId)).ok)
    }

    @Test
    fun missingArgsAreHonest() {
        assertTrue(!run("asset.import", emptyMap()).ok)
        assertTrue(!run("timeline.addClip", mapOf("projectId" to "x")).ok)
        assertTrue(!run("version.restore", mapOf("projectId" to "x")).ok)
        val r = run("project.delete", mapOf("projectId" to "x"))
        assertTrue(!r.ok)
        assertTrue(r.error.contains("ไม่มีโปรเจกต์"))
    }
}
