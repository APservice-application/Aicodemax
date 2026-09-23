package com.aicodemax.app

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
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

    override suspend fun proxy(src: String, dst: String, maxDim: Int): Outcome<VideoInfo> =
        withContext(Dispatchers.IO) {
            if (!File(src).isFile) {
                return@withContext Outcome.Failure(AppError("VIDEO_NO_FILE", "ไม่พบไฟล์ $src"))
            }
            if (maxDim < 160) {
                return@withContext Outcome.Failure(AppError("VIDEO_PROXY", "maxDim ต้อง ≥ 160"))
            }
            try {
                proxyImpl(src, dst, maxDim)
            } catch (e: Exception) {
                Outcome.Failure(AppError("VIDEO_PROXY", "ทำพร็อกซีไม่ได้: ${e.message}"))
            }
        }

    /**
     * CP-102: decode → downscale → AVC re-encode (video only; proxies drop
     * audio by design). Supports planar / semi-planar(NV12) / flexible YUV.
     */
    private fun proxyImpl(src: String, dst: String, maxDim: Int): Outcome<VideoInfo> {
        val extractor = MediaExtractor()
        extractor.setDataSource(src)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        if (track == null) {
            extractor.release()
            return Outcome.Failure(AppError("VIDEO_PROXY", "ไฟล์นี้ไม่มีแทร็กวิดีโอ"))
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val w = format.getInteger(MediaFormat.KEY_WIDTH)
        val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val longer = maxOf(w, h)
        val scale = if (longer <= maxDim) 1.0 else maxDim.toDouble() / longer
        val w2 = ((w * scale).toInt().coerceAtLeast(2)) and 1.inv()
        val h2 = ((h * scale).toInt().coerceAtLeast(2)) and 1.inv()
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 120)
        } else {
            30
        }
        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w2, h2)
        encFormat.setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
        encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
        encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        File(dst).parentFile?.mkdirs()
        val muxer = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxStarted = false
        var decColor = -1
        var stride = w
        var sliceH = h
        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var decInputDone = false
        var decOutputDone = false
        var encDone = false
        var encEosQueued = false
        var durationUs = 0L
        try {
            while (!encDone) {
                if (!decInputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decInputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(decInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        decColor = f.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                        stride = if (f.containsKey("stride")) f.getInteger("stride") else w
                        sliceH = if (f.containsKey("slice-height")) f.getInteger("slice-height") else h
                        val ok = decColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                            decColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                            decColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                        if (!ok) {
                            return Outcome.Failure(AppError("VIDEO_PROXY", "รูปแบบสี decoder ไม่รองรับ ($decColor)"))
                        }
                    }
                    outIdx >= 0 -> {
                        val buf = decoder.getOutputBuffer(outIdx)
                        if (buf != null && decInfo.size > 0) {
                            durationUs = maxOf(durationUs, decInfo.presentationTimeUs)
                            val i420 = toI420(buf, decColor, stride, sliceH, w, h)
                            val small = downscaleI420(i420, w, h, w2, h2)
                            var fed = false
                            while (!fed) {
                                val einIdx = encoder.dequeueInputBuffer(10_000)
                                if (einIdx >= 0) {
                                    val ebuf = encoder.getInputBuffer(einIdx)!!
                                    ebuf.clear()
                                    ebuf.put(small)
                                    encoder.queueInputBuffer(einIdx, 0, small.size, decInfo.presentationTimeUs, 0)
                                    fed = true
                                }
                            }
                        }
                        if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decOutputDone = true
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                    }
                }
                if (decOutputDone && !encEosQueued) {
                    var queued = false
                    while (!queued) {
                        val einIdx = encoder.dequeueInputBuffer(10_000)
                        if (einIdx >= 0) {
                            encoder.queueInputBuffer(einIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            queued = true
                            encEosQueued = true
                        }
                    }
                }
                val eoutIdx = encoder.dequeueOutputBuffer(encInfo, 10_000)
                when {
                    eoutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxStarted = true
                    }
                    eoutIdx >= 0 -> {
                        val ebuf = encoder.getOutputBuffer(eoutIdx)
                        if (ebuf != null && encInfo.size > 0 && encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (!muxStarted) {
                                muxTrack = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxStarted = true
                            }
                            muxer.writeSampleData(muxTrack, ebuf, encInfo)
                        }
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encDone = true
                        }
                        encoder.releaseOutputBuffer(eoutIdx, false)
                    }
                }
            }
        } finally {
            try {
                decoder.stop()
            } catch (_: Exception) {
            }
            try {
                decoder.release()
            } catch (_: Exception) {
            }
            try {
                encoder.stop()
            } catch (_: Exception) {
            }
            try {
                encoder.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
            try {
                if (muxStarted) muxer.stop()
            } catch (_: Exception) {
            }
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }
        if (!muxStarted) {
            return Outcome.Failure(AppError("VIDEO_PROXY", "เข้ารหัสพร็อกซีไม่ได้ (ไม่มีเฟรมออก)"))
        }
        return Outcome.Success(VideoInfo(dst, "MP4", durationUs / 1000, w2, h2, hasAudio = false, sizeBytes = File(dst).length()))
    }

    /** Decoder YUV (planar/NV12/flexible) → I420 at visible [w]x[h]. */
    private fun toI420(buf: java.nio.ByteBuffer, color: Int, stride: Int, sliceH: Int, w: Int, h: Int): ByteArray {
        val planar = color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val ySize = stride * sliceH
        val uvSize = (stride / 2) * (sliceH / 2)
        val out = ByteArray(w * h * 3 / 2)
        val dup = buf.duplicate()
        dup.clear()
        // Y.
        for (row in 0 until h) {
            dup.position(row * stride)
            dup.get(out, row * w, w)
        }
        if (planar) {
            for (row in 0 until h / 2) {
                dup.position(ySize + row * (stride / 2))
                dup.get(out, w * h + row * (w / 2), w / 2)
            }
            for (row in 0 until h / 2) {
                dup.position(ySize + uvSize + row * (stride / 2))
                dup.get(out, w * h + (w * h / 4) + row * (w / 2), w / 2)
            }
        } else {
            // NV12 interleaved UV.
            val uv = ByteArray((stride / 2) * (sliceH / 2) * 2)
            for (row in 0 until sliceH / 2) {
                dup.position(ySize + row * stride)
                dup.get(uv, row * stride, stride)
            }
            for (row in 0 until h / 2) {
                for (col in 0 until w / 2) {
                    out[w * h + row * (w / 2) + col] = uv[row * stride + col * 2]
                    out[w * h + (w * h / 4) + row * (w / 2) + col] = uv[row * stride + col * 2 + 1]
                }
            }
        }
        return out
    }

    /** Bilinear-ish I420 downscale (box-sampled Y, nearest UV). */
    private fun downscaleI420(src: ByteArray, w: Int, h: Int, w2: Int, h2: Int): ByteArray {
        if (w == w2 && h == h2) return src
        val out = ByteArray(w2 * h2 * 3 / 2)
        val xRatio = w.toDouble() / w2
        val yRatio = h.toDouble() / h2
        for (y in 0 until h2) {
            val sy = (y * yRatio).toInt().coerceIn(0, h - 1)
            for (x in 0 until w2) {
                out[y * w2 + x] = src[sy * w + (x * xRatio).toInt().coerceIn(0, w - 1)]
            }
        }
        val uw = w / 2
        val uh = h / 2
        val uw2 = w2 / 2
        val uh2 = h2 / 2
        val uOff = w * h
        val vOff = w * h + uw * uh
        val ouOff = w2 * h2
        val ovOff = w2 * h2 + uw2 * uh2
        for (y in 0 until uh2) {
            val sy = (y * uh / uh2).coerceIn(0, uh - 1)
            for (x in 0 until uw2) {
                val sx = (x * uw / uw2).coerceIn(0, uw - 1)
                out[ouOff + y * uw2 + x] = src[uOff + sy * uw + sx]
                out[ovOff + y * uw2 + x] = src[vOff + sy * uw + sx]
            }
        }
        return out
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
