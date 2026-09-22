package com.aicodemax.app

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.video.Mp4Probe
import com.aicodemax.tools.video.VideoInfo
import com.aicodemax.tools.video.VideoPort
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CP-63 Android video: pure [Mp4Probe] first (+ retriever fallback for other
 * containers), thumbnails via MediaMetadataRetriever, trim/audio-extract via
 * MediaExtractor + MediaMuxer stream-copy (no re-encode — fast, HW-free).
 */
class AndroidVideoPort : VideoPort {
    override suspend fun info(path: String): Outcome<VideoInfo> =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.isFile) {
                return@withContext Outcome.Failure(AppError("VIDEO_NO_FILE", "ไม่พบไฟล์ $path"))
            }
            when (val probed = Mp4Probe.probe(file)) {
                is Outcome.Success -> Outcome.Success(
                    VideoInfo(
                        path, probed.value.format, probed.value.durationMs,
                        probed.value.width, probed.value.height, probed.value.hasAudio, probed.value.sizeBytes,
                    ),
                )
                is Outcome.Failure -> retrieverInfo(path, file.length())
            }
        }

    override suspend fun thumbnail(src: String, dst: String, timeMs: Long): Outcome<VideoInfo> =
        withContext(Dispatchers.IO) {
            if (!File(src).isFile) {
                return@withContext Outcome.Failure(AppError("VIDEO_NO_FILE", "ไม่พบไฟล์ $src"))
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(src)
                val frame = retriever.getFrameAtTime(
                    timeMs.coerceAtLeast(0) * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: return@withContext Outcome.Failure(
                    AppError("VIDEO_THUMB", "จับภาพที่ $timeMs ms ไม่ได้ (ไฟล์อาจเสีย)"),
                )
                try {
                    File(dst).parentFile?.mkdirs()
                    FileOutputStream(dst).use { out ->
                        if (!frame.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            return@withContext Outcome.Failure(AppError("VIDEO_THUMB", "เขียนภาพปกไม่ได้: $dst"))
                        }
                    }
                } catch (e: Exception) {
                    return@withContext Outcome.Failure(AppError("VIDEO_THUMB", "เขียนภาพปกไม่ได้: ${e.message}"))
                }
                val w = frame.width
                val h = frame.height
                frame.recycle()
                Outcome.Success(VideoInfo(dst, "PNG", 0, w, h))
            } catch (e: Exception) {
                Outcome.Failure(AppError("VIDEO_THUMB", "จับภาพปกไม่ได้: ${e.message}"))
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }

    override suspend fun trim(src: String, dst: String, startMs: Long, endMs: Long): Outcome<VideoInfo> =
        withContext(Dispatchers.IO) {
            if (endMs <= startMs || startMs < 0) {
                return@withContext Outcome.Failure(AppError("VIDEO_TRIM", "ช่วงเวลาไม่ถูกต้อง ($startMs..$endMs ms)"))
            }
            remux(src, dst, startMs * 1000, endMs * 1000, audioOnly = false, code = "VIDEO_TRIM")
        }

    override suspend fun extractAudio(src: String, dst: String): Outcome<VideoInfo> =
        withContext(Dispatchers.IO) {
            remux(src, dst, 0, Long.MAX_VALUE, audioOnly = true, code = "VIDEO_AUDIO")
        }

    private fun retrieverInfo(path: String, sizeBytes: Long): Outcome<VideoInfo> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            fun meta(key: Int): String? = try {
                retriever.extractMetadata(key)
            } catch (_: Exception) {
                null
            }
            val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: -1
            val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: -1
            val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            val hasVideo = meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" || w > 0
            val hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (!hasVideo && !hasAudio) {
                return Outcome.Failure(AppError("VIDEO_UNKNOWN", "ไฟล์นี้ไม่มีวิดีโอ/เสียงที่อ่านได้"))
            }
            val format = when (path.substringAfterLast('.', "").lowercase()) {
                "webm" -> "WebM"
                "mkv" -> "MKV"
                "3gp" -> "3GP"
                "mov" -> "MOV"
                else -> "VIDEO"
            }
            Outcome.Success(VideoInfo(path, format, duration, w, h, hasAudio, sizeBytes))
        } catch (e: Exception) {
            Outcome.Failure(AppError("VIDEO_UNKNOWN", "อ่านไฟล์วิดีโอไม่ได้: ${e.message}"))
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Stream-copy remux. [startUs]/[endUs] bound the kept range; [audioOnly]
     * keeps just audio tracks (.m4a). Timestamps are rebased to start at ~0.
     */
    private fun remux(
        src: String,
        dst: String,
        startUs: Long,
        endUs: Long,
        audioOnly: Boolean,
        code: String,
    ): Outcome<VideoInfo> {
        if (!File(src).isFile) {
            return Outcome.Failure(AppError("VIDEO_NO_FILE", "ไม่พบไฟล์ $src"))
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(src)
        } catch (e: Exception) {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
            return Outcome.Failure(AppError(code, "เปิดไฟล์ไม่ได้: ${e.message}"))
        }
        var muxer: MediaMuxer? = null
        try {
            val trackMap = mutableMapOf<Int, Int>()
            val offsets = mutableMapOf<Int, Long>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = try {
                    format.getString(MediaFormat.KEY_MIME)
                } catch (_: Exception) {
                    null
                } ?: continue
                val isAudio = mime.startsWith("audio/")
                val isVideo = mime.startsWith("video/")
                if (!isAudio && !isVideo) continue
                if (audioOnly && !isAudio) continue
                extractor.selectTrack(i)
                trackMap[i] = -1 // filled after muxer starts
            }
            if (trackMap.isEmpty()) {
                return Outcome.Failure(
                    AppError(code, if (audioOnly) "คลิปนี้ไม่มีแทร็กเสียง" else "ไฟล์นี้ไม่มีแทร็กวิดีโอ/เสียง"),
                )
            }
            File(dst).parentFile?.mkdirs()
            muxer = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for (i in trackMap.keys.sorted()) {
                trackMap[i] = muxer.addTrack(extractor.getTrackFormat(i))
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val time = extractor.sampleTime
                if (time > endUs) break
                val muxTrack = trackMap[track]
                if (muxTrack == null || muxTrack < 0) {
                    extractor.advance()
                    continue
                }
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                info.presentationTimeUs = time - (offsets[track] ?: time.also { offsets[track] = it })
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            return when (val probed = infoBlocking(dst)) {
                is Outcome.Failure -> Outcome.Success(VideoInfo(dst, "MP4", (endUs - startUs) / 1000))
                is Outcome.Success -> probed
            }
        } catch (e: Exception) {
            try {
                File(dst).delete()
            } catch (_: Exception) {
            }
            return Outcome.Failure(AppError(code, "ตัดต่อไม่ได้: ${e.message}"))
        } finally {
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Blocking re-probe of our own output (already on Dispatchers.IO). */
    private fun infoBlocking(path: String): Outcome<VideoInfo> {
        val file = File(path)
        return when (val probed = Mp4Probe.probe(file)) {
            is Outcome.Failure -> probed
            is Outcome.Success -> Outcome.Success(
                VideoInfo(
                    path, probed.value.format, probed.value.durationMs,
                    probed.value.width, probed.value.height, probed.value.hasAudio, probed.value.sizeBytes,
                ),
            )
        }
    }
}
