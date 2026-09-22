package com.aicodemax.data.media

import com.aicodemax.core.common.Outcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaStoresTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun projectCrud() {
        val store = FileProjectStore(tmp.root)
        val created = (store.create("คลิปเที่ยว") as Outcome.Success<Project>).value
        assertEquals("คลิปเที่ยว", created.name)
        val opened = (store.open(created.id) as Outcome.Success<Project>).value
        assertEquals(created.id, opened.id)
        val list = (store.list() as Outcome.Success<List<Project>>).value
        assertEquals(1, list.size)
        assertTrue(store.open("proj_missing") is Outcome.Failure)
    }

    @Test
    fun importCopyAndRemove() {
        val projects = FileProjectStore(tmp.root)
        val assets = FileMediaStore(tmp.root)
        val project = (projects.create("p") as Outcome.Success<Project>).value
        val src = File(tmp.root, "song.mp3").also { it.writeText("fake-mp3-bytes") }
        val asset = (assets.import(project.id, src, mapOf("format" to "MP3")) as Outcome.Success<MediaAsset>).value
        assertEquals(MediaKind.AUDIO, asset.kind)
        assertEquals("MP3", asset.facts["format"])
        assertTrue(assets.assetFile(project.id, asset).isFile)
        assertEquals("fake-mp3-bytes", assets.assetFile(project.id, asset).readText())
        val listed = (assets.list(project.id) as Outcome.Success<List<MediaAsset>>).value
        assertEquals(1, listed.size)
        assertTrue(assets.remove(project.id, asset.id) is Outcome.Success)
        assertTrue(!assets.assetFile(project.id, asset).exists())
    }

    @Test
    fun importRejectsUnknownKind() {
        val projects = FileProjectStore(tmp.root)
        val assets = FileMediaStore(tmp.root)
        val project = (projects.create("p") as Outcome.Success<Project>).value
        val src = File(tmp.root, "doc.pdf").also { it.writeText("x") }
        assertTrue(assets.import(project.id, src) is Outcome.Failure)
        assertTrue(assets.import(project.id, File(tmp.root, "missing.mp4")) is Outcome.Failure)
    }

    @Test
    fun timelineValidation() {
        val ok = Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(Clip("c1", "a1", 0, 1000, 0)))))
        assertTrue(ok.validate(setOf("a1")).isEmpty())
        assertEquals(1000L, ok.durationMs)
        val bad = Timeline(
            listOf(
                Track(
                    "V1", MediaKind.VIDEO,
                    listOf(
                        Clip("c1", "ghost", 0, 1000, 0),
                        Clip("c2", "a1", 500, 500, -5, volume = 150),
                    ),
                ),
            ),
        )
        val errors = bad.validate(setOf("a1"))
        assertEquals(4, errors.size)
    }

    @Test
    fun versionsSaveAndRestore() {
        val store = FileProjectStore(tmp.root)
        val project = (store.create("p") as Outcome.Success<Project>).value
        val v1 = Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(Clip("c1", "a1", 0, 1000, 0)))))
        store.saveTimeline(project.id, v1)
        assertEquals(1, (store.saveVersion(project.id) as Outcome.Success<Int>).value)
        val v2 = Timeline(
            listOf(
                Track("V1", MediaKind.VIDEO, listOf(Clip("c1", "a1", 0, 1000, 0), Clip("c2", "a1", 0, 500, 1000))),
            ),
        )
        store.saveTimeline(project.id, v2)
        assertEquals(2, (store.saveVersion(project.id) as Outcome.Success<Int>).value)
        assertEquals(listOf(1, 2), (store.listVersions(project.id) as Outcome.Success<List<Int>>).value)
        val restored = (store.restoreVersion(project.id, 1) as Outcome.Success<Project>).value
        assertEquals(1000L, restored.timeline.durationMs)
        assertTrue(store.restoreVersion(project.id, 9) is Outcome.Failure)
    }
}
