package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.MediaAsset
import com.aicodemax.data.media.Project
import com.aicodemax.tools.audio.InMemoryAudioPort
import com.aicodemax.tools.audio.PcmAudio
import com.aicodemax.tools.image.InMemoryImagePort
import com.aicodemax.tools.image.PixelImage
import com.aicodemax.tools.image.argb
import com.aicodemax.tools.video.InMemoryVideoPort
import com.aicodemax.tools.video.VideoInfo
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileMediaProjectTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var picPath: String
    private lateinit var songPath: String
    private lateinit var clipPath: String

    private fun project(): FileMediaProject {
        val srcDir = File(tmp.root, "src").also { it.mkdirs() }
        picPath = File(srcDir, "pic.png").also { it.writeBytes(ByteArray(10)) }.path
        songPath = File(srcDir, "song.wav").also { it.writeBytes(ByteArray(10)) }.path
        clipPath = File(srcDir, "clip.mp4").also { it.writeBytes(ByteArray(10)) }.path
        val images = InMemoryImagePort().also {
            it.put(picPath, PixelImage(4, 2, IntArray(8) { argb(255, 1, 2, 3) }))
        }
        val audio = InMemoryAudioPort().also {
            it.put(songPath, PcmAudio(8000, 1, FloatArray(8000) { 0.1f }))
        }
        val video = InMemoryVideoPort().also {
            it.put(clipPath, VideoInfo(clipPath, "MP4", 3000, 640, 480, hasAudio = true))
        }
        return FileMediaProject(File(tmp.root, "media"), images, audio, video)
    }

    @Test
    fun importProbesFactsAndCopies(): Unit = runBlocking {
        val media = project()
        val project = (media.createProject("demo") as Outcome.Success<Project>).value
        val asset = (media.importAsset(project.id, picPath) as Outcome.Success<MediaAsset>).value
        assertEquals("4", asset.facts["width"])
        assertEquals("2", asset.facts["height"])
        val assets = (media.listAssets(project.id) as Outcome.Success<List<MediaAsset>>).value
        assertEquals(1, assets.size)
        assertTrue(media.importAsset("proj_missing", picPath) is Outcome.Failure)
    }

    @Test
    fun addClipPlacesOnKindTrack(): Unit = runBlocking {
        val media = project()
        val project = (media.createProject("demo") as Outcome.Success<Project>).value
        val clip = (media.importAsset(project.id, clipPath) as Outcome.Success<MediaAsset>).value
        val song = (media.importAsset(project.id, songPath) as Outcome.Success<MediaAsset>).value
        assertEquals("3000", clip.facts["durationMs"])
        assertEquals("1000", song.facts["durationMs"])
        val withVideo = (media.addClip(project.id, clip.id, 0, 2000, 0) as Outcome.Success<Project>).value
        assertEquals(listOf("V1"), withVideo.timeline.tracks.map { it.id })
        val withBoth = (media.addClip(project.id, song.id, 0, 1000, 500) as Outcome.Success<Project>).value
        assertEquals(2000L, withBoth.timeline.durationMs)
        assertEquals(setOf("V1", "A1"), withBoth.timeline.tracks.map { it.id }.toSet())
        assertTrue(media.addClip(project.id, "asset_ghost", 0, 100, 0) is Outcome.Failure)
    }

    @Test
    fun setTimelineRejectsUnknownAsset(): Unit = runBlocking {
        val media = project()
        val project = (media.createProject("demo") as Outcome.Success<Project>).value
        val bad = com.aicodemax.data.media.Timeline(
            listOf(
                com.aicodemax.data.media.Track(
                    "V1", com.aicodemax.data.media.MediaKind.VIDEO,
                    listOf(com.aicodemax.data.media.Clip("c", "ghost", 0, 10, 0)),
                ),
            ),
        )
        val result = media.setTimeline(project.id, bad)
        assertTrue(result is Outcome.Failure)
    }

    @Test
    fun versionRoundTrip(): Unit = runBlocking {
        val media = project()
        val project = (media.createProject("demo") as Outcome.Success<Project>).value
        assertEquals(1, (media.saveVersion(project.id) as Outcome.Success<Int>).value)
        assertEquals(listOf(1), (media.listVersions(project.id) as Outcome.Success<List<Int>>).value)
        assertTrue(media.restoreVersion(project.id, 1) is Outcome.Success)
    }
}
