package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.OverlayText
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-74: text overlays on the in-memory port (undoable) + idea templates. */
class MediaTextTest {
    @Test
    fun textLifecycleWithUndo(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val proj = (media.createProject("tx") as Outcome.Success<Project>).value
        val added = media.addText(proj.id, OverlayText("t1", "เปิดร้าน", 0, 2000)) as Outcome.Success<Project>
        assertEquals(1, added.value.timeline.texts.size)
        media.updateText(proj.id, "t1", OverlayText("t1", "เปิดร้านแล้ว!", 0, 2000, animIn = "pop"))
        val updated = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals("pop", updated.texts[0].animIn)
        media.removeText(proj.id, "t1")
        assertTrue(((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.texts.isEmpty()))
        media.undo(proj.id)
        assertEquals(1, ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.texts.size))
        val bad = media.addText(proj.id, OverlayText("t2", "", 0, 1000))
        assertTrue(bad is Outcome.Failure)
    }

    @Test
    fun ideasCoverKinds(): Unit = runBlocking {
        for (kind in TextIdeas.kinds()) {
            val ideas = TextIdeas.ideas(kind, "กาแฟ", "tiktok")
            assertTrue(kind, ideas.size >= 2 && ideas.all { it.contains("กาแฟ") })
        }
    }
}
