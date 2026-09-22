package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-76: keyframes on the in-memory port (undoable). */
class MediaKeyframeTest {
    @Test
    fun keyframeLifecycleWithUndo(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val proj = (media.createProject("kf") as Outcome.Success<Project>).value
        val asset = (media.importAsset(proj.id, "v.mp4") as Outcome.Success<com.aicodemax.data.media.MediaAsset>).value
        media.addClip(proj.id, asset.id, 0, 4000, 0)
        val clipId = ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.tracks[0].clips[0].id)
        media.setKeyframe(proj.id, clipId, "scale", 0, 100f)
        media.setKeyframe(proj.id, clipId, "scale", 4000, 200f, "easeinout")
        val keyed = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals(2, keyed.tracks[0].clips[0].keyframes!!.count())
        media.clearKeyframes(proj.id, clipId, "scale")
        val cleared = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals(null, cleared.tracks[0].clips[0].keyframes)
        media.undo(proj.id)
        val back = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals(2, back.tracks[0].clips[0].keyframes!!.count())
        val bad = media.setKeyframe(proj.id, clipId, "scale", 0, 999f)
        assertTrue(bad is Outcome.Failure)
    }
}
