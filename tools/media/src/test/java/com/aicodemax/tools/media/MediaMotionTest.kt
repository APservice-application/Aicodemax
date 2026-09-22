package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.KeyPoint
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.ProjectEventTypes
import com.aicodemax.data.media.Timeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-80: motion path on the in-memory port (undoable). */
class MediaMotionTest {
    @Test
    fun applyTrackPathLifecycleWithUndo(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val proj = (media.createProject("mo") as Outcome.Success<Project>).value
        val asset = (media.importAsset(proj.id, "v.mp4") as Outcome.Success<com.aicodemax.data.media.MediaAsset>).value
        media.addClip(proj.id, asset.id, 0, 4000, 0)
        val clipId = ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.tracks[0].clips[0].id)
        val px = listOf(KeyPoint(0, 0f), KeyPoint(4000, 100f))
        val py = listOf(KeyPoint(0, 0f), KeyPoint(4000, 50f))
        media.applyTrackPath(proj.id, clipId, px, py, 5, ProjectEventTypes.STAB_APPLIED, "กันสั่น")
        val done = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals(2, done.tracks[0].clips[0].keyframes!!.points("posX").size)
        assertEquals(105, done.tracks[0].clips[0].transform!!.scale)
        media.undo(proj.id)
        val back = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals(null, back.tracks[0].clips[0].keyframes)
        val bad = media.applyTrackPath(proj.id, "ghost", px, py)
        assertTrue(bad is Outcome.Failure)
    }
}
