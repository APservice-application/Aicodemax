package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.ClipSpeed
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-75: clip speed on the in-memory port (undoable). */
class MediaSpeedTest {
    @Test
    fun speedLifecycleWithUndo(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val proj = (media.createProject("sp") as Outcome.Success<Project>).value
        val asset = (media.importAsset(proj.id, "v.mp4") as Outcome.Success<com.aicodemax.data.media.MediaAsset>).value
        media.addClip(proj.id, asset.id, 0, 4000, 0)
        val clipId = ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.tracks[0].clips[0].id)
        val done = media.setClipSpeed(proj.id, clipId, ClipSpeed(rate = 200)) as Outcome.Success<Project>
        assertEquals(2000, done.value.timeline.durationMs)
        media.setClipSpeed(proj.id, clipId, ClipSpeed(rate = 100, reverse = true))
        val rev = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals("REV", rev.tracks[0].clips[0].speed!!.summary())
        media.undo(proj.id)
        media.undo(proj.id)
        val back = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals(4000, back.durationMs)
        val bad = media.setClipSpeed(proj.id, clipId, ClipSpeed(rate = 10))
        assertTrue(bad is Outcome.Failure)
    }
}
