package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.ProjectTemplate
import com.aicodemax.data.media.Timeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-82: templates + library on the in-memory port. */
class MediaTemplateTest {
    @Test
    fun builtinSeedsPresent(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val list = (media.listTemplates(null) as Outcome.Success<List<ProjectTemplate>>).value
        assertTrue(list.size >= 2)
        assertTrue(list.any { it.id == "builtin-social-hook" })
    }

    @Test
    fun saveApplyDeleteWithUndo(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val proj = (media.createProject("tpl") as Outcome.Success<Project>).value
        val asset = (media.importAsset(proj.id, "v.mp4") as Outcome.Success<com.aicodemax.data.media.MediaAsset>).value
        media.addClip(proj.id, asset.id, 0, 4000, 0)
        val clipId = ((media.getTimeline(proj.id) as Outcome.Success<Timeline>).value.tracks[0].clips[0].id)
        val saved = (media.saveTemplate("My T", "vlog", proj.id, mapOf("main" to clipId), "d") as Outcome.Success<ProjectTemplate>).value
        assertEquals("slot:main", saved.timeline.tracks[0].clips[0].assetId)
        val bad = media.saveTemplate("X", "vlog", proj.id, mapOf("main" to "ghost"))
        assertTrue(bad is Outcome.Failure)
        media.applyTemplate(proj.id, saved.id, mapOf("main" to asset.id))
        val applied = (media.getTimeline(proj.id) as Outcome.Success<Timeline>).value
        assertEquals(asset.id, applied.tracks[0].clips[0].assetId)
        val missingSlot = media.applyTemplate(proj.id, saved.id, emptyMap())
        assertTrue(missingSlot is Outcome.Failure)
        media.undo(proj.id)
        media.deleteTemplate(saved.id)
        val gone = media.getTemplate(saved.id)
        assertTrue(gone is Outcome.Failure)
        val builtinDel = media.deleteTemplate("builtin-social-hook")
        assertTrue(builtinDel is Outcome.Failure)
    }

    @Test
    fun libraryCrud(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val added = (media.libraryAdd("lut", "Warm", listOf("อุ่น"), "/w.cube") as Outcome.Success<com.aicodemax.data.media.LibraryItem>).value
        assertTrue((media.librarySearch("อุ่น") as Outcome.Success<List<com.aicodemax.data.media.LibraryItem>>).value.size == 1)
        assertTrue((media.libraryList("lut") as Outcome.Success<List<com.aicodemax.data.media.LibraryItem>>).value.size == 1)
        media.libraryRemove(added.id)
        assertTrue((media.libraryList(null) as Outcome.Success<List<com.aicodemax.data.media.LibraryItem>>).value.isEmpty())
        assertTrue(media.libraryAdd("nope", "N", emptyList(), "") is Outcome.Failure)
    }
}
