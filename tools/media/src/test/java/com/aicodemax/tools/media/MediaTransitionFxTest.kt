package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.ClipFx
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-77: transitions + fx on the in-memory port (undoable). */
class MediaTransitionFxTest {
    @Test
    fun transitionFxLifecycleWithUndo(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val proj = (media.createProject("tf") as Outcome.Success<Project>).value
        val asset = (media.importAsset(proj.id, "v.mp4") as Outcome.Success<com.aicodemax.data.media.MediaAsset>).value
        media.addClip(proj.id, asset.id, 0, 4000, 0)
        val clipId = ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.tracks[0].clips[0].id)
        media.setTransition(proj.id, clipId, "in", "dissolve", 800)
        media.setClipFx(proj.id, clipId, ClipFx(blur = 3, grain = 10))
        val done = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals("dissolve800", done.tracks[0].clips[0].transitionIn!!.summary())
        assertEquals("b3/g10", done.tracks[0].clips[0].fx!!.summary())
        media.undo(proj.id)
        media.undo(proj.id)
        val back = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals(null, back.tracks[0].clips[0].transitionIn)
        assertEquals(null, back.tracks[0].clips[0].fx)
        val bad = media.setTransition(proj.id, clipId, "out", "dissolve")
        assertTrue(bad is Outcome.Failure)
    }

}
