package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.ClipColor
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-78: color correction on the in-memory port (undoable). */
class MediaColorTest {
    @Test
    fun colorLifecycleWithUndo(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val proj = (media.createProject("cc") as Outcome.Success<Project>).value
        val asset = (media.importAsset(proj.id, "v.mp4") as Outcome.Success<com.aicodemax.data.media.MediaAsset>).value
        media.addClip(proj.id, asset.id, 0, 4000, 0)
        val clipId = ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.tracks[0].clips[0].id)
        media.setClipColor(proj.id, clipId, ClipColor.preset("cinema"))
        val done = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertTrue(done.tracks[0].clips[0].color!!.summary().contains("ct15"))
        media.undo(proj.id)
        val back = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals(null, back.tracks[0].clips[0].color)
        val bad = media.setClipColor(proj.id, clipId, ClipColor(hueShift = 999))
        assertTrue(bad is Outcome.Failure)
    }
}
