package com.aicodemax.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.audio.AudioPort
import com.aicodemax.tools.subtitle.Cue
import com.aicodemax.tools.subtitle.FileSubtitlePort
import com.aicodemax.tools.subtitle.Srt
import com.aicodemax.tools.subtitle.SubtitlePort
import com.aicodemax.tools.subtitle.Yuv
import com.aicodemax.tools.video.Mp4Probe
import com.aicodemax.tools.video.VideoInfo
import com.aicodemax.tools.video.VideoPort
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CP-66 Android subtitles: SRT make/parse/shift from [FileSubtitlePort] +
 * burn-in transcode (decode → RGBA frames → Canvas text overlay → YUV420 →
 * AVC ByteBuffer encode → mux + audio passthrough). Output capped at 720p,
 * 10-minute transcode guard; failures are honest Thai errors.
 */
class AndroidSubtitlePort(
    audio: AudioPort,
    private val video: VideoPort,
) : FileSubtitlePort(audio, video), SubtitlePort {
    override suspend fun burn(srcVideo: String, srtPath: String, dst: String): Outcome<VideoInfo> =
        withContext(Dispatchers.IO) {
            burnImpl(srcVideo, srtPath, dst)
        }

    private fun burnImpl(srcVideo: String, srtPath: String, dst: String): Outcome<VideoInfo> {
        if (!File(srcVideo).isFile) {
            return Outcome.Failure(AppError("SUB_NO_VIDEO", "ไม่พบไฟล์วิดีโอ $srcVideo"))
        }
        if (!File(srtPath).isFile) {
            return Outcome.Failure(AppError("SUB_NO_FILE", "ไม่พบไฟล์ซับ $srtPath"))
        }
        val cues = when (val parsed = Srt.parse(File(srtPath).readText(Charsets.UTF_8))) {
            is Outcome.Failure -> return parsed
            is Outcome.Success -> parsed.value
        }
        if (cues.isEmpty()) {
            return Outcome.Failure(AppError("SUB_EMPTY", "ไฟล์ซับว่างเปล่า"))
        }
        val facts = videoInfoBlocking(srcVideo) ?: return Outcome.Failure(
            AppError("SUB_NO_VIDEO", "อ่านข้อมูลวิดีโอไม่ได้"),
        )
        val outW = facts.outW
        val outH = facts.outH
        val tmpVideo = File(dst).parentFile?.let { File(it, File(dst).nameWithoutExtension + "-nosub.mp4") }
            ?: File("$dst-nosub.mp4")
        return try {
            val transcode = transcodeVideo(srcVideo, cues, tmpVideo.path, outW, outH, facts)
            if (transcode is Outcome.Failure) {
                tmpVideo.delete()
                return transcode
            }
            val merged = mergeAudio(tmpVideo.path, srcVideo, dst)
            tmpVideo.delete()
            merged
        } catch (e: Exception) {
            try {
                tmpVideo.delete()
            } catch (_: Exception) {
            }
            Outcome.Failure(AppError("SUB_BURN", "ฝังซับไม่ได้: ${e.message}"))
        }
    }

    private data class VideoFacts(
        val width: Int,
        val height: Int,
        val outW: Int,
        val outH: Int,
        val durationMs: Long,
    )

    private fun videoInfoBlocking(src: String): VideoFacts? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(src)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = try {
                    format.getString(MediaFormat.KEY_MIME)
                } catch (_: Exception) {
                    null
                }
                if (mime != null && mime.startsWith("video/")) {
                    val w = format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                    val durationUs = try {
                        format.getLong(MediaFormat.KEY_DURATION)
                    } catch (_: Exception) {
                        0L
                    }
                    var outW = w
                    var outH = h
                    if (outW * outH > 1280 * 720) {
                        val scale = kotlin.math.sqrt((1280.0 * 720) / (outW * outH))
                        outW = ((outW * scale).toInt() / 2) * 2
                        outH = ((outH * scale).toInt() / 2) * 2
                    }
                    outW = (outW / 2) * 2
                    outH = (outH / 2) * 2
                    return VideoFacts(w, h, outW.coerceAtLeast(2), outH.coerceAtLeast(2), durationUs / 1000)
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun transcodeVideo(
        src: String,
        cues: List<Cue>,
        tmpOut: String,
        outW: Int,
        outH: Int,
        facts: VideoFacts,
    ): Outcome<Unit> {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var reader: ImageReader? = null
        try {
            extractor.setDataSource(src)
            var track = -1
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val candidate = try {
                    format.getString(MediaFormat.KEY_MIME)
                } catch (_: Exception) {
                    null
                }
                if (candidate != null && candidate.startsWith("video/")) {
                    track = i
                    mime = candidate
                    break
                }
            }
            if (track < 0) {
                return Outcome.Failure(AppError("SUB_NO_VIDEO", "ไฟล์นี้ไม่มีแทร็กวิดีโอ"))
            }
            extractor.selectTrack(track)
            reader = ImageReader.newInstance(facts.width, facts.height, PixelFormat.RGBA_8888, 2)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(extractor.getTrackFormat(track), reader.surface, null, 0)
            decoder.start()

            val colorFormat = pickEncoderColorFormat()
                ?: return Outcome.Failure(AppError("SUB_BURN", "เครื่องนี้เข้ารหัสวิดีโอแบบ CPU ไม่ได้"))
            val planar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH)
            encFormat.setInteger(
                MediaFormat.KEY_BIT_RATE,
                (outW * outH * 4).coerceIn(500_000, 8_000_000),
            )
            encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            File(tmpOut).parentFile?.mkdirs()
            muxer = MediaMuxer(tmpOut, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxTrack = -1
            var muxStarted = false
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 600_000
            var sawInputEos = false
            var sawDecEos = false
            var sawEncEos = false
            var pendingFrames = 0
            while (!sawEncEos) {
                if (System.currentTimeMillis() > deadline) {
                    return Outcome.Failure(AppError("SUB_BURN", "ฝังซับนานเกิน 10 นาที — ลองคลิปสั้นกว่านี้ครับ"))
                }
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)
                        if (buf == null) {
                            sawInputEos = true
                        } else {
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                if (!sawDecEos) {
                    when (val outIndex = decoder.dequeueOutputBuffer(decInfo, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> {
                            if (outIndex >= 0) {
                                if (decInfo.size > 0) {
                                    decoder.releaseOutputBuffer(outIndex, true)
                                    val image = acquireImage(reader)
                                    if (image != null) {
                                        try {
                                            val overlay = drawOverlay(image, cues, decInfo.presentationTimeUs / 1000, outW, outH, planar)
                                            feedEncoder(encoder, overlay, decInfo.presentationTimeUs)
                                            pendingFrames += 1
                                        } finally {
                                            image.close()
                                        }
                                    }
                                } else {
                                    decoder.releaseOutputBuffer(outIndex, false)
                                }
                                if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    sawDecEos = true
                                }
                            }
                        }
                    }
                } else if (pendingFrames >= 0) {
                    // Decoder drained: send EOS once.
                    val inIndex = encoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        pendingFrames = -1
                    }
                }
                when (val outIndex = encoder.dequeueOutputBuffer(encInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxStarted = true
                    }
                    else -> {
                        if (outIndex >= 0) {
                            val buf = encoder.getOutputBuffer(outIndex)
                            if (buf != null && encInfo.size > 0 && muxStarted) {
                                muxer.writeSampleData(muxTrack, buf, encInfo)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawEncEos = true
                            }
                        }
                    }
                }
            }
            try {
                muxer.stop()
            } catch (_: Exception) {
            }
            return Outcome.Success(Unit)
        } catch (e: Exception) {
            try {
                File(tmpOut).delete()
            } catch (_: Exception) {
            }
            return Outcome.Failure(AppError("SUB_BURN", "เข้ารหัสวิดีโอไม่ได้: ${e.message}"))
        } finally {
            try {
                decoder?.stop()
            } catch (_: Exception) {
            }
            try {
                decoder?.release()
            } catch (_: Exception) {
            }
            try {
                encoder?.stop()
            } catch (_: Exception) {
            }
            try {
                encoder?.release()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            try {
                reader?.close()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun pickEncoderColorFormat(): Int? {
        return try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val info = codec.codecInfo
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val formats = caps.colorFormats.toSet()
                when {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in formats ->
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats ->
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    else -> null
                }
            } finally {
                try {
                    codec.release()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Renders one decoded frame + active cue into YUV420 bytes for the encoder. */
    /** Waits briefly for the rendered frame (decoder surface is async). */
    private fun acquireImage(reader: ImageReader): android.media.Image? {
        repeat(10) {
            val image = try {
                reader.acquireNextImage()
            } catch (_: Exception) {
                null
            }
            if (image != null) return image
            try {
                Thread.sleep(5)
            } catch (_: InterruptedException) {
            }
        }
        return null
    }

    private fun drawOverlay(
        image: android.media.Image,
        cues: List<Cue>,
        timeMs: Long,
        outW: Int,
        outH: Int,
        planar: Boolean,
    ): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (pixelStride == 4 && rowStride == w * 4) {
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            val pixels = IntArray(w * h)
            val row = ByteArray(rowStride)
            for (y in 0 until h) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, rowStride)
                for (x in 0 until w) {
                    val o = x * pixelStride
                    val r = row[o].toInt() and 0xFF
                    val g = row[o + 1].toInt() and 0xFF
                    val b = row[o + 2].toInt() and 0xFF
                    val a = row[o + 3].toInt() and 0xFF
                    pixels[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }
        val scaled = if (bitmap.width != outW || bitmap.height != outH) {
            Bitmap.createScaledBitmap(bitmap, outW, outH, true).also { bitmap.recycle() }
        } else {
            bitmap
        }
        val cue = cues.firstOrNull { timeMs in it.startMs until it.endMs }
        if (cue != null) {
            val canvas = Canvas(scaled)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = outH * 0.055f
                textAlign = Paint.Align.CENTER
            }
            val bg = Paint().apply { color = Color.argb(160, 0, 0, 0) }
            val lineH = paint.textSize * 1.25f
            val totalH = lineH * cue.lines.size
            var baseline = outH - outH * 0.06f - totalH + paint.textSize
            val widest = cue.lines.maxOf { paint.measureText(it) }
            canvas.drawRoundRect(
                RectF(
                    (outW - widest) / 2 - 24f,
                    baseline - paint.textSize - 12f,
                    (outW + widest) / 2 + 24f,
                    baseline + lineH * (cue.lines.size - 1) + 24f,
                ),
                18f, 18f, bg,
            )
            for (line in cue.lines) {
                canvas.drawText(line, outW / 2f, baseline, paint)
                baseline += lineH
            }
        }
        val pixels = IntArray(outW * outH)
        scaled.getPixels(pixels, 0, outW, 0, 0, outW, outH)
        scaled.recycle()
        return if (planar) Yuv.toI420(pixels, outW, outH) else Yuv.toNV12(pixels, outW, outH)
    }

    private fun feedEncoder(encoder: MediaCodec, yuv: ByteArray, ptsUs: Long) {
        var fed = false
        while (!fed) {
            val inIndex = encoder.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buf = encoder.getInputBuffer(inIndex)
                if (buf != null && buf.remaining() >= yuv.size) {
                    buf.clear()
                    buf.put(yuv)
                    encoder.queueInputBuffer(inIndex, 0, yuv.size, ptsUs, 0)
                } else {
                    // No room: queue an empty buffer so timestamps keep moving.
                    encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
                }
                fed = true
            }
        }
    }

    /** Merges transcoded video-only [videoOnly] with the audio track of [srcAudio]. */
    private fun mergeAudio(videoOnly: String, srcAudio: String, dst: String): Outcome<VideoInfo> {
        val vExt = MediaExtractor()
        val aExt = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            vExt.setDataSource(videoOnly)
            var vTrack = -1
            for (i in 0 until vExt.trackCount) {
                val mime = try {
                    vExt.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                } catch (_: Exception) {
                    null
                }
                if (mime != null && mime.startsWith("video/")) {
                    vTrack = i
                    break
                }
            }
            if (vTrack < 0) {
                return Outcome.Failure(AppError("SUB_BURN", "วิดีโอที่เข้ารหัสไม่มีภาพ"))
            }
            var aTrack = -1
            try {
                aExt.setDataSource(srcAudio)
                for (i in 0 until aExt.trackCount) {
                    val mime = try {
                        aExt.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    } catch (_: Exception) {
                        null
                    }
                    if (mime != null && mime.startsWith("audio/")) {
                        aTrack = i
                        break
                    }
                }
            } catch (_: Exception) {
                aTrack = -1
            }
            File(dst).parentFile?.mkdirs()
            muxer = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            vExt.selectTrack(vTrack)
            val muxV = muxer.addTrack(vExt.getTrackFormat(vTrack))
            var muxA = -1
            if (aTrack >= 0) {
                aExt.selectTrack(aTrack)
                muxA = muxer.addTrack(aExt.getTrackFormat(aTrack))
            }
            muxer.start()
            copyTrack(vExt, muxer, muxV)
            if (aTrack >= 0) copyTrack(aExt, muxer, muxA)
            try {
                muxer.stop()
            } catch (_: Exception) {
            }
            return when (val probed = Mp4Probe.probe(File(dst))) {
                is Outcome.Failure -> Outcome.Success(VideoInfo(dst, "MP4", -1))
                is Outcome.Success -> Outcome.Success(
                    VideoInfo(
                        dst, probed.value.format, probed.value.durationMs,
                        probed.value.width, probed.value.height, probed.value.hasAudio, probed.value.sizeBytes,
                    ),
                )
            }
        } catch (e: Exception) {
            try {
                File(dst).delete()
            } catch (_: Exception) {
            }
            return Outcome.Failure(AppError("SUB_BURN", "รวมเสียงไม่ได้: ${e.message}"))
        } finally {
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            try {
                vExt.release()
            } catch (_: Exception) {
            }
            try {
                aExt.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, muxTrack: Int) {
        val buffer = ByteBuffer.allocate(512 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            info.offset = 0
            info.size = extractor.readSampleData(buffer, 0)
            if (info.size < 0) break
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(muxTrack, buffer, info)
            extractor.advance()
        }
    }
}
