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
    fun cp72ClipOpsViaIndex(): Unit = runBlocking {
        val projectId = (media.createProject("clips") as Outcome.Success<com.aicodemax.data.media.Project>).value.id
        assertTrue(run("asset.import", mapOf("projectId" to projectId, "path" to "a.mp4")).ok)
        val assetId = run("asset.list", mapOf("projectId" to projectId)).output.substringBefore(" |")
        assertTrue(run("timeline.addClip", mapOf("projectId" to projectId, "assetId" to assetId, "startMs" to "0", "endMs" to "4000", "atMs" to "0")).ok)
        assertTrue(run("timeline.splitClip", mapOf("projectId" to projectId, "clipIndex" to "1", "atMs" to "2000")).ok)
        val got = run("timeline.get", mapOf("projectId" to projectId))
        assertTrue(got.output.contains("1. V1") && got.output.contains("2. V1"))
        assertTrue(run("timeline.trackFlags", mapOf("projectId" to projectId, "trackId" to "V1", "muted" to "เปิด")).output.contains("ปิดเสียง=true"))
        assertTrue(run("timeline.addMarker", mapOf("projectId" to projectId, "atMs" to "1000", "label" to "hi")).ok)
        assertTrue(run("timeline.moveClip", mapOf("projectId" to projectId, "clipIndex" to "2", "toAtMs" to "5000")).ok)
        assertTrue(run("timeline.duplicateClip", mapOf("projectId" to projectId, "clipIndex" to "1")).ok)
        assertTrue(run("timeline.deleteClip", mapOf("projectId" to projectId, "clipIndex" to "1")).ok)
        assertTrue(!run("timeline.splitClip", mapOf("projectId" to projectId)).ok)
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

    @Test
    fun transformAndFreezeFlow(): Unit = runBlocking {
        val projectId = (media.createProject("fx") as Outcome.Success<com.aicodemax.data.media.Project>).value.id
        val imported = run("asset.import", mapOf("projectId" to projectId, "path" to "v.mp4"))
        assertTrue(imported.ok)
        val assetId = ((media.listAssets(projectId) as Outcome.Success<List<com.aicodemax.data.media.MediaAsset>>).value[0].id)
        val clip = run(
            "timeline.addClip",
            mapOf("projectId" to projectId, "assetId" to assetId, "startMs" to "0", "endMs" to "4000", "atMs" to "0"),
        )
        assertTrue(clip.ok)
        val rotated = run("timeline.transformClip", mapOf("projectId" to projectId, "clipIndex" to "1", "rotation" to "90"))
        assertTrue(rotated.output, rotated.ok)
        val listed = run("timeline.get", mapOf("projectId" to projectId))
        assertTrue(listed.output, listed.ok && listed.output.contains("R90"))
        val frozen = run("timeline.freezeFrame", mapOf("projectId" to projectId, "clipIndex" to "1", "holdMs" to "1000"))
        assertTrue(frozen.output, frozen.ok)
        val undone = run("edit.undo", mapOf("projectId" to projectId))
        assertTrue(undone.ok)
    }

    @Test
    fun textFlowAndIdeas(): Unit = runBlocking {
        val projectId = (media.createProject("tx") as Outcome.Success<com.aicodemax.data.media.Project>).value.id
        val added = run("timeline.addText", mapOf("projectId" to projectId, "text" to "สวัสดี", "preset" to "hook"))
        assertTrue(added.output, added.ok)
        val listed = run("timeline.get", mapOf("projectId" to projectId))
        assertTrue(listed.output, listed.ok && listed.output.contains("สวัสดี"))
        val ideas = run("text.ideas", mapOf("kind" to "cta", "topic" to "กาแฟ"))
        assertTrue(ideas.output, ideas.ok && ideas.output.contains("กาแฟ"))
        val removed = run("timeline.removeText", mapOf("projectId" to projectId, "textIndex" to "1"))
        assertTrue(removed.output, removed.ok)
    }

    @Test
    fun speedFlow(): Unit = runBlocking {
        val projectId = (media.createProject("sp") as Outcome.Success<com.aicodemax.data.media.Project>).value.id
        val imported = run("asset.import", mapOf("projectId" to projectId, "path" to "v.mp4"))
        assertTrue(imported.ok)
        val assetId = ((media.listAssets(projectId) as Outcome.Success<List<com.aicodemax.data.media.MediaAsset>>).value[0].id)
        val clip = run(
            "timeline.addClip",
            mapOf("projectId" to projectId, "assetId" to assetId, "startMs" to "0", "endMs" to "4000", "atMs" to "0"),
        )
        assertTrue(clip.ok)
        val sped = run("timeline.setSpeed", mapOf("projectId" to projectId, "clipIndex" to "1", "rate" to "200"))
        assertTrue(sped.output, sped.ok)
        val listed = run("timeline.get", mapOf("projectId" to projectId))
        assertTrue(listed.output, listed.ok && listed.output.contains("[2x]"))
        val ramp = run("timeline.setSpeed", mapOf("projectId" to projectId, "clipIndex" to "1", "rate" to "100", "curve" to "hero"))
        assertTrue(ramp.output, ramp.ok)
        val bad = run("timeline.setSpeed", mapOf("projectId" to projectId, "clipIndex" to "1", "rate" to "999"))
        assertTrue(!bad.ok)
    }

    @Test
    fun keyframeFlow(): Unit = runBlocking {
        val projectId = (media.createProject("kf") as Outcome.Success<com.aicodemax.data.media.Project>).value.id
        val imported = run("asset.import", mapOf("projectId" to projectId, "path" to "v.mp4"))
        assertTrue(imported.ok)
        val assetId = ((media.listAssets(projectId) as Outcome.Success<List<com.aicodemax.data.media.MediaAsset>>).value[0].id)
        val clip = run(
            "timeline.addClip",
            mapOf("projectId" to projectId, "assetId" to assetId, "startMs" to "0", "endMs" to "4000", "atMs" to "0"),
        )
        assertTrue(clip.ok)
        val set = run("timeline.setKeyframe", mapOf("projectId" to projectId, "clipIndex" to "1", "prop" to "scale", "atMs" to "2000", "value" to "150"))
        assertTrue(set.output, set.ok)
        val listed = run("timeline.get", mapOf("projectId" to projectId))
        assertTrue(listed.output, listed.ok && listed.output.contains("{KF scale×1}"))
        val cleared = run("timeline.clearKeyframes", mapOf("projectId" to projectId, "clipIndex" to "1"))
        assertTrue(cleared.output, cleared.ok)
        val bad = run("timeline.setKeyframe", mapOf("projectId" to projectId, "clipIndex" to "1", "prop" to "scale", "atMs" to "0", "value" to "999"))
        assertTrue(!bad.ok)
    }

    @Test
    fun transitionFxFlow(): Unit = runBlocking {
        val projectId = (media.createProject("tf") as Outcome.Success<com.aicodemax.data.media.Project>).value.id
        val imported = run("asset.import", mapOf("projectId" to projectId, "path" to "v.mp4"))
        assertTrue(imported.ok)
        val assetId = ((media.listAssets(projectId) as Outcome.Success<List<com.aicodemax.data.media.MediaAsset>>).value[0].id)
        val clip = run(
            "timeline.addClip",
            mapOf("projectId" to projectId, "assetId" to assetId, "startMs" to "0", "endMs" to "4000", "atMs" to "0"),
        )
        assertTrue(clip.ok)
        val tr = run("timeline.setTransition", mapOf("projectId" to projectId, "clipIndex" to "1", "edge" to "in", "kind" to "fade", "durationMs" to "400"))
        assertTrue(tr.output, tr.ok)
        val fx = run("timeline.setFx", mapOf("projectId" to projectId, "clipIndex" to "1", "grain" to "20"))
        assertTrue(fx.output, fx.ok)
        val listed = run("timeline.get", mapOf("projectId" to projectId))
        assertTrue(listed.output, listed.ok && listed.output.contains("{IN:fade400}") && listed.output.contains("{FX:g20}"))
        val bad = run("timeline.setFx", mapOf("projectId" to projectId, "clipIndex" to "1", "blur" to "99"))
        assertTrue(!bad.ok)
    }

    @Test
    fun colorFlow(): Unit = runBlocking {
        val projectId = (media.createProject("cc") as Outcome.Success<com.aicodemax.data.media.Project>).value.id
        val imported = run("asset.import", mapOf("projectId" to projectId, "path" to "v.mp4"))
        assertTrue(imported.ok)
        val assetId = ((media.listAssets(projectId) as Outcome.Success<List<com.aicodemax.data.media.MediaAsset>>).value[0].id)
        val clip = run(
            "timeline.addClip",
            mapOf("projectId" to projectId, "assetId" to assetId, "startMs" to "0", "endMs" to "4000", "atMs" to "0"),
        )
        assertTrue(clip.ok)
        val graded = run("timeline.setColor", mapOf("projectId" to projectId, "clipIndex" to "1", "preset" to "bw"))
        assertTrue(graded.output, graded.ok)
        val listed = run("timeline.get", mapOf("projectId" to projectId))
        assertTrue(listed.output, listed.ok && listed.output.contains("{C:st-100}"))
        val badPreset = run("timeline.setColor", mapOf("projectId" to projectId, "clipIndex" to "1", "preset" to "nope"))
        assertTrue(!badPreset.ok)
        val badRange = run("timeline.setColor", mapOf("projectId" to projectId, "clipIndex" to "1", "brightness" to "500"))
        assertTrue(!badRange.ok)
    }

    @Test
    fun maskChromaBgFlow(): Unit = runBlocking {
        val projectId = (media.createProject("mc") as Outcome.Success<com.aicodemax.data.media.Project>).value.id
        val imported = run("asset.import", mapOf("projectId" to projectId, "path" to "v.mp4"))
        assertTrue(imported.ok)
        val assetId = ((media.listAssets(projectId) as Outcome.Success<List<com.aicodemax.data.media.MediaAsset>>).value[0].id)
        val clip = run(
            "timeline.addClip",
            mapOf("projectId" to projectId, "assetId" to assetId, "startMs" to "0", "endMs" to "4000", "atMs" to "0"),
        )
        assertTrue(clip.ok)
        val mask = run("timeline.setMask", mapOf("projectId" to projectId, "clipIndex" to "1", "shape" to "ellipse"))
        assertTrue(mask.output, mask.ok)
        val chroma = run("timeline.setChroma", mapOf("projectId" to projectId, "clipIndex" to "1"))
        assertTrue(chroma.output, chroma.ok)
        val bg = run("timeline.setBackground", mapOf("projectId" to projectId, "mode" to "color", "color" to "1A2B4A"))
        assertTrue(bg.output, bg.ok)
        val listed = run("timeline.get", mapOf("projectId" to projectId))
        assertTrue(listed.output, listed.ok && listed.output.contains("{M:ellipse") && listed.output.contains("{CH:h120") && listed.output.contains("พื้นหลัง: color#1A2B4A"))
        val bad = run("timeline.setMask", mapOf("projectId" to projectId, "clipIndex" to "1", "shape" to "star"))
        assertTrue(!bad.ok)
    }

    @Test
    fun trackStabilizeFlow(): Unit = runBlocking {
        val projectId = (media.createProject("mo") as Outcome.Success<com.aicodemax.data.media.Project>).value.id
        val imported = run("asset.import", mapOf("projectId" to projectId, "path" to "v.mp4"))
        assertTrue(imported.ok)
        val assetId = ((media.listAssets(projectId) as Outcome.Success<List<com.aicodemax.data.media.MediaAsset>>).value[0].id)
        val clip = run(
            "timeline.addClip",
            mapOf("projectId" to projectId, "assetId" to assetId, "startMs" to "0", "endMs" to "4000", "atMs" to "0"),
        )
        assertTrue(clip.ok)
        val tracked = run("timeline.track", mapOf("projectId" to projectId, "clipIndex" to "1"))
        assertTrue(tracked.output, tracked.ok)
        val listed = run("timeline.get", mapOf("projectId" to projectId))
        assertTrue(listed.output, listed.ok && listed.output.contains("{KF posX×3 posY×3}"))
        val stabbed = run("timeline.stabilize", mapOf("projectId" to projectId, "clipIndex" to "1"))
        assertTrue(stabbed.output, stabbed.ok && stabbed.output.contains("กันสั่นแล้ว"))
        val listed2 = run("timeline.get", mapOf("projectId" to projectId))
        assertTrue(listed2.output, listed2.output.contains("108%"))
        val bad = run("timeline.track", mapOf("projectId" to projectId, "clipIndex" to "1", "w" to "0"))
        assertTrue(!bad.ok)
    }
}
