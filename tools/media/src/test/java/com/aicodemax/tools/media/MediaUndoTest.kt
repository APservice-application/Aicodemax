package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.MediaAsset
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import com.aicodemax.tools.audio.InMemoryAudioPort
import com.aicodemax.tools.audio.PcmAudio
import com.aicodemax.tools.image.InMemoryImagePort
import com.aicodemax.tools.image.PixelImage
import com.aicodemax.tools.image.argb
import com.aicodemax.tools.video.InMemoryVideoPort
import com.aicodemax.tools.video.VideoInfo
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CP-71: transactions, undo/redo, rollback, trash, checkpoints, history. */
class MediaUndoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun project(): Pair<FileMediaProject, String> {
        val srcDir = File(tmp.root, "src").also { it.mkdirs() }
        val picPath = File(srcDir, "pic.png").also { it.writeBytes(ByteArray(10)) }.path
        val images = InMemoryImagePort().also {
            it.put(picPath, PixelImage(4, 2, IntArray(8) { argb(255, 1, 2, 3) }))
        }
        val audio = InMemoryAudioPort().also {
            it.put("x", PcmAudio(8000, 1, FloatArray(8) { 0.1f }))
        }
        val video = InMemoryVideoPort().also {
            it.put("x", VideoInfo("x", "MP4", 3000, 640, 480, hasAudio = true))
        }
        return Pair(FileMediaProject(File(tmp.root, "media"), images, audio, video), picPath)
    }

    private suspend fun setup(): Triple<FileMediaProject, Project, MediaAsset> {
        val (media, picPath) = project()
        val proj = (media.createProject("demo") as Outcome.Success<Project>).value
        val asset = (media.importAsset(proj.id, picPath) as Outcome.Success<MediaAsset>).value
        return Triple(media, proj, asset)
    }

    private suspend fun clips(media: FileMediaProject, pid: String): Int =
        ((media.getTimeline(pid) as Outcome.Success<Timeline>).value.tracks.flatMap { it.clips }).size

    @Test
    fun undoRedoAddClip(): Unit = runBlocking {
        val (media, proj, asset) = setup()
        media.addClip(proj.id, asset.id, 0, 1000, 0)
        assertEquals(1, clips(media, proj.id))
        val label = (media.undo(proj.id) as Outcome.Success<String>).value
        assertTrue(label.contains("วางคลิป"))
        assertEquals(0, clips(media, proj.id))
        media.redo(proj.id)
        assertEquals(1, clips(media, proj.id))
    }

    @Test
    fun transactionIsSingleUndo(): Unit = runBlocking {
        val (media, proj, asset) = setup()
        media.runTransaction(proj.id, "AI edit", "AI") {
            addClip(proj.id, asset.id, 0, 1000, 0)
            addClip(proj.id, asset.id, 0, 1000, 1000)
        }
        assertEquals(2, clips(media, proj.id))
        media.undo(proj.id)
        assertEquals(0, clips(media, proj.id))
        media.redo(proj.id)
        assertEquals(2, clips(media, proj.id))
    }

    @Test
    fun failedTransactionRollsBack(): Unit = runBlocking {
        val (media, proj, asset) = setup()
        val result = media.runTransaction(proj.id, "bad edit", "AI") {
            addClip(proj.id, asset.id, 0, 1000, 0)
            addClip(proj.id, "asset_missing", 0, 1000, 0)
        }
        assertTrue(result is Outcome.Failure)
        assertEquals(0, clips(media, proj.id))
    }

    @Test
    fun deleteRestoresFromTrash(): Unit = runBlocking {
        val (media, proj, asset) = setup()
        media.addClip(proj.id, asset.id, 0, 1000, 0)
        val trashId = (media.deleteProject(proj.id) as Outcome.Success<String>).value
        assertTrue((media.listProjects() as Outcome.Success<List<Project>>).value.isEmpty())
        val trash = (media.listTrash() as Outcome.Success<List<String>>).value
        assertEquals(listOf(trashId), trash)
        val restored = (media.restoreProject(trashId) as Outcome.Success<Project>).value
        assertEquals(1, restored.timeline.tracks.flatMap { it.clips }.size)
    }

    @Test
    fun undoDeleteBringsProjectBack(): Unit = runBlocking {
        val (media, proj, asset) = setup()
        media.addClip(proj.id, asset.id, 0, 1000, 0)
        media.deleteProject(proj.id)
        assertTrue((media.listProjects() as Outcome.Success<List<Project>>).value.isEmpty())
        media.undo(proj.id)
        assertEquals(1, clips(media, proj.id))
    }

    @Test
    fun checkpointRecoverRestoresTimeline(): Unit = runBlocking {
        val (media, proj, asset) = setup()
        media.addClip(proj.id, asset.id, 0, 1000, 0)
        val id = (media.checkpoint(proj.id, "test") as Outcome.Success<String>).value
        media.addClip(proj.id, asset.id, 0, 1000, 1000)
        assertEquals(2, clips(media, proj.id))
        media.recoverCheckpoint(proj.id, id)
        assertEquals(1, clips(media, proj.id))
        // Recover itself is undoable.
        media.undo(proj.id)
        assertEquals(2, clips(media, proj.id))
    }

    @Test
    fun renameDuplicateAndHistory(): Unit = runBlocking {
        val (media, proj, _) = setup()
        media.renameProject(proj.id, "newname")
        assertEquals("newname", (media.openProject(proj.id) as Outcome.Success<Project>).value.name)
        media.undo(proj.id)
        assertEquals("demo", (media.openProject(proj.id) as Outcome.Success<Project>).value.name)
        media.duplicateProject(proj.id)
        assertEquals(2, (media.listProjects() as Outcome.Success<List<Project>>).value.size)
        val history = (media.history(proj.id) as Outcome.Success<ProjectHistory>).value
        assertTrue(history.undoLabels.isNotEmpty())
        val types = history.events.map { it.type }
        assertTrue(types.contains("PROJECT_CREATED"))
        assertTrue(types.contains("ASSET_IMPORTED"))
    }
}
