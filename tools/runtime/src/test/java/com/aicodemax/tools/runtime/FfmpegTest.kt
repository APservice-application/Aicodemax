package com.aicodemax.tools.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FfmpegTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val probeText = """
        [STREAM]
        width=1920
        height=1080
        codec_name=h264
        codec_type=video
        [/STREAM]
        [STREAM]
        codec_name=aac
        codec_type=audio
        [/STREAM]
        [FORMAT]
        duration=12.500000
        [/FORMAT]
    """.trimIndent()

    @Test
    fun parseProbeReadsAllFields() {
        val probe = Ffmpeg.parseProbe(probeText)
        assertEquals(12500L, probe.durationMs)
        assertEquals(1920, probe.width)
        assertEquals(1080, probe.height)
        assertEquals("h264", probe.videoCodec)
        assertEquals("aac", probe.audioCodec)
    }

    @Test
    fun parseProbeToleratesReversedKeyOrder() {
        val probe = Ffmpeg.parseProbe("[STREAM]\ncodec_type=video\ncodec_name=hevc\n[/STREAM]\n")
        assertEquals("hevc", probe.videoCodec)
    }

    @Test
    fun parseProbeSkipsAudioDimensions() {
        val probe = Ffmpeg.parseProbe("[STREAM]\nwidth=0\ncodec_type=audio\ncodec_name=aac\n[/STREAM]\n")
        assertEquals(null, probe.width)
        assertEquals("aac", probe.audioCodec)
    }

    @Test
    fun exportArgsOrderIsSafe() {
        val args = Ffmpeg.exportArgs("in.mp4", "out.mp4", Ffmpeg.ExportOpts(startMs = 1500, width = 640))
        assertEquals(listOf("-y", "-ss", "1.500", "-i", "in.mp4", "-vf", "scale=640:-1", "-c:v", "mpeg4", "-c:a", "aac", "out.mp4"), args)
    }

    @Test
    fun probeWithoutBinaryIsHonest() {
        val input = tmp.newFile("clip.mp4")
        val outcome = Ffmpeg.probeFile(null, input.path) { _, _, _ ->
            Ffmpeg.RunnerResult(0, probeText)
        }
        assertTrue(outcome is com.aicodemax.core.common.Outcome.Failure)
        assertTrue((outcome as com.aicodemax.core.common.Outcome.Failure).error.message.contains("ffprobe"))
    }

    @Test
    fun probeMissingInputIsHonest() {
        val exe = tmp.newFile("libffprobe.so")
        val outcome = Ffmpeg.probeFile(exe.path, tmp.root.path + "/nope.mp4") { _, _, _ ->
            Ffmpeg.RunnerResult(0, "")
        }
        assertTrue(outcome is com.aicodemax.core.common.Outcome.Failure)
        assertTrue((outcome as com.aicodemax.core.common.Outcome.Failure).error.message.contains("ไม่พบไฟล์"))
    }

    @Test
    fun probeExitCodeIsHonest() {
        val exe = tmp.newFile("libffprobe.so")
        val input = tmp.newFile("bad.mp4")
        val outcome = Ffmpeg.probeFile(exe.path, input.path) { _, _, _ ->
            Ffmpeg.RunnerResult(1, "", "Invalid data")
        }
        val err = (outcome as com.aicodemax.core.common.Outcome.Failure).error.message
        assertTrue(err.contains("exit 1"))
        assertTrue(err.contains("Invalid data"))
    }

    @Test
    fun exportWithoutBinaryIsHonest() {
        val input = tmp.newFile("in.mp4")
        val outcome = Ffmpeg.exportFile(null, input.path, tmp.root.path + "/out.mp4", Ffmpeg.ExportOpts()) { _, _, _ ->
            Ffmpeg.RunnerResult(0)
        }
        assertTrue(outcome is com.aicodemax.core.common.Outcome.Failure)
    }

    @Test
    fun exportWritesOutputFile() {
        val exe = tmp.newFile("libffmpeg.so")
        val input = tmp.newFile("in.mp4")
        val out = tmp.root.path + "/out.mp4"
        val outcome = Ffmpeg.exportFile(exe.path, input.path, out, Ffmpeg.ExportOpts()) { _, _, _ ->
            java.io.File(out).writeBytes(byteArrayOf(1, 2, 3))
            Ffmpeg.RunnerResult(0)
        }
        val result = (outcome as com.aicodemax.core.common.Outcome.Success).value
        assertEquals(out, result.outputPath)
        assertEquals(3L, result.bytes)
    }

    @Test
    fun exportMissingOutputIsHonest() {
        val exe = tmp.newFile("libffmpeg.so")
        val input = tmp.newFile("in.mp4")
        val outcome = Ffmpeg.exportFile(exe.path, input.path, tmp.root.path + "/ghost.mp4", Ffmpeg.ExportOpts()) { _, _, _ ->
            Ffmpeg.RunnerResult(0)
        }
        assertTrue(outcome is com.aicodemax.core.common.Outcome.Failure)
        assertTrue((outcome as com.aicodemax.core.common.Outcome.Failure).error.message.contains("ไม่มีไฟล์ผลลัพธ์"))
    }
}
