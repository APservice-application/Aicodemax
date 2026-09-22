package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import com.aicodemax.data.media.TimelineMarker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-85: volume + bulk markers on the in-memory port (undoable). */
class MediaVolumeTest {
    @Test
    fun volumeAndMarkersWithUndo(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val proj = (media.createProject("v") as Outcome.Success<Project>).value
        val asset = (media.importAsset(proj.id, "a.wav") as Outcome.Success<com.aicodemax.data.media.MediaAsset>).value
        media.addClip(proj.id, asset.id, 0, 4000, 0)
        val clipId = ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.tracks[0].clips[0].id)
        media.setClipVolume(proj.id, clipId, 40)
        assertEquals(40, ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.tracks[0].clips[0].volume))
        assertTrue(media.setClipVolume(proj.id, clipId, 200) is Outcome.Failure)
        media.addMarkers(proj.id, listOf(TimelineMarker("m1", 100, "x")))
        assertEquals(1, ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.markers.size))
        media.undo(proj.id)
        assertEquals(0, ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.markers.size))
    }
}
