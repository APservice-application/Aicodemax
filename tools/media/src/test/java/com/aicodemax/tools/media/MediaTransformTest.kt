package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.ClipTransform
import com.aicodemax.data.media.MediaAsset
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import com.aicodemax.tools.audio.InMemoryAudioPort
import com.aicodemax.tools.audio.PcmAudio
import com.aicodemax.tools.image.InMemoryImagePort
import com.aicodemax.tools.video.InMemoryVideoPort
import com.aicodemax.tools.video.VideoInfo
import com.aicodemax.tools.video.VideoPort
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CP-73: transform + freeze on file-backed and in-memory ports (undoable). */
class MediaTransformTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Thumbnail fake that really writes bytes so importAsset succeeds. */
    private class WritingVideo(val inner: InMemoryVideoPort) : VideoPort by inner {
        override suspend fun thumbnail(src: String, dst: String, timeMs: Long): Outcome<VideoInfo> =
            when (val got = inner.thumbnail(src, dst, timeMs)) {
                is Outcome.Failure -> got
                is Outcome.Success -> {
                    File(dst).also { it.parentFile?.mkdirs() }.writeBytes(ByteArray(16) { 1 })
                    got
                }
            }
    }

    private suspend fun fileSetup(): Triple<FileMediaProject, Project, String> {
        val srcDir = File(tmp.root, "src").also { it.mkdirs() }
        val clipPath = File(srcDir, "clip.mp4").also { it.writeBytes(ByteArray(10)) }.path
        val inner = InMemoryVideoPort().also {
            it.put(clipPath, VideoInfo(clipPath, "MP4", 3000, 640, 480, hasAudio = true))
        }
        val media = FileMediaProject(
            File(tmp.root, "media"),
            InMemoryImagePort(),
            InMemoryAudioPort().also { it.put("x", PcmAudio(8000, 1, FloatArray(8) { 0.1f })) },
            WritingVideo(inner),
        )
        val proj = (media.createProject("fx") as Outcome.Success<Project>).value
        val asset = (media.importAsset(proj.id, clipPath) as Outcome.Success<MediaAsset>).value
        // The port thumbnails the IMPORTED copy, so the fake must know that path too.
        val importedPath = File(File(tmp.root, "media"), "${proj.id}/assets/${asset.fileName}").path
        inner.put(importedPath, VideoInfo(importedPath, "MP4", 3000, 640, 480, hasAudio = true))
        media.addClip(proj.id, asset.id, 0, 3000, 0)
        val clipId = ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.tracks[0].clips[0].id)
        return Triple(media, proj, clipId)
    }

    @Test
    fun fileTransformAndUndo(): Unit = runBlocking {
        val (media, proj, clipId) = fileSetup()
        val done = media.transformClip(proj.id, clipId, ClipTransform(rotation = 180, opacity = 80)) as Outcome.Success<Project>
        assertEquals(180, done.value.timeline.tracks[0].clips[0].transform!!.rotation)
        media.undo(proj.id)
        val back = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals(null, back.tracks[0].clips[0].transform)
        val bad = media.transformClip(proj.id, clipId, ClipTransform(scale = 999))
        assertTrue(bad is Outcome.Failure)
    }

    @Test
    fun fileFreezeInsertsStillAndRipples(): Unit = runBlocking {
        val (media, proj, clipId) = fileSetup()
        val done = media.freezeFrame(proj.id, clipId, 1500, 2000) as Outcome.Success<Project>
        val clips = done.value.timeline.tracks.flatMap { it.clips }.sortedBy { it.atMs }
        assertEquals(3, clips.size)
        assertEquals(2000, clips[1].durationMs)
        assertEquals(5000, done.value.timeline.durationMs)
        val assets = (media.listAssets(proj.id) as Outcome.Success<List<MediaAsset>>).value
        assertTrue(assets.any { it.kind == MediaKind.IMAGE })
        media.undo(proj.id)
        val back = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals(1, back.tracks.flatMap { it.clips }.size)
    }

    @Test
    fun memoryFreezeAndErrors(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val proj = (media.createProject("m") as Outcome.Success<Project>).value
        val asset = (media.importAsset(proj.id, "v.mp4") as Outcome.Success<MediaAsset>).value
        media.addClip(proj.id, asset.id, 0, 4000, 0)
        val clipId = ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.tracks[0].clips[0].id)
        val done = media.freezeFrame(proj.id, clipId, null, 1000) as Outcome.Success<Project>
        assertEquals(3, done.value.timeline.tracks.flatMap { it.clips }.size)
        val audioAsset = (media.importAsset(proj.id, "s.mp3") as Outcome.Success<MediaAsset>).value
        media.addClip(proj.id, audioAsset.id, 0, 4000, 0)
        val audioClip = ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.tracks
            .first { it.kind == MediaKind.AUDIO }.clips[0].id)
        val frozen = media.freezeFrame(proj.id, audioClip, null, 1000)
        assertTrue(frozen is Outcome.Failure)
        val rotated = media.transformClip(proj.id, audioClip, ClipTransform(rotation = 90))
        assertTrue(rotated is Outcome.Failure)
    }
}
