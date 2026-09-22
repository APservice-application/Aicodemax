package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.ClipBackground
import com.aicodemax.data.media.ClipChroma
import com.aicodemax.data.media.ClipMask
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-79: mask + chroma + background on the in-memory port (undoable). */
class MediaMaskChromaBgTest {
    @Test
    fun maskChromaBgLifecycleWithUndo(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val proj = (media.createProject("mc") as Outcome.Success<Project>).value
        val asset = (media.importAsset(proj.id, "v.mp4") as Outcome.Success<com.aicodemax.data.media.MediaAsset>).value
        media.addClip(proj.id, asset.id, 0, 4000, 0)
        val clipId = ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.tracks[0].clips[0].id)
        media.setClipMask(proj.id, clipId, ClipMask(shape = "ellipse", feather = 20))
        media.setClipChroma(proj.id, clipId, ClipChroma())
        media.setBackground(proj.id, ClipBackground(mode = "color", color = "1A2B4A"))
        val done = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertTrue(done.tracks[0].clips[0].mask!!.summary().contains("ellipse"))
        assertEquals("h120 t30", done.tracks[0].clips[0].chroma!!.summary())
        assertEquals("color#1A2B4A", done.background!!.summary())
        media.undo(proj.id)
        media.undo(proj.id)
        media.undo(proj.id)
        val back = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals(null, back.tracks[0].clips[0].mask)
        assertEquals(null, back.tracks[0].clips[0].chroma)
        assertEquals(null, back.background)
        val bad = media.setClipMask(proj.id, clipId, ClipMask(feather = 999))
        assertTrue(bad is Outcome.Failure)
    }
}
