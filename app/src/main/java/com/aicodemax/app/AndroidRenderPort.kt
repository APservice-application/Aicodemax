package com.aicodemax.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.Clip
import com.aicodemax.data.media.ClipTransform
import com.aicodemax.data.media.OverlayText
import com.aicodemax.data.media.MediaAsset
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import com.aicodemax.tools.audio.PcmAudio
import com.aicodemax.tools.media.MediaProjectPort
import com.aicodemax.tools.render.FileRenderQueue
import com.aicodemax.tools.render.Qc
import com.aicodemax.tools.render.RenderJob
import com.aicodemax.tools.render.RenderPort
import com.aicodemax.tools.render.RenderPreset
import com.aicodemax.tools.render.RenderStatus
import com.aicodemax.tools.subtitle.Yuv
import com.aicodemax.tools.video.VideoPort
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CP-67 real renderer (§19–21): timeline → MP4 + QC + previews + export.
 *
 * Two paths:
 * - fast: a single full-quality video clip is stream-copied (no quality loss);
 * - full: multi-clip concat via decode → scale → AVC encode (≤ preset height,
 *   30fps), stills as repeated frames, A-track clips mixed to AAC.
 *
 * Honest v0 limits: video-clip audio is skipped in the full path (noted on
 * the job), audio mixes cap at 3 min, no mid-render cancel.
 */
class AndroidRenderPort(
    private val media: MediaProjectPort,
    private val mediaRoot: File,
    private val decodeAudio: (String) -> Outcome<PcmAudio>,
    private val video: VideoPort,
    private val appContext: Context,
) : RenderPort {
    private val queue = FileRenderQueue(mediaRoot)
    private val outDir = File(mediaRoot, "render-out").also { it.mkdirs() }

    override suspend fun enqueue(projectId: String, presetName: String?): Outcome<RenderJob> =
        withContext(Dispatchers.IO) {
            when (media.openProject(projectId)) {
                is Outcome.Failure -> Outcome.Failure(AppError("RENDER_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
                is Outcome.Success -> Outcome.Success(queue.enqueue(projectId, RenderPreset.byName(presetName)))
            }
        }

    override suspend fun runNow(projectId: String, presetName: String?): Outcome<RenderJob> =
        withContext(Dispatchers.IO) {
            when (val enqueued = enqueue(projectId, presetName)) {
                is Outcome.Failure -> enqueued
                is Outcome.Success -> run(enqueued.value.id)
            }
        }

    override suspend fun run(jobId: String): Outcome<RenderJob> =
        withContext(Dispatchers.IO) {
            val job = queue.get(jobId)
                ?: return@withContext Outcome.Failure(AppError("RENDER_NO_JOB", "ไม่มีงาน $jobId"))
            if (job.status == RenderStatus.RUNNING) {
                return@withContext Outcome.Failure(AppError("RENDER_BUSY", "งานกำลังรันอยู่แล้ว"))
            }
            var current = job.copy(
                status = RenderStatus.RUNNING, progress = 1,
                startedAt = System.currentTimeMillis(), finishedAt = 0, error = "",
            )
            queue.save(current)
            val progress: (Int) -> Unit = { pct ->
                current = current.copy(progress = pct)
                queue.save(current)
            }
            try {
                val project = media.openProject(job.projectId).fold(
                    onSuccess = { it },
                    onFailure = { return@withContext fail(current, it.message) },
                )
                val assets = media.listAssets(job.projectId).fold(
                    onSuccess = { it },
                    onFailure = { return@withContext fail(current, it.message) },
                )
                try {
                    media.checkpoint(job.projectId, "before-render", "AI")
                } catch (_: Exception) {
                }
                val plan = planRender(project, assets, job)
                    ?: return@withContext fail(current, planError(project, assets))
                progress(5)
                val outPath = File(outDir, "${job.id}.mp4").path
                val notes = mutableListOf<String>()
                if (plan.fast) {
                    val seg = plan.segments[0]
                    when (val trimmed = fastTrim(seg, outPath, plan)) {
                        is Outcome.Failure -> return@withContext fail(current, trimmed.error.message)
                        is Outcome.Success -> Unit
                    }
                    progress(70)
                } else {
                    transcode(plan, outPath, job.preset, progress, notes).fold(
                        onSuccess = { Unit },
                        onFailure = { return@withContext fail(current, it.message) },
                    )
                }
                val report = Qc.check(
                    plan.timeline.durationMs, plan.wantAudio, outPath,
                    job.preset.maxHeight, video,
                )
                val previews = makePreviews(outPath, plan.timeline.durationMs)
                current = current.copy(
                    status = if (report.passed) RenderStatus.DONE else RenderStatus.FAILED,
                    progress = 100,
                    finishedAt = System.currentTimeMillis(),
                    outputPath = outPath,
                    previews = previews,
                    qc = report,
                    notes = notes,
                    error = if (report.passed) "" else "QC ไม่ผ่าน",
                )
                queue.save(current)
                Outcome.Success(current)
            } catch (e: Exception) {
                fail(current, e.message ?: e.javaClass.simpleName)
            }
        }

    override suspend fun status(jobId: String): Outcome<RenderJob> =
        withContext(Dispatchers.IO) {
            queue.get(jobId)?.let { Outcome.Success(it) }
                ?: Outcome.Failure(AppError("RENDER_NO_JOB", "ไม่มีงาน $jobId"))
        }

    override suspend fun list(): Outcome<List<RenderJob>> =
        withContext(Dispatchers.IO) { Outcome.Success(queue.list()) }

    override suspend fun retry(jobId: String): Outcome<RenderJob> =
        withContext(Dispatchers.IO) {
            queue.retry(jobId)?.let { Outcome.Success(it) }
                ?: Outcome.Failure(AppError("RENDER_NO_JOB", "ไม่มีงาน $jobId"))
        }

    override suspend fun approve(jobId: String): Outcome<RenderJob> =
        withContext(Dispatchers.IO) {
            val job = queue.get(jobId)
                ?: return@withContext Outcome.Failure(AppError("RENDER_NO_JOB", "ไม่มีงาน $jobId"))
            if (job.status != RenderStatus.DONE || job.qc?.passed != true) {
                return@withContext Outcome.Failure(AppError("RENDER_QC", "QC ยังไม่ผ่าน อนุมัติไม่ได้"))
            }
            val approved = job.copy(approved = true)
            queue.save(approved)
            Outcome.Success(approved)
        }

    override suspend fun export(jobId: String): Outcome<RenderJob> =
        withContext(Dispatchers.IO) {
            val job = queue.get(jobId)
                ?: return@withContext Outcome.Failure(AppError("RENDER_NO_JOB", "ไม่มีงาน $jobId"))
            if (!job.approved) {
                return@withContext Outcome.Failure(AppError("RENDER_APPROVAL", "ต้องอนุมัติก่อนเอ็กซ์พอร์ต"))
            }
            if (!File(job.outputPath).isFile) {
                return@withContext Outcome.Failure(AppError("RENDER_NO_FILE", "ไม่พบไฟล์ ${job.outputPath}"))
            }
            try {
                media.checkpoint(job.projectId, "before-export", "AI")
            } catch (_: Exception) {
            }
            when (val uri = exportFile(job)) {
                is Outcome.Failure -> uri
                is Outcome.Success -> {
                    val exported = job.copy(exportedUri = uri.value.exportedUri)
                    queue.save(exported)
                    Outcome.Success(exported)
                }
            }
        }

    private fun fail(job: RenderJob, message: String): Outcome<RenderJob> {
        val failed = job.copy(
            status = RenderStatus.FAILED, finishedAt = System.currentTimeMillis(), error = message,
        )
        queue.save(failed)
        return Outcome.Success(failed)
    }

    // ---- planning ----

    private data class Segment(
        val clip: Clip,
        val file: File,
        val kind: MediaKind,
        val width: Int = -1,
        val height: Int = -1,
        val transform: ClipTransform? = null,
    )

    private data class RenderPlan(
        val timeline: Timeline,
        val segments: List<Segment>,
        val audioClips: List<Segment>,
        val wantAudio: Boolean,
        val fast: Boolean,
        val texts: List<OverlayText> = emptyList(),
    )

    private suspend fun planRender(project: Project, assets: List<MediaAsset>, job: RenderJob): RenderPlan? {
        val timeline = project.timeline
        val byId = assets.associateBy { it.id }
        val videoSegs = mutableListOf<Segment>()
        val audioSegs = mutableListOf<Segment>()
        for (track in timeline.tracks.sortedBy { it.id }) {
            // CP-72: hidden video tracks and muted audio tracks are skipped.
            if (track.kind != MediaKind.AUDIO && track.hidden) continue
            if (track.kind == MediaKind.AUDIO && track.muted) continue
            for (clip in track.clips.sortedBy { it.atMs }) {
                val asset = byId[clip.assetId] ?: return null
                val file = File(File(mediaRoot, "${project.id}/assets"), asset.fileName)
                if (!file.isFile) return null
                when (track.kind) {
                    MediaKind.VIDEO, MediaKind.IMAGE -> videoSegs += Segment(clip, file, track.kind, transform = clip.transform)
                    MediaKind.AUDIO -> if (job.preset.includeAudio) {
                        audioSegs += Segment(clip, file, track.kind)
                    }
                }
            }
        }
        if (videoSegs.isEmpty()) return null
        // Probe video dims for scaling decisions.
        val probed = videoSegs.map { seg ->
            if (seg.kind != MediaKind.VIDEO) return@map seg
            when (val info = video.info(seg.file.path)) {
                is Outcome.Failure -> return null
                is Outcome.Success -> seg.copy(width = info.value.width, height = info.value.height)
            }
        }
        val single = probed.singleOrNull()
        val fast = single != null && single.kind == MediaKind.VIDEO &&
            audioSegs.isEmpty() && single.clip.volume == 100 &&
            single.clip.atMs == 0L &&
            (single.transform == null || single.transform.isIdentity) &&
            single.height in 1..job.preset.maxHeight &&
            timeline.texts.isEmpty()
        return RenderPlan(
            timeline = timeline,
            segments = probed.sortedBy { it.clip.atMs },
            audioClips = audioSegs,
            wantAudio = audioSegs.isNotEmpty(),
            fast = fast,
            texts = timeline.texts,
        )
    }

    private fun planError(project: Project, assets: List<MediaAsset>): String {
        val clips = project.timeline.tracks.flatMap { it.clips }
        if (clips.none { true }) return "ไทม์ไลน์ว่าง ไม่มีอะไรให้เรนเดอร์"
        val ids = assets.map { it.id }.toSet()
        val unknown = clips.firstOrNull { it.assetId !in ids }
        if (unknown != null) return "คลิปอ้าง asset ที่ไม่มี (${unknown.assetId})"
        val missing = clips.firstOrNull { clip ->
            val asset = assets.first { it.id == clip.assetId }
            !File(File(mediaRoot, "${project.id}/assets"), asset.fileName).isFile
        }
        if (missing != null) return "ไม่พบไฟล์ของคลิป ${missing.id}"
        val videoOnly = clips.isNotEmpty() && project.timeline.tracks
            .filter { it.kind != MediaKind.AUDIO }.all { it.clips.isEmpty() }
        if (videoOnly) return "มีแต่คลิปเสียง ยังเรนเดอร์เสียงอย่างเดียวไม่ได้ (v0)"
        return "อ่านไทม์ไลน์ไม่ได้ หรือวิดีโอต้นฉบับเสีย"
    }

    private suspend fun fastTrim(seg: Segment, outPath: String, plan: RenderPlan): Outcome<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val ext = MediaExtractor()
                ext.setDataSource(seg.file.path)
                val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    val startUs = seg.clip.startMs * 1000
                    val endUs = seg.clip.endMs * 1000
                    val indexMap = mutableMapOf<Int, Int>()
                    for (i in 0 until ext.trackCount) {
                        val format = ext.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                            indexMap[i] = muxer.addTrack(format)
                        }
                    }
                    if (indexMap.isEmpty()) {
                        return@withContext Outcome.Failure(AppError("RENDER_TRIM", "ไม่พบแทร็กวิดีโอ/เสียง"))
                    }
                    muxer.start()
                    val buf = ByteBuffer.allocate(2 * 1024 * 1024)
                    val info = android.media.MediaCodec.BufferInfo()
                    for ((trackIndex, _) in indexMap) {
                        ext.selectTrack(trackIndex)
                        ext.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        while (true) {
                            val size = ext.readSampleData(buf, 0)
                            if (size < 0) break
                            val pts = ext.sampleTime
                            if (pts > endUs) break
                            if (pts < startUs) {
                                ext.advance()
                                continue
                            }
                            info.set(0, size, pts - startUs, ext.sampleFlags)
                            muxer.writeSampleData(indexMap[trackIndex]!!, buf, info)
                            ext.advance()
                        }
                        ext.unselectTrack(trackIndex)
                    }
                    if (plan.timeline.durationMs <= 0) {
                        return@withContext Outcome.Failure(AppError("RENDER_TRIM", "ไทม์ไลน์ว่าง"))
                    }
                    Outcome.Success(Unit)
                } finally {
                    try {
                        muxer.stop()
                    } catch (_: Exception) {
                    }
                    muxer.release()
                    ext.release()
                }
            } catch (e: Exception) {
                Outcome.Failure(AppError("RENDER_TRIM", e.message ?: "trim ล้มเหลว"))
            }
        }

    // ---- full transcode ----

    private suspend fun transcode(
        plan: RenderPlan,
        outPath: String,
        preset: RenderPreset,
        progress: (Int) -> Unit,
        notes: MutableList<String>,
    ): Outcome<Unit> = withContext(Dispatchers.IO) {
        try {
            val firstVideo = plan.segments.firstOrNull { it.kind == MediaKind.VIDEO && it.height > 0 }
            val outW: Int
            val outH: Int
            if (firstVideo != null) {
                val scale = preset.maxHeight.toDouble() / firstVideo.height
                outH = (if (scale >= 1.0) firstVideo.height else preset.maxHeight) and 1.inv()
                outW = ((firstVideo.width * (outH.toDouble() / firstVideo.height)).toInt() + 1) and 1.inv()
            } else {
                outH = preset.maxHeight and 1.inv()
                outW = (preset.maxHeight * 16 / 9) and 1.inv()
            }
            if (plan.segments.any { it.kind == MediaKind.VIDEO } && plan.audioClips.isEmpty()) {
                notes += "ข้ามเสียงจากคลิปวิดีโอ (v0 รองรับเสียงจากแทร็ก A1/A2 เท่านั้น)"
            }
            val colorFormat = pickEncoderColorFormat()
                ?: return@withContext Outcome.Failure(AppError("RENDER_CODEC", "เครื่องนี้เข้ารหัสวิดีโอแบบ CPU ไม่ได้"))
            val planar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            val videoTemp = File.createTempFile("render-v-", ".mp4", outDir)
            try {
                encodeVideo(plan, videoTemp.path, outW, outH, preset, colorFormat, planar, progress).fold(
                    onSuccess = { Unit },
                    onFailure = { return@withContext Outcome.Failure(it) },
                )
                progress(80)
                if (plan.wantAudio) {
                    val audioTemp = File.createTempFile("render-a-", ".m4a", outDir)
                    try {
                        mixAndEncodeAudio(plan, audioTemp.path).fold(
                            onSuccess = { Unit },
                            onFailure = { return@withContext Outcome.Failure(it) },
                        )
                        mergeAv(videoTemp.path, audioTemp.path, outPath)
                    } finally {
                        audioTemp.delete()
                    }
                } else {
                    videoTemp.copyTo(File(outPath), overwrite = true)
                }
                progress(90)
                Outcome.Success(Unit)
            } finally {
                videoTemp.delete()
            }
        } catch (e: Exception) {
            Outcome.Failure(AppError("RENDER_CODEC", e.message ?: "transcode ล้มเหลว"))
        }
    }

    private fun encodeVideo(
        plan: RenderPlan,
        dst: String,
        outW: Int,
        outH: Int,
        preset: RenderPreset,
        colorFormat: Int,
        planar: Boolean,
        progress: (Int) -> Unit,
    ): Outcome<Unit> {
        val totalMs = plan.timeline.durationMs.coerceAtLeast(1)
        val frameStepUs = 1_000_000L / 30
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxStarted = false
        try {
            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH)
            encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, preset.videoBitrate)
            encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                var ptsUs = 0L
                val feed = fun(yuv: ByteArray) {
                    feedEncoder(encoder, yuv, ptsUs)
                    ptsUs += frameStepUs
                }
                val total = plan.segments.size
                plan.segments.forEachIndexed { index, seg ->
                    if (seg.kind == MediaKind.IMAGE) {
                        feedStill(seg, outW, outH, planar, plan.texts, feed)
                    } else {
                        decodeSegment(seg, outW, outH, planar, seg.transform, plan.texts, feed)
                    }
                    progress(5 + 70 * (index + 1) / total.coerceAtLeast(1))
                }
                // Encoder EOS + drain.
                var fed = false
                while (!fed) {
                    val inIndex = encoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        fed = true
                    }
                }
                muxer = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val encInfo = android.media.MediaCodec.BufferInfo()
                var sawEos = false
                while (!sawEos) {
                    val outIndex = encoder.dequeueOutputBuffer(encInfo, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxStarted = true
                        }
                        outIndex >= 0 -> {
                            val encoded = encoder.getOutputBuffer(outIndex)
                            if (encInfo.size > 0 && muxStarted && encoded != null &&
                                encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                muxer.writeSampleData(trackIndex, encoded, encInfo)
                            }
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEos = true
                            encoder.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
            } finally {
                try {
                    encoder.stop()
                } catch (_: Exception) {
                }
                encoder.release()
            }
            if (!muxStarted) return Outcome.Failure(AppError("RENDER_CODEC", "encoder ไม่ให้ข้อมูลออกมา"))
            return Outcome.Success(Unit)
        } catch (e: Exception) {
            return Outcome.Failure(AppError("RENDER_CODEC", e.message ?: "encode ล้มเหลว"))
        } finally {
            try {
                if (muxStarted) muxer?.stop()
            } catch (_: Exception) {
            }
            muxer?.release()
        }
    }

    private fun decodeSegment(
        seg: Segment,
        outW: Int,
        outH: Int,
        planar: Boolean,
        transform: ClipTransform?,
        texts: List<OverlayText>,
        feed: (ByteArray) -> Unit,
    ) {
        val active = transform?.takeUnless { it.isIdentity }
        val startUs = seg.clip.startMs * 1000
        val endUs = seg.clip.endMs * 1000
        val readerW = if (seg.width > 0) seg.width else outW
        val readerH = if (seg.height > 0) seg.height else outH
        val reader = ImageReader.newInstance(readerW, readerH, android.graphics.ImageFormat.YUV_420_888, 2)
        val ext = MediaExtractor()
        ext.setDataSource(seg.file.path)
        val trackIndex = (0 until ext.trackCount).firstOrNull { i ->
            (ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")
        } ?: run { ext.release(); reader.close(); return }
        ext.selectTrack(trackIndex)
        ext.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val decoder = MediaCodec.createDecoderByType(
            ext.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME)!!,
        )
        try {
            decoder.configure(ext.getTrackFormat(trackIndex), reader.surface, null, 0)
            decoder.start()
            val decInfo = android.media.MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)
                        val size = if (buf != null) ext.readSampleData(buf, 0) else -1
                        if (size < 0 || ext.sampleTime > endUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(decInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        if (decInfo.size > 0 && decInfo.presentationTimeUs >= startUs) {
                            decoder.releaseOutputBuffer(outIndex, true)
                            val image = acquireImage(reader)
                            if (image != null) {
                                try {
                                    val timelineMs = seg.clip.atMs + (decInfo.presentationTimeUs / 1000 - seg.clip.startMs)
                                    feed(frameYuv(image, active, texts, timelineMs, outW, outH, planar))
                                } finally {
                                    image.close()
                                }
                            }
                        } else {
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                        if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }
        } finally {
            try {
                decoder.stop()
            } catch (_: Exception) {
            }
            decoder.release()
            ext.release()
            reader.close()
        }
    }

    private fun feedStill(
        seg: Segment,
        outW: Int,
        outH: Int,
        planar: Boolean,
        texts: List<OverlayText>,
        feed: (ByteArray) -> Unit,
    ) {
        val raw = BitmapFactory.decodeFile(seg.file.path) ?: return
        val active = seg.transform?.takeUnless { it.isIdentity }
        val stillEnd = seg.clip.atMs + (seg.clip.endMs - seg.clip.startMs)
        val live = texts.filter { it.startMs < stillEnd && it.endMs > seg.clip.atMs }
        if (active != null || live.isNotEmpty()) {
            feedStillComposed(raw, active, live, seg, outW, outH, planar, feed)
            return
        }
        val scaled = centerCrop(raw, outW, outH)
        val pixels = IntArray(outW * outH)
        scaled.getPixels(pixels, 0, outW, 0, 0, outW, outH)
        scaled.recycle()
        val yuv = if (planar) Yuv.toI420(pixels, outW, outH) else Yuv.toNV12(pixels, outW, outH)
        val frames = ((seg.clip.endMs - seg.clip.startMs) * 30 / 1000).toInt().coerceIn(1, 30 * 600)
        repeat(frames) { feed(yuv) }
    }

    // ---- CP-73 clip transform (§12) ----

    private fun transformFrameToYuv(
        image: android.media.Image,
        t: ClipTransform,
        outW: Int,
        outH: Int,
        planar: Boolean,
    ): ByteArray {
        val pixels = yuv420888ToArgb(image)
        val composed = composeFrame(pixels, image.width, image.height, t, outW, outH)
        return if (planar) Yuv.toI420(composed, outW, outH) else Yuv.toNV12(composed, outW, outH)
    }

    private fun feedStillComposed(
        raw: Bitmap,
        t: ClipTransform?,
        live: List<OverlayText>,
        seg: Segment,
        outW: Int,
        outH: Int,
        planar: Boolean,
        feed: (ByteArray) -> Unit,
    ) {
        val pixels = IntArray(raw.width * raw.height)
        raw.getPixels(pixels, 0, raw.width, 0, 0, raw.width, raw.height)
        val base = if (t != null) {
            composeFrame(pixels, raw.width, raw.height, t, outW, outH)
        } else {
            scaleArgb(centerCropPixels(pixels, raw.width, raw.height, outW, outH), outW, outH, outW, outH)
        }
        val frames = ((seg.clip.endMs - seg.clip.startMs) * 30 / 1000).toInt().coerceIn(1, 30 * 600)
        if (live.isEmpty()) {
            val yuv = if (planar) Yuv.toI420(base, outW, outH) else Yuv.toNV12(base, outW, outH)
            repeat(frames) { feed(yuv) }
            return
        }
        repeat(frames) { i ->
            val timelineMs = seg.clip.atMs + i * 1000L / 30
            val frame = base.copyOf()
            drawTexts(frame, outW, outH, live.filter { timelineMs in it.startMs until it.endMs }, timelineMs)
            feed(if (planar) Yuv.toI420(frame, outW, outH) else Yuv.toNV12(frame, outW, outH))
        }
    }

    private fun centerCropPixels(pixels: IntArray, w: Int, h: Int, outW: Int, outH: Int): IntArray {
        val srcAspect = w.toDouble() / h
        val dstAspect = outW.toDouble() / outH
        val cw: Int
        val ch: Int
        if (srcAspect > dstAspect) {
            ch = h
            cw = (h * dstAspect).toInt().coerceIn(1, w)
        } else {
            cw = w
            ch = (w / dstAspect).toInt().coerceIn(1, h)
        }
        val x0 = (w - cw) / 2
        val y0 = (h - ch) / 2
        // Reuse Bitmap for the crop+scale (still path runs once per still).
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        src.setPixels(pixels, 0, w, 0, 0, w, h)
        val cropped = Bitmap.createBitmap(src, x0, y0, cw, ch)
        val scaled = Bitmap.createScaledBitmap(cropped, outW, outH, true)
        val out = IntArray(outW * outH)
        scaled.getPixels(out, 0, outW, 0, 0, outW, outH)
        src.recycle()
        if (cropped != src) cropped.recycle()
        if (scaled != cropped) scaled.recycle()
        return out
    }

    /** One decoded frame → output YUV, with optional transform + text overlay. */
    private fun frameYuv(
        image: android.media.Image,
        t: ClipTransform?,
        texts: List<OverlayText>,
        timelineMs: Long,
        outW: Int,
        outH: Int,
        planar: Boolean,
    ): ByteArray {
        val live = texts.filter { timelineMs in it.startMs until it.endMs }
        if (t == null && live.isEmpty()) return frameToYuv(image, outW, outH, planar)
        val argb = yuv420888ToArgb(image)
        val base = if (t != null) {
            composeFrame(argb, image.width, image.height, t, outW, outH)
        } else {
            scaleArgb(argb, image.width, image.height, outW, outH)
        }
        if (live.isNotEmpty()) drawTexts(base, outW, outH, live, timelineMs)
        return if (planar) Yuv.toI420(base, outW, outH) else Yuv.toNV12(base, outW, outH)
    }

    private fun scaleArgb(pixels: IntArray, w: Int, h: Int, outW: Int, outH: Int): IntArray {
        if (w == outW && h == outH) return pixels
        val out = IntArray(outW * outH)
        for (y in 0 until outH) {
            val sy = (y * h / outH).coerceIn(0, h - 1)
            for (x in 0 until outW) {
                out[y * outW + x] = pixels[sy * w + (x * w / outW).coerceIn(0, w - 1)]
            }
        }
        return out
    }

    /** Draws live texts onto outWxH ARGB pixels (in place). */
    private fun drawTexts(
        base: IntArray,
        outW: Int,
        outH: Int,
        live: List<OverlayText>,
        timelineMs: Long,
    ) {
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(base, 0, outW, 0, 0, outW, outH)
        val cv = android.graphics.Canvas(bmp)
        for (t in live.sortedBy { it.startMs }) drawOneText(cv, t, timelineMs, outW, outH)
        bmp.getPixels(base, 0, outW, 0, 0, outW, outH)
        bmp.recycle()
    }

    private fun drawOneText(
        cv: android.graphics.Canvas,
        t: OverlayText,
        timelineMs: Long,
        outW: Int,
        outH: Int,
    ) {
        val elapsed = timelineMs - t.startMs
        val remain = t.endMs - timelineMs
        var alpha = t.opacity * 255 / 100
        var dy = 0f
        var scale = 1f
        var visible = t.text
        when (t.animIn) {
            "fade" -> if (elapsed < 400) alpha = (alpha * elapsed / 400).toInt()
            "slide" -> if (elapsed < 400) dy = outH * 0.08f * (1 - elapsed / 400f)
            "pop" -> if (elapsed < 300) scale = 0.5f + 0.5f * elapsed / 300f
            "typewriter" -> {
                val span = (t.endMs - t.startMs).coerceIn(1, 3000)
                visible = t.text.take(((t.text.length * elapsed) / span).toInt().coerceIn(0, t.text.length))
            }
        }
        if (t.animOut == "fade" && remain < 400) alpha = (alpha * remain / 400).toInt()
        if (alpha <= 0 || visible.isEmpty()) return
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = t.color.toInt()
            textSize = (outH * t.sizePct / 100f * scale).coerceAtLeast(8f)
            textAlign = when (t.align) {
                "left" -> android.graphics.Paint.Align.LEFT
                "right" -> android.graphics.Paint.Align.RIGHT
                else -> android.graphics.Paint.Align.CENTER
            }
            this.alpha = alpha.coerceIn(0, 255)
            if (t.bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            if (t.shadow) setShadowLayer(6f, 0f, 3f, 0xCC000000.toInt())
        }
        val lines = visible.split("\n")
        val lineH = paint.textSize * 1.25f
        val cx = outW * t.xPct / 100f
        val cy = outH * t.yPct / 100f + dy
        val top = cy - lineH * (lines.size - 1) / 2 - paint.textSize
        cv.save()
        cv.rotate(t.rotation.toFloat(), cx, cy)
        if (t.background) {
            val widest = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
            val left = when (t.align) {
                "left" -> cx
                "right" -> cx - widest
                else -> cx - widest / 2
            }
            val bgAlpha = ((t.bgColor ushr 24) and 0xFF).toInt().coerceIn(0, 255)
            val bg = android.graphics.Paint().apply {
                color = t.bgColor.toInt()
                alpha = (bgAlpha * alpha / 255).coerceIn(0, 255)
            }
            cv.drawRoundRect(
                android.graphics.RectF(left - 20f, top - 14f, left + widest + 20f, top + lineH * lines.size + 14f),
                16f, 16f, bg,
            )
        }
        if (t.strokePx > 0) {
            val stroke = android.graphics.Paint(paint).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = t.strokePx * outH / 720f
                color = t.strokeColor.toInt()
            }
            var baseline = top + paint.textSize
            for (line in lines) {
                cv.drawText(line, cx, baseline, stroke)
                baseline += lineH
            }
        }
        var baseline = top + paint.textSize
        for (line in lines) {
            cv.drawText(line, cx, baseline, paint)
            baseline += lineH
        }
        cv.restore()
    }

    /** BT.601 YUV_420_888 → ARGB (stride-aware). */
    private fun yuv420888ToArgb(image: android.media.Image): IntArray {
        val w = image.width
        val h = image.height
        val y = readPlane(image.planes[0], w, h)
        val srcW = (w / 2).coerceAtLeast(1)
        val srcH = (h / 2).coerceAtLeast(1)
        val u = if (image.planes.size > 1) readPlane(image.planes[1], srcW, srcH) else ByteArray(srcW * srcH) { 128.toByte() }
        val v = if (image.planes.size > 2) readPlane(image.planes[2], srcW, srcH) else ByteArray(srcW * srcH) { 128.toByte() }
        val out = IntArray(w * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val yy = (y[row * w + col].toInt() and 0xFF) - 16
                val uu = (u[(row / 2) * srcW + (col / 2)].toInt() and 0xFF) - 128
                val vv = (v[(row / 2) * srcW + (col / 2)].toInt() and 0xFF) - 128
                val r = (298 * yy + 409 * vv + 128) shr 8
                val g = (298 * yy - 100 * uu - 208 * vv + 128) shr 8
                val b = (298 * yy + 516 * uu + 128) shr 8
                out[row * w + col] = 0xFF000000.toInt() or
                    (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        return out
    }

    /**
     * Composes one transformed frame: crop% → rotate/flip → center-crop fill
     * × scale → offset position → opacity over black.
     */
    private fun composeFrame(
        pixels: IntArray,
        w: Int,
        h: Int,
        t: ClipTransform,
        outW: Int,
        outH: Int,
    ): IntArray {
        val cx = (w * t.cropX / 100).coerceIn(0, w - 1)
        val cy = (h * t.cropY / 100).coerceIn(0, h - 1)
        val cw = (w * t.cropW / 100).coerceIn(1, w - cx)
        val ch = (h * t.cropH / 100).coerceIn(1, h - cy)
        val cropped = IntArray(cw * ch)
        for (row in 0 until ch) {
            pixels.copyInto(cropped, row * cw, (cy + row) * w + cx, (cy + row) * w + cx + cw)
        }
        val (oriented, ow, oh) = rotateFlip(cropped, cw, ch, t.rotation, t.flipH, t.flipV)
        val src = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        src.setPixels(oriented, 0, ow, 0, 0, ow, oh)
        val canvas = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        canvas.eraseColor(0xFF000000.toInt())
        val fill = maxOf(outW.toDouble() / ow, outH.toDouble() / oh)
        val s = fill * t.scale / 100.0
        val dw = (ow * s).toInt().coerceAtLeast(1)
        val dh = (oh * s).toInt().coerceAtLeast(1)
        val dx = (outW - dw) / 2 + t.posX
        val dy = (outH - dh) / 2 + t.posY
        val paint = android.graphics.Paint().apply {
            alpha = (t.opacity * 255 / 100).coerceIn(0, 255)
            isFilterBitmap = true
        }
        val cv = android.graphics.Canvas(canvas)
        cv.drawBitmap(src, null, android.graphics.Rect(dx, dy, dx + dw, dy + dh), paint)
        src.recycle()
        val out = IntArray(outW * outH)
        canvas.getPixels(out, 0, outW, 0, 0, outW, outH)
        canvas.recycle()
        return out
    }

    private fun rotateFlip(
        pixels: IntArray,
        w: Int,
        h: Int,
        rotation: Int,
        flipH: Boolean,
        flipV: Boolean,
    ): Triple<IntArray, Int, Int> {
        val swap = rotation == 90 || rotation == 270
        val ow = if (swap) h else w
        val oh = if (swap) w else h
        val out = IntArray(ow * oh)
        for (y in 0 until oh) {
            for (x in 0 until ow) {
                // Flip lives in output space: un-flip first, then inverse-rotate.
                var sx = x
                var sy = y
                if (flipH) sx = ow - 1 - sx
                if (flipV) sy = oh - 1 - sy
                val src = when (rotation) {
                    90 -> (h - 1 - sx) * w + sy
                    180 -> (h - 1 - sy) * w + (w - 1 - sx)
                    270 -> sx * w + (w - 1 - sy)
                    else -> sy * w + sx
                }
                out[y * ow + x] = pixels[src.coerceIn(0, pixels.size - 1)]
            }
        }
        return Triple(out, ow, oh)
    }

    private fun centerCrop(src: Bitmap, outW: Int, outH: Int): Bitmap {
        val srcAspect = src.width.toDouble() / src.height
        val dstAspect = outW.toDouble() / outH
        val cropW: Int
        val cropH: Int
        if (srcAspect > dstAspect) {
            cropH = src.height
            cropW = (src.height * dstAspect).toInt().coerceIn(1, src.width)
        } else {
            cropW = src.width
            cropH = (src.width / dstAspect).toInt().coerceIn(1, src.height)
        }
        val x = (src.width - cropW) / 2
        val y = (src.height - cropH) / 2
        val cropped = Bitmap.createBitmap(src, x, y, cropW, cropH)
        if (cropped.width == outW && cropped.height == outH) return cropped
        val scaled = Bitmap.createScaledBitmap(cropped, outW, outH, true)
        if (scaled != cropped) cropped.recycle()
        return scaled
    }

    private fun frameToYuv(image: android.media.Image, outW: Int, outH: Int, planar: Boolean): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        // Y-plane carries luma; U/V planes carry subsampled chroma (see finishYuv).
        // For exact colors we read Y + U/V planes directly into I420/NV12.
        val ySize = outW * outH
        if (w == outW && h == outH && rowStride == outW && pixelStride == 1) {
            val y = ByteArray(ySize)
            buffer.get(y)
            return finishYuv(y, image, outW, outH, planar)
        }
        // General path: nearest-neighbor scale of the Y plane, neutral chroma.
        val tmp = ByteArray(buffer.remaining())
        buffer.get(tmp)
        val y = ByteArray(ySize)
        for (dstY in 0 until outH) {
            val srcY = (dstY * h / outH).coerceIn(0, h - 1)
            for (dstX in 0 until outW) {
                val srcX = (dstX * w / outW).coerceIn(0, w - 1)
                y[dstY * outW + dstX] = tmp[srcY * rowStride + srcX * pixelStride]
            }
        }
        return finishYuv(y, null, outW, outH, planar)
    }

    private fun finishYuv(
        y: ByteArray,
        image: android.media.Image?,
        outW: Int,
        outH: Int,
        planar: Boolean,
    ): ByteArray {
        val ySize = outW * outH
        if (planar) {
            val out = ByteArray(ySize + ySize / 2)
            y.copyInto(out, 0, 0, ySize.coerceAtMost(y.size))
            copyChromaPlanar(image, out, outW, outH)
            return out
        }
        val out = ByteArray(ySize + ySize / 2)
        y.copyInto(out, 0, 0, ySize.coerceAtMost(y.size))
        copyChromaSemi(image, out, outW, outH)
        return out
    }

    private fun copyChromaPlanar(image: android.media.Image?, out: ByteArray, outW: Int, outH: Int) {
        val ySize = outW * outH
        if (image == null || image.planes.size < 3) {
            out.fill(128.toByte(), ySize, out.size)
            return
        }
        val srcW = (image.width / 2).coerceAtLeast(1)
        val srcH = (image.height / 2).coerceAtLeast(1)
        val halfW = outW / 2
        val halfH = outH / 2
        val u = readPlane(image.planes[1], srcW, srcH)
        val v = readPlane(image.planes[2], srcW, srcH)
        scalePlane(u, srcW, srcH, out, ySize, halfW, halfH)
        scalePlane(v, srcW, srcH, out, ySize + halfW * halfH, halfW, halfH)
    }

    private fun copyChromaSemi(image: android.media.Image?, out: ByteArray, outW: Int, outH: Int) {
        val ySize = outW * outH
        if (image == null || image.planes.size < 3) {
            out.fill(128.toByte(), ySize, out.size)
            return
        }
        val srcW = (image.width / 2).coerceAtLeast(1)
        val srcH = (image.height / 2).coerceAtLeast(1)
        val halfW = outW / 2
        val halfH = outH / 2
        val u = readPlane(image.planes[1], srcW, srcH)
        val v = readPlane(image.planes[2], srcW, srcH)
        for (r in 0 until halfH) {
            val sr = (r * srcH / halfH).coerceIn(0, srcH - 1)
            for (c in 0 until halfW) {
                val sc = (c * srcW / halfW).coerceIn(0, srcW - 1)
                out[ySize + (r * halfW + c) * 2] = u[sr * srcW + sc]
                out[ySize + (r * halfW + c) * 2 + 1] = v[sr * srcW + sc]
            }
        }
    }

    /** Reads one chroma plane honoring row/pixel stride (codecs often pad rows). */
    private fun readPlane(plane: android.media.Image.Plane, w: Int, h: Int): ByteArray {
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val buf = plane.buffer
        val tmp = ByteArray(buf.remaining())
        buf.get(tmp)
        val out = ByteArray(w * h)
        for (r in 0 until h) {
            for (c in 0 until w) {
                val idx = r * rowStride + c * pixelStride
                out[r * w + c] = if (idx < tmp.size) tmp[idx] else 128.toByte()
            }
        }
        return out
    }

    private fun scalePlane(
        src: ByteArray,
        srcW: Int,
        srcH: Int,
        out: ByteArray,
        offset: Int,
        dstW: Int,
        dstH: Int,
    ) {
        for (r in 0 until dstH) {
            val sr = (r * srcH / dstH).coerceIn(0, srcH - 1)
            for (c in 0 until dstW) {
                val sc = (c * srcW / dstW).coerceIn(0, srcW - 1)
                out[offset + r * dstW + c] = src[sr * srcW + sc]
            }
        }
    }

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
                    encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
                }
                fed = true
            }
        }
    }

    private fun pickEncoderColorFormat(): Int? {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val formats = caps.colorFormats.toSet()
            val planar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            val semi = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            return when {
                planar in formats -> planar
                semi in formats -> semi
                else -> null
            }
        } catch (_: Exception) {
            return null
        } finally {
            codec.release()
        }
    }

    // ---- audio mix + AAC ----

    private fun mixAndEncodeAudio(plan: RenderPlan, dst: String): Outcome<Unit> {
        val totalMs = plan.timeline.durationMs
        if (totalMs > 180_000) {
            return Outcome.Failure(AppError("RENDER_AUDIO", "เสียงยาวเกิน 3 นาที (ขีดจำกัด v0)"))
        }
        val rate = 44_100
        val frames = ((totalMs * rate) / 1000).toInt().coerceAtLeast(rate)
        val mix = FloatArray(frames * 2)
        for (seg in plan.audioClips) {
            when (val decoded = decodeAudio(seg.file.path)) {
                is Outcome.Failure -> return Outcome.Failure(
                    AppError("RENDER_AUDIO", "ถอดเสียง ${seg.file.name} ไม่ได้: ${decoded.error.message}"),
                )
                is Outcome.Success -> {
                    val stereo = toStereo44100(decoded.value, rate)
                    val startFrame = ((seg.clip.startMs * rate) / 1000).toInt().coerceIn(0, stereo.frames)
                    val endFrame = ((seg.clip.endMs * rate) / 1000).toInt().coerceIn(startFrame, stereo.frames)
                    val atFrame = ((seg.clip.atMs * rate) / 1000).toInt()
                    val gain = seg.clip.volume / 100.0f
                    var s = startFrame
                    var d = atFrame
                    while (s < endFrame && d < frames) {
                        mix[d * 2] += stereo.samples[s * 2] * gain
                        mix[d * 2 + 1] += stereo.samples[s * 2 + 1] * gain
                        s += 1
                        d += 1
                    }
                }
            }
        }
        return encodeAac(mix, rate, dst)
    }

    private fun toStereo44100(pcm: PcmAudio, rate: Int): PcmAudio {
        val stereo = if (pcm.channels == 2) {
            pcm.samples
        } else {
            FloatArray(pcm.frames * 2) { i -> pcm.samples[i / 2] }
        }
        if (pcm.sampleRate == rate) return PcmAudio(rate, 2, stereo)
        val ratio = pcm.sampleRate.toDouble() / rate
        val outFrames = (pcm.frames / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outFrames * 2)
        for (i in 0 until outFrames) {
            val pos = i * ratio
            val a = pos.toInt().coerceIn(0, pcm.frames - 1)
            val b = (a + 1).coerceIn(0, pcm.frames - 1)
            val f = (pos - a).toFloat()
            out[i * 2] = stereo[a * 2] * (1 - f) + stereo[b * 2] * f
            out[i * 2 + 1] = stereo[a * 2 + 1] * (1 - f) + stereo[b * 2 + 1] * f
        }
        return PcmAudio(rate, 2, out)
    }

    private fun encodeAac(mix: FloatArray, rate: Int, dst: String): Outcome<Unit> {
        var muxer: MediaMuxer? = null
        var muxStarted = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                val chunkFrames = 2048
                val totalFrames = mix.size / 2
                var frame = 0
                var fedEos = false
                while (!fedEos) {
                    val inIndex = encoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = encoder.getInputBuffer(inIndex)!!
                        buf.clear()
                        val take = (totalFrames - frame).coerceAtMost(chunkFrames)
                        if (take <= 0) {
                            val pts = frame * 1_000_000L / rate
                            encoder.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fedEos = true
                        } else {
                            for (i in 0 until take) {
                                buf.putShort((mix[(frame + i) * 2].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                                buf.putShort((mix[(frame + i) * 2 + 1].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                            }
                            val pts = frame * 1_000_000L / rate
                            encoder.queueInputBuffer(inIndex, 0, take * 4, pts, 0)
                            frame += take
                        }
                    }
                }
                muxer = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var trackIndex = -1
                val info = android.media.MediaCodec.BufferInfo()
                var sawEos = false
                while (!sawEos) {
                    val outIndex = encoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxStarted = true
                        }
                        outIndex >= 0 -> {
                            val encoded = encoder.getOutputBuffer(outIndex)
                            if (info.size > 0 && muxStarted && encoded != null &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                muxer.writeSampleData(trackIndex, encoded, info)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEos = true
                            encoder.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
            } finally {
                try {
                    encoder.stop()
                } catch (_: Exception) {
                }
                encoder.release()
            }
            if (!muxStarted) return Outcome.Failure(AppError("RENDER_AUDIO", "encoder เสียงไม่ให้ข้อมูลออกมา"))
            return Outcome.Success(Unit)
        } catch (e: Exception) {
            return Outcome.Failure(AppError("RENDER_AUDIO", e.message ?: "encode เสียงล้มเหลว"))
        } finally {
            try {
                if (muxStarted) muxer?.stop()
            } catch (_: Exception) {
            }
            muxer?.release()
        }
    }

    private fun mergeAv(videoPath: String, audioPath: String, dst: String) {
        val muxer = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vExt = MediaExtractor()
            vExt.setDataSource(videoPath)
            val vTrack = (0 until vExt.trackCount).first { i ->
                (vExt.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")
            }
            val aExt = MediaExtractor()
            aExt.setDataSource(audioPath)
            val aTrack = (0 until aExt.trackCount).first { i ->
                (aExt.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")
            }
            vExt.selectTrack(vTrack)
            aExt.selectTrack(aTrack)
            val vOut = muxer.addTrack(vExt.getTrackFormat(vTrack))
            val aOut = muxer.addTrack(aExt.getTrackFormat(aTrack))
            muxer.start()
            copyTrack(vExt, vTrack, muxer, vOut)
            copyTrack(aExt, aTrack, muxer, aOut)
            vExt.release()
            aExt.release()
        } finally {
            try {
                muxer.stop()
            } catch (_: Exception) {
            }
            muxer.release()
        }
    }

    private fun copyTrack(ext: MediaExtractor, track: Int, muxer: MediaMuxer, outTrack: Int) {
        val buf = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()
        while (true) {
            val size = ext.readSampleData(buf, 0)
            if (size < 0) break
            info.set(0, size, ext.sampleTime, ext.sampleFlags)
            muxer.writeSampleData(outTrack, buf, info)
            ext.advance()
        }
    }

    // ---- previews + export ----

    private suspend fun makePreviews(outPath: String, totalMs: Long): List<String> {
        if (totalMs <= 0) return emptyList()
        val paths = mutableListOf<String>()
        for (f in listOf(0.25, 0.5, 0.75)) {
            val dst = File(outDir, "${File(outPath).nameWithoutExtension}-pv${(f * 100).toInt()}.jpg").path
            when (video.thumbnail(outPath, dst, (totalMs * f).toLong())) {
                is Outcome.Success -> paths += dst
                is Outcome.Failure -> Unit
            }
        }
        return paths
    }

    private fun exportFile(job: RenderJob): Outcome<RenderJob> {
        return try {
            val src = File(job.outputPath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "${job.projectId}-${job.id}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Download/Aicodemax")
                }
                val resolver = appContext.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return Outcome.Failure(AppError("RENDER_EXPORT", "สร้างไฟล์ปลายทางไม่ได้"))
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: return Outcome.Failure(AppError("RENDER_EXPORT", "เขียนไฟล์ปลายทางไม่ได้"))
                Outcome.Success(job.copy(exportedUri = uri.toString()))
            } else {
                val dst = File(appContext.getExternalFilesDir(null), "exports/${src.name}")
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                Outcome.Success(job.copy(exportedUri = dst.path))
            }
        } catch (e: Exception) {
            Outcome.Failure(AppError("RENDER_EXPORT", e.message ?: "เอ็กซ์พอร์ตล้มเหลว"))
        }
    }
}
