package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.MediaAsset
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import com.aicodemax.data.media.TimelineMarker
import com.aicodemax.data.media.Track
import com.aicodemax.tools.audio.InMemoryAudioPort
import com.aicodemax.tools.audio.PcmAudio
import com.aicodemax.tools.image.InMemoryImagePort
import com.aicodemax.tools.video.InMemoryVideoPort
import com.aicodemax.tools.video.VideoInfo
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CP-72: clip/marker/track ops on the file-backed port (undoable). */
class MediaClipTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun setup(): Triple<FileMediaProject, Project, MediaAsset> {
        val srcDir = File(tmp.root, "src").also { it.mkdirs() }
        val clipPath = File(srcDir, "clip.mp4").also { it.writeBytes(ByteArray(10)) }.path
        val media = FileMediaProject(
            File(tmp.root, "media"),
            InMemoryImagePort(),
            InMemoryAudioPort().also { it.put("x", PcmAudio(8000, 1, FloatArray(8) { 0.1f })) },
            InMemoryVideoPort().also {
                it.put(clipPath, VideoInfo(clipPath, "MP4", 3000, 640, 480, hasAudio = true))
            },
        )
        val proj = (media.createProject("clips") as Outcome.Success<Project>).value
        val asset = (media.importAsset(proj.id, clipPath) as Outcome.Success<MediaAsset>).value
        media.addClip(proj.id, asset.id, 0, 3000, 0)
        return Triple(media, proj, asset)
    }

    private suspend fun timelineOf(media: FileMediaProject, pid: String): Timeline =
        (media.getTimeline(pid) as Outcome.Success<Timeline>).value

    @Test
    fun splitTrimMoveDeleteDuplicateFlow(): Unit = runBlocking {
        val (media, proj, _) = setup()
        val pid = proj.id
        val first = timelineOf(media, pid).tracks[0].clips[0].id
        media.splitClip(pid, first, 1000)
        assertEquals(2, timelineOf(media, pid).tracks[0].clips.size)
        val right = timelineOf(media, pid).tracks[0].clips.first { it.atMs == 1000L }
        assertEquals(1000, right.startMs)
        media.trimClip(pid, right.id, null, 2500, null)
        assertEquals(2500, timelineOf(media, pid).tracks[0].clips.first { it.id == right.id }.endMs)
        media.moveClip(pid, right.id, 5000, null)
        assertEquals(5000, timelineOf(media, pid).tracks[0].clips.first { it.id == right.id }.atMs)
        media.duplicateClip(pid, right.id, null)
        assertEquals(3, timelineOf(media, pid).tracks[0].clips.size)
        media.deleteClip(pid, right.id)
        assertEquals(2, timelineOf(media, pid).tracks[0].clips.size)
        // Whole flow is undoable step by step.
        media.undo(pid)
        assertEquals(3, timelineOf(media, pid).tracks[0].clips.size)
    }

    @Test
    fun trimBeyondAssetIsHonest(): Unit = runBlocking {
        val (media, proj, _) = setup()
        val first = timelineOf(media, proj.id).tracks[0].clips[0].id
        val result = media.trimClip(proj.id, first, null, 99999, null)
        assertTrue(result is Outcome.Failure)
        assertTrue((result as Outcome.Failure).error.message.contains("3000"))
    }

    @Test
    fun markersAndLocks(): Unit = runBlocking {
        val (media, proj, _) = setup()
        val pid = proj.id
        val mark = (media.addMarker(pid, 1500, "hi") as Outcome.Success<TimelineMarker>).value
        assertEquals(1, timelineOf(media, pid).markers.size)
        media.removeMarker(pid, mark.id)
        assertTrue(timelineOf(media, pid).markers.isEmpty())
        val track = (media.setTrackFlags(pid, "V1", true, null, null) as Outcome.Success<Track>).value
        assertTrue(track.locked)
        val first = timelineOf(media, pid).tracks[0].clips[0].id
        val blocked = media.deleteClip(pid, first)
        assertTrue(blocked is Outcome.Failure)
        assertTrue((blocked as Outcome.Failure).error.message.contains("ล็อก"))
    }
}
