package com.aicodemax.tools.render

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.video.VideoInfo
import com.aicodemax.tools.video.VideoPort
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QcTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun fakeVideo(info: VideoInfo) = object : VideoPort {
        override suspend fun info(path: String): Outcome<VideoInfo> = Outcome.Success(info)
        override suspend fun thumbnail(src: String, dst: String, timeMs: Long): Outcome<VideoInfo> =
            Outcome.Success(info)
        override suspend fun trim(src: String, dst: String, startMs: Long, endMs: Long): Outcome<VideoInfo> =
            Outcome.Success(info)
        override suspend fun extractAudio(src: String, dst: String): Outcome<VideoInfo> =
            Outcome.Success(info)
        override suspend fun proxy(src: String, dst: String, maxDim: Int): Outcome<VideoInfo> =
            Outcome.Success(info)
        override suspend fun multicamSync(
            paths: List<String>,
            method: String,
        ): Outcome<com.aicodemax.tools.video.MulticamGroup> =
            Outcome.Failure(com.aicodemax.core.common.AppError("NO", "no"))
        override suspend fun multicamCut(
            groupId: String,
            atMs: Long,
            angle: Int,
        ): Outcome<com.aicodemax.tools.video.MulticamGroup> =
            Outcome.Failure(com.aicodemax.core.common.AppError("NO", "no"))
        override suspend fun multicamEdl(groupId: String): Outcome<String> =
            Outcome.Failure(com.aicodemax.core.common.AppError("NO", "no"))
        override suspend fun scopes(path: String, atMs: Long): Outcome<com.aicodemax.tools.image.ScopesReport> =
            Outcome.Failure(com.aicodemax.core.common.AppError("NO", "no"))
    }

    @Test
    fun matchingOutputPasses() = runBlocking {
        val out = File(temp.root, "r.mp4").also { it.writeBytes(ByteArray(16)) }
        val video = fakeVideo(VideoInfo(out.path, "MP4", 10_000, 1280, 720, hasAudio = true))
        val report = Qc.check(10_000, true, out.path, 720, video)
        assertTrue(report.checks.joinToString { "${it.name}=${it.ok}" }, report.passed)
    }

    @Test
    fun wrongDurationOrHeightFails() = runBlocking {
        val out = File(temp.root, "r.mp4").also { it.writeBytes(ByteArray(16)) }
        val short = fakeVideo(VideoInfo(out.path, "MP4", 1_000, 1280, 720, hasAudio = true))
        assertFalse(Qc.check(10_000, true, out.path, 720, short).passed)
        val tall = fakeVideo(VideoInfo(out.path, "MP4", 10_000, 1920, 1080, hasAudio = false))
        assertFalse(Qc.check(10_000, false, out.path, 720, tall).passed)
    }

    @Test
    fun missingFileOrAudioFails() = runBlocking {
        val video = fakeVideo(VideoInfo("x", "MP4", 10_000, 1280, 720, hasAudio = false))
        assertFalse(Qc.check(10_000, false, File(temp.root, "no.mp4").path, 720, video).passed)
        val out = File(temp.root, "r.mp4").also { it.writeBytes(ByteArray(16)) }
        assertFalse(Qc.check(10_000, true, out.path, 720, video).passed)
    }
}
