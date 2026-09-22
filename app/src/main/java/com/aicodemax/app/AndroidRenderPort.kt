package com.aicodemax.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import kotlin.math.pow
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
import com.aicodemax.data.media.ClipSpeed
import com.aicodemax.data.media.ClipKeyframes
import com.aicodemax.data.media.ClipTransition
import com.aicodemax.data.media.ClipFx
import com.aicodemax.data.media.ClipColor
import com.aicodemax.data.media.ClipMask
import com.aicodemax.data.media.ClipChroma
import com.aicodemax.data.media.ClipBackground
import com.aicodemax.tools.image.RenderScopes
import com.aicodemax.tools.image.FrameScopes
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
                var exposure: FrameScopes? = null
                if (plan.fast) {
                    val seg = plan.segments[0]
                    when (val trimmed = fastTrim(seg, outPath, plan)) {
                        is Outcome.Failure -> return@withContext fail(current, trimmed.error.message)
                        is Outcome.Success -> Unit
                    }
                    progress(70)
                } else {
                    val scopes = RenderScopes()
                    transcode(plan, outPath, job.preset, progress, notes, scopes).fold(
                        onSuccess = { Unit },
                        onFailure = { return@withContext fail(current, it.message) },
                    )
                    if (!scopes.isEmpty()) {
                        exposure = scopes.report()
                        notes += "สโคป: ${exposure.summary()}"
                    }
                }
                val report = Qc.check(
                    plan.timeline.durationMs, plan.wantAudio, outPath,
                    job.preset.maxHeight, video, exposure,
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
        val trackId: String = "",

        val width: Int = -1,
        val height: Int = -1,
        val transform: ClipTransform? = null,
        val speed: ClipSpeed? = null,
    )

    private data class RenderPlan(
        val timeline: Timeline,
        val segments: List<Segment>,
        val audioClips: List<Segment>,
        val wantAudio: Boolean,
        val fast: Boolean,
        val texts: List<OverlayText> = emptyList(),
        val bgFile: File? = null,
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
                    MediaKind.VIDEO, MediaKind.IMAGE -> videoSegs += Segment(clip, file, track.kind, track.id, transform = clip.transform, speed = clip.speed?.takeUnless { it.isIdentity })
                    MediaKind.AUDIO -> if (job.preset.includeAudio) {
                        audioSegs += Segment(clip, file, track.kind, track.id, speed = clip.speed?.takeUnless { it.isIdentity })
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
        val singleKeys = single?.clip?.keyframes
        val singleFx = single?.clip?.fx
        val singleColor = single?.clip?.color
        val singleMask = single?.clip?.mask
        val bg = timeline.background
        val bgAsset = bg?.takeUnless { it.isIdentity }?.takeIf { it.mode == "image" }?.assetId
        val bgFile = bgAsset?.let { id ->
            val asset = byId[id]
            asset?.let { File(File(mediaRoot, "${project.id}/assets"), it.fileName).takeIf { f -> f.isFile } }
        }
        val fast = single != null && single.kind == MediaKind.VIDEO &&
            audioSegs.isEmpty() && single.clip.volume == 100 &&
            single.clip.atMs == 0L &&
            (single.transform == null || single.transform.isIdentity) &&
            single.height in 1..job.preset.maxHeight &&
            timeline.texts.isEmpty() &&
            (single.speed == null) &&
            (singleKeys == null || singleKeys.isEmpty) &&
            single?.clip?.transitionIn == null &&
            single?.clip?.transitionOut == null &&
            (singleFx == null || singleFx.isIdentity) &&
            (singleColor == null || singleColor.isIdentity) &&
            (singleMask == null || singleMask.isIdentity) &&
            single?.clip?.chroma == null &&
            (bg == null || bg.isIdentity)
        return RenderPlan(
            timeline = timeline,
            segments = probed.sortedBy { it.clip.atMs },
            audioClips = audioSegs,
            wantAudio = audioSegs.isNotEmpty(),
            fast = fast,
            texts = timeline.texts,
            bgFile = bgFile,
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
        scopes: RenderScopes,
    ): Outcome<Unit> = withContext(Dispatchers.IO) {
        try {
            val firstVideo = plan.segments.firstOrNull { it.kind == MediaKind.VIDEO && it.height > 0 }
            val outW: Int
            val outH: Int
            // CP-88 §33: explicit canvas aspect wins over source-derived size.
            val canvasParts = plan.timeline.canvas.split(":").mapNotNull { it.toIntOrNull() }
            if (canvasParts.size == 2 && canvasParts[0] > 0 && canvasParts[1] > 0) {
                val longSide = preset.maxHeight and 1.inv()
                if (canvasParts[0] >= canvasParts[1]) {
                    outW = longSide * canvasParts[0] / canvasParts[1] and 1.inv()
                    outH = longSide
                } else {
                    outH = longSide * canvasParts[1] / canvasParts[0] and 1.inv()
                    outW = longSide
                }
            } else if (firstVideo != null) {
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
                encodeVideo(plan, videoTemp.path, outW, outH, preset, colorFormat, planar, progress, scopes).fold(
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
        scopes: RenderScopes,
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
                val bg = prepareBg(plan, outW, outH)
                val tails = mutableMapOf<String, IntArray>()
                val needTail = mutableSetOf<String>()
                plan.segments.groupBy { it.trackId }.values.forEach { group ->
                    val sorted = group.sortedBy { it.clip.atMs }
                    sorted.forEachIndexed { i, s ->
                        val k = s.clip.transitionIn?.kind
                        if (i > 0 && k != null && k != "cut" && k != "fade") needTail += sorted[i - 1].clip.id
                    }
                }
                plan.segments.forEachIndexed { index, seg ->
                    val prev = tails[seg.trackId]
                    val wantTail = seg.clip.id in needTail
                    val last = if (seg.kind == MediaKind.IMAGE) {
                        feedStill(seg, outW, outH, planar, plan.texts, prev, wantTail, scopes, bg, feed)
                    } else {
                        decodeSegment(seg, outW, outH, planar, seg.transform, plan.texts, seg.speed, prev, wantTail, scopes, bg, feed)
                    }
                    if (last != null) tails[seg.trackId] = last
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
        speed: ClipSpeed?,
        prevTail: IntArray?,
        wantTail: Boolean,
        scopes: RenderScopes?,
        bg: BgSource?,
        feed: (ByteArray) -> Unit,
    ): IntArray? {
        val active = transform?.takeUnless { it.isIdentity }
        if (speed?.reverse == true) {
            return decodeReversed(seg, outW, outH, planar, active, texts, speed, prevTail, wantTail, scopes, bg, feed)
        }
        val srcLen = seg.clip.endMs - seg.clip.startMs
        val outLen = seg.clip.outputDurationMs()
        val totalOut = ((outLen * 30) / 1000).toInt().coerceAtLeast(1)
        var outIndex = 0
        var lastYuv: ByteArray? = null
        fun needed(j: Int): Long =
            speed?.outputToSource(j * 1000L / 30, srcLen) ?: (j * 1000L / 30)
        val keys = seg.clip.keyframes?.takeUnless { it.isEmpty }
        var lastArgb: IntArray? = null
        val trIn = seg.clip.transitionIn
        val trOut = seg.clip.transitionOut
        val fx = seg.clip.fx?.takeUnless { it.isIdentity }
        val cc = seg.clip.color?.takeUnless { it.isIdentity }
        val lut = seg.clip.lut
        val mask = seg.clip.mask?.takeUnless { it.isIdentity }
        val chroma = seg.clip.chroma
        val plainOk = active == null && keys == null && trIn == null && trOut == null && fx == null && cc == null && mask == null && chroma == null
        fun emit(image: android.media.Image, j: Int) {
            val offMs = j * 1000L / 30
            val timelineMs = seg.clip.atMs + offMs
            val live = texts.filter { timelineMs in it.startMs until it.endMs }
            val yuv = if (plainOk && !wantTail && live.isEmpty()) {
                frameToYuv(image, outW, outH, planar)
            } else {
                val argb = yuv420888ToArgb(image)
                var frame = if (keys != null) {
                    composeLook(argb, image.width, image.height, withMotion(frameLook(active, keys, offMs), seg.clip.motion, offMs.toFloat() / outLen.coerceAtLeast(1), outW, outH), outW, outH)
                } else if (active != null) {
                    composeFrame(argb, image.width, image.height, active, outW, outH)
                } else {
                    scaleArgb(argb, image.width, image.height, outW, outH)
                }
                frame = applyFx(frame, outW, outH, fx, timelineMs)
                frame = applyLut(applyColor(frame, cc), lut)
                if (mask != null || chroma != null) {
                    frame = applyMask(frame, outW, outH, mask)
                    frame = applyChroma(frame, chroma)
                    frame = compositeOver(frame, bgPixelsFor(bg, frame, outW, outH))
                }
                frame = applyTransitionIn(frame, outW, outH, trIn, prevTail, offMs)
                frame = applyTransitionOut(frame, trOut, offMs, outLen)
                if (j % 30 == 0) scopes?.add(frame)
                lastArgb = frame
                if (live.isNotEmpty()) drawTexts(frame, outW, outH, live, timelineMs)
                if (planar) Yuv.toI420(frame, outW, outH) else Yuv.toNV12(frame, outW, outH)
            }
            lastYuv = yuv
            feed(yuv)
        }
        val startUs = seg.clip.startMs * 1000
        val endUs = seg.clip.endMs * 1000
        val readerW = if (seg.width > 0) seg.width else outW
        val readerH = if (seg.height > 0) seg.height else outH
        val reader = ImageReader.newInstance(readerW, readerH, android.graphics.ImageFormat.YUV_420_888, 2)
        val ext = MediaExtractor()
        ext.setDataSource(seg.file.path)
        val trackIndex = (0 until ext.trackCount).firstOrNull { i ->
            (ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")
        } ?: run { ext.release(); reader.close(); return null }
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
                val outIdx = decoder.dequeueOutputBuffer(decInfo, 10_000)
                when {
                    outIdx >= 0 -> {
                        if (decInfo.size > 0 && decInfo.presentationTimeUs >= startUs) {
                            decoder.releaseOutputBuffer(outIdx, true)
                            val image = acquireImage(reader)
                            if (image != null) {
                                try {
                                    // Nearest-previous sampling: emit for every output
                                    // frame whose source time this decoded frame covers.
                                    val rel = decInfo.presentationTimeUs / 1000 - seg.clip.startMs
                                    while (outIndex < totalOut && needed(outIndex) <= rel) {
                                        emit(image, outIndex)
                                        outIndex += 1
                                    }
                                } finally {
                                    image.close()
                                }
                            }
                        } else {
                            decoder.releaseOutputBuffer(outIdx, false)
                        }
                        if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }
            // Pad with the last frame if the source ran short (§13: no interpolation).
            val tail = lastYuv
            if (tail != null) {
                while (outIndex < totalOut) {
                    feed(tail)
                    outIndex += 1
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
        return lastArgb
    }

    /**
     * CP-75 reverse playback: decodes the range once, caches selected frames
     * as JPEGs (bounded temp), then feeds them back-to-front. Capped at 90
     * output frames (~3s) — honest v0 limit, longer clips fail with guidance.
     */
    private fun decodeReversed(
        seg: Segment,
        outW: Int,
        outH: Int,
        planar: Boolean,
        active: ClipTransform?,
        texts: List<OverlayText>,
        speed: ClipSpeed,
        prevTail: IntArray?,
        wantTail: Boolean,
        scopes: RenderScopes?,
        bg: BgSource?,
        feed: (ByteArray) -> Unit,
    ): IntArray? {
        val srcLen = seg.clip.endMs - seg.clip.startMs
        val outLen = seg.clip.outputDurationMs()
        val totalOut = ((outLen * 30) / 1000).toInt().coerceAtLeast(1)
        if (totalOut > 90) {
            throw IllegalStateException("ย้อนกลับได้ครั้งละไม่เกิน 3 วินาที (v0) — ตัดช่วงให้สั้นลงก่อนครับ")
        }
        // Source time each output frame shows (mirrored through the rate map).
        fun srcAt(j: Int): Long =
            (srcLen - speed.outputToSource(j * 1000L / 30, srcLen)).coerceIn(0, srcLen)
        val cacheDir = File(outDir, "rev-${seg.clip.id}-${System.currentTimeMillis()}").also { it.mkdirs() }
        try {
            val startUs = seg.clip.startMs * 1000
            val endUs = seg.clip.endMs * 1000
            val reader = ImageReader.newInstance(
                if (seg.width > 0) seg.width else outW,
                if (seg.height > 0) seg.height else outH,
                android.graphics.ImageFormat.YUV_420_888, 2,
            )
            val ext = MediaExtractor()
            ext.setDataSource(seg.file.path)
            val trackIndex = (0 until ext.trackCount).firstOrNull { i ->
                (ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")
            } ?: run { ext.release(); reader.close(); return null }
            ext.selectTrack(trackIndex)
            ext.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(
                ext.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME)!!,
            )
            val rels = mutableListOf<Long>()
            val keys = seg.clip.keyframes?.takeUnless { it.isEmpty }
            try {
                decoder.configure(ext.getTrackFormat(trackIndex), reader.surface, null, 0)
                decoder.start()
                val decInfo = android.media.MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                var stored = 0
                while (!sawOutputEos && stored < 400) {
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
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 10_000)
                    when {
                        outIdx >= 0 -> {
                            if (decInfo.size > 0 && decInfo.presentationTimeUs >= startUs) {
                                decoder.releaseOutputBuffer(outIdx, true)
                                val image = acquireImage(reader)
                                if (image != null) {
                                    try {
                                        val argb = yuv420888ToArgb(image)
                                        val iw = image.width
                                        val ih = image.height
                                        val base: IntArray
                                        val bw: Int
                                        val bh: Int
                                        if (keys != null) {
                                            base = argb; bw = iw; bh = ih
                                        } else if (active != null) {
                                            base = composeFrame(argb, iw, ih, active, outW, outH); bw = outW; bh = outH
                                        } else {
                                            base = scaleArgb(argb, iw, ih, outW, outH); bw = outW; bh = outH
                                        }
                                        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                                        bmp.setPixels(base, 0, bw, 0, 0, bw, bh)
                                        File(cacheDir, "f$stored.jpg").outputStream().use { out ->
                                            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                        }
                                        bmp.recycle()
                                        rels += decInfo.presentationTimeUs / 1000 - seg.clip.startMs
                                        stored += 1
                                    } finally {
                                        image.close()
                                    }
                                }
                            } else {
                                decoder.releaseOutputBuffer(outIdx, false)
                            }
                            if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
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
            if (rels.isEmpty()) throw IllegalStateException("ถอดเฟรมช่วงย้อนกลับไม่ได้")
            var lastArgb: IntArray? = null
            val fx = seg.clip.fx?.takeUnless { it.isIdentity }
            val cc = seg.clip.color?.takeUnless { it.isIdentity }
        val lut = seg.clip.lut
            val mask = seg.clip.mask?.takeUnless { it.isIdentity }
            val chroma = seg.clip.chroma
            for (j in 0 until totalOut) {
                val want = srcAt(j)
                var best = 0
                for (i in rels.indices) {
                    if (kotlin.math.abs(rels[i] - want) < kotlin.math.abs(rels[best] - want)) best = i
                }
                val bmp = BitmapFactory.decodeFile(File(cacheDir, "f$best.jpg").path)
                    ?: throw IllegalStateException("อ่านเฟรมแคชไม่ได้")
                val frame = IntArray(outW * outH)
                if (keys != null) {
                    val raw = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(raw, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val composed = composeLook(raw, bmp.width, bmp.height, withMotion(frameLook(active, keys, j * 1000L / 30), seg.clip.motion, j.toFloat() / totalOut.coerceAtLeast(1), outW, outH), outW, outH)
                    composed.copyInto(frame)
                } else if (bmp.width == outW && bmp.height == outH) {
                    bmp.getPixels(frame, 0, outW, 0, 0, outW, outH)
                } else {
                    val scaled = Bitmap.createScaledBitmap(bmp, outW, outH, true)
                    scaled.getPixels(frame, 0, outW, 0, 0, outW, outH)
                    if (scaled != bmp) scaled.recycle()
                }
                bmp.recycle()
                val offMs = j * 1000L / 30
                val timelineMs = seg.clip.atMs + offMs
                var done = applyFx(frame, outW, outH, fx, timelineMs)
                done = applyLut(applyColor(done, cc), lut)
                if (mask != null || chroma != null) {
                    done = applyMask(done, outW, outH, mask)
                    done = applyChroma(done, chroma)
                    done = compositeOver(done, bgPixelsFor(bg, done, outW, outH))
                }
                done = applyTransitionIn(done, outW, outH, seg.clip.transitionIn, prevTail, offMs)
                done = applyTransitionOut(done, seg.clip.transitionOut, offMs, outLen)
                if (j % 30 == 0) scopes?.add(done)
                lastArgb = done
                val live = texts.filter { timelineMs in it.startMs until it.endMs }
                if (live.isNotEmpty()) drawTexts(done, outW, outH, live, timelineMs)
                feed(if (planar) Yuv.toI420(done, outW, outH) else Yuv.toNV12(done, outW, outH))
            }
            if (!wantTail && fx == null && cc == null && mask == null && chroma == null && seg.clip.transitionIn == null && seg.clip.transitionOut == null) {
                lastArgb = null
            }
            return lastArgb
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun feedStill(
        seg: Segment,
        outW: Int,
        outH: Int,
        planar: Boolean,
        texts: List<OverlayText>,
        prevTail: IntArray?,
        wantTail: Boolean,
        scopes: RenderScopes?,
        bg: BgSource?,
        feed: (ByteArray) -> Unit,
    ): IntArray? {
        val raw = BitmapFactory.decodeFile(seg.file.path) ?: return null
        val active = seg.transform?.takeUnless { it.isIdentity }
        val keys = seg.clip.keyframes?.takeUnless { it.isEmpty }
        val stillEnd = seg.clip.atMs + seg.clip.outputDurationMs()
        val live = texts.filter { it.startMs < stillEnd && it.endMs > seg.clip.atMs }
        val fxStill = seg.clip.fx?.takeUnless { it.isIdentity }
        val ccStill = seg.clip.color?.takeUnless { it.isIdentity }
        val cutStill = seg.clip.mask?.takeUnless { it.isIdentity } != null || seg.clip.chroma != null || seg.clip.lut != null || seg.clip.motion != null
        if (active != null || live.isNotEmpty() || keys != null || wantTail ||
            seg.clip.transitionIn != null || seg.clip.transitionOut != null || fxStill != null || ccStill != null || cutStill
        ) {
            return feedStillComposed(raw, active, live, seg, outW, outH, planar, prevTail, scopes, bg, feed)
        }
        val scaled = centerCrop(raw, outW, outH)
        val pixels = IntArray(outW * outH)
        scaled.getPixels(pixels, 0, outW, 0, 0, outW, outH)
        scaled.recycle()
        val yuv = if (planar) Yuv.toI420(pixels, outW, outH) else Yuv.toNV12(pixels, outW, outH)
        val frames = ((seg.clip.outputDurationMs() * 30) / 1000).toInt().coerceIn(1, 30 * 600)
        scopes?.add(pixels)
        repeat(frames) { feed(yuv) }
        return null
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
        prevTail: IntArray?,
        scopes: RenderScopes?,
        bg: BgSource?,
        feed: (ByteArray) -> Unit,
    ): IntArray? {
        val pixels = IntArray(raw.width * raw.height)
        raw.getPixels(pixels, 0, raw.width, 0, 0, raw.width, raw.height)
        val outLen = seg.clip.outputDurationMs()
        val frames = ((outLen * 30) / 1000).toInt().coerceIn(1, 30 * 600)
        val keys = seg.clip.keyframes?.takeUnless { it.isEmpty }
        val trIn = seg.clip.transitionIn
        val trOut = seg.clip.transitionOut
        val fx = seg.clip.fx?.takeUnless { it.isIdentity }
        val cc = seg.clip.color?.takeUnless { it.isIdentity }
        val lut = seg.clip.lut
        val mask = seg.clip.mask?.takeUnless { it.isIdentity }
        val chroma = seg.clip.chroma
        var lastArgb: IntArray? = null
        val motion = seg.clip.motion?.takeUnless { it.isIdentity }
        if (keys != null || motion != null) {
            repeat(frames) { i ->
                val offMs = i * 1000L / 30
                val timelineMs = seg.clip.atMs + offMs
                var frame = composeLook(pixels, raw.width, raw.height, withMotion(frameLook(t, keys, offMs), motion, offMs.toFloat() / outLen.coerceAtLeast(1), outW, outH), outW, outH)
                frame = applyFx(frame, outW, outH, fx, timelineMs)
                frame = applyLut(applyColor(frame, cc), lut)
                if (mask != null || chroma != null) {
                    frame = applyMask(frame, outW, outH, mask)
                    frame = applyChroma(frame, chroma)
                    frame = compositeOver(frame, bgPixelsFor(bg, frame, outW, outH))
                }
                frame = applyTransitionIn(frame, outW, outH, trIn, prevTail, offMs)
                frame = applyTransitionOut(frame, trOut, offMs, outLen)
                if (i % 30 == 0) scopes?.add(frame)
                lastArgb = frame
                drawTexts(frame, outW, outH, live.filter { timelineMs in it.startMs until it.endMs }, timelineMs)
                feed(if (planar) Yuv.toI420(frame, outW, outH) else Yuv.toNV12(frame, outW, outH))
            }
            return lastArgb
        }
        var base = if (t != null) {
            composeFrame(pixels, raw.width, raw.height, t, outW, outH)
        } else {
            scaleArgb(centerCropPixels(pixels, raw.width, raw.height, outW, outH), outW, outH, outW, outH)
        }
        base = applyFx(base, outW, outH, fx, seg.clip.atMs)
        base = applyLut(applyColor(base, cc), lut)
        if (mask != null || chroma != null) {
            base = applyMask(base, outW, outH, mask)
            base = applyChroma(base, chroma)
            base = compositeOver(base, bgPixelsFor(bg, base, outW, outH))
        }
        scopes?.add(base)
        lastArgb = base
        if (live.isEmpty() && trIn == null && trOut == null) {
            val yuv = if (planar) Yuv.toI420(base, outW, outH) else Yuv.toNV12(base, outW, outH)
            repeat(frames) { feed(yuv) }
            return lastArgb
        }
        repeat(frames) { i ->
            val offMs = i * 1000L / 30
            val timelineMs = seg.clip.atMs + offMs
            var frame = base.copyOf()
            frame = applyTransitionIn(frame, outW, outH, trIn, prevTail, offMs)
            frame = applyTransitionOut(frame, trOut, offMs, outLen)
            lastArgb = frame
            drawTexts(frame, outW, outH, live.filter { timelineMs in it.startMs until it.endMs }, timelineMs)
            feed(if (planar) Yuv.toI420(frame, outW, outH) else Yuv.toNV12(frame, outW, outH))
        }
        return lastArgb
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
        val follow = t.follow?.takeUnless { it.isEmpty }?.offsetAt(timelineMs) ?: (0f to 0f)
        val cx = outW * (t.xPct / 100f + follow.first / 100f)
        val cy = outH * (t.yPct / 100f + follow.second / 100f) + dy
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

    // ---- CP-79 mask + chroma + background (§17/§18/§19) ----

    private data class BgSource(
        val mode: String,
        val solid: IntArray?,
        val image: IntArray?,
        val blurR: Int,
    )

    private fun prepareBg(plan: RenderPlan, outW: Int, outH: Int): BgSource? {
        val b = plan.timeline.background ?: return null
        if (b.isIdentity) return null
        return when (b.mode) {
            "color" -> {
                val rgb = b.color.toIntOrNull(16) ?: 0
                BgSource("color", IntArray(outW * outH) { 0xFF000000.toInt() or rgb }, null, 0)
            }
            "image" -> {
                val file = plan.bgFile ?: return null
                val bmp = try {
                    android.graphics.BitmapFactory.decodeFile(file.path)
                } catch (_: Exception) {
                    null
                } ?: return null
                val bw = bmp.width.coerceAtLeast(1)
                val bh = bmp.height.coerceAtLeast(1)
                val pixels = IntArray(bw * bh)
                bmp.getPixels(pixels, 0, bw, 0, 0, bw, bh)
                bmp.recycle()
                val fitted = scaleArgb(centerCropPixels(pixels, bw, bh, outW, outH), outW, outH, outW, outH)
                BgSource("image", null, fitted, 0)
            }
            "blur" -> BgSource("blur", null, null, b.blur.coerceIn(1, 10))
            else -> null
        }
    }

    private fun bgPixelsFor(bg: BgSource?, frame: IntArray, w: Int, h: Int): IntArray? {
        if (bg == null) return null
        return when (bg.mode) {
            "color" -> bg.solid
            "image" -> bg.image
            "blur" -> blurFill(frame, w, h, bg.blurR)
            else -> null
        }
    }

    /** Classic blurred-fill background: downscale → blur → upscale. */
    private fun blurFill(frame: IntArray, w: Int, h: Int, r: Int): IntArray {
        val sw = 64.coerceAtMost(w)
        val sh = (64f * h / w).toInt().coerceIn(1, h)
        val small = IntArray(sw * sh)
        for (y in 0 until sh) {
            val sy = (y * h / sh).coerceIn(0, h - 1)
            for (x in 0 until sw) {
                small[y * sw + x] = frame[sy * w + (x * w / sw).coerceIn(0, w - 1)]
            }
        }
        return scaleArgb(boxBlur(small, sw, sh, r.coerceIn(1, 10)), sw, sh, w, h)
    }

    /** Shape mask → alpha channel (mutates [px]). */
    private fun applyMask(px: IntArray, w: Int, h: Int, m: ClipMask?): IntArray {
        if (m == null || m.isIdentity) return px
        val x0 = w * m.x / 100f
        val y0 = h * m.y / 100f
        val mw = (w * m.w / 100f).coerceAtLeast(1f)
        val mh = (h * m.h / 100f).coerceAtLeast(1f)
        val featherPx = (m.feather / 100f * minOf(mw, mh) / 2f).coerceAtLeast(0f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val insidePx = if (m.shape == "ellipse") {
                    val nx = (x + 0.5f - (x0 + mw / 2f)) / (mw / 2f)
                    val ny = (y + 0.5f - (y0 + mh / 2f)) / (mh / 2f)
                    (1f - kotlin.math.sqrt(nx * nx + ny * ny)) * minOf(mw, mh) / 2f
                } else {
                    val dx = minOf(x + 0.5f - x0, x0 + mw - (x + 0.5f))
                    val dy = minOf(y + 0.5f - y0, y0 + mh - (y + 0.5f))
                    minOf(dx, dy)
                }
                var a = if (featherPx > 0f) {
                    (insidePx / featherPx * 0.5f + 0.5f).coerceIn(0f, 1f)
                } else if (insidePx > 0f) {
                    1f
                } else {
                    0f
                }
                if (m.invert) a = 1f - a
                val i = y * w + x
                px[i] = (px[i] and 0x00FFFFFF) or (((a * 255).toInt().coerceIn(0, 255)) shl 24)
            }
        }
        return px
    }

    /** Chroma key → alpha channel + despill (mutates [px]). */
    private fun applyChroma(px: IntArray, ch: ClipChroma?): IntArray {
        if (ch == null) return px
        val target = hslToRgb(ch.hue / 360f, 1f, 0.5f)
        val tSum = target[0] + target[1] + target[2] + 1e-6f
        val tr = target[0] / tSum
        val tg = target[1] / tSum
        val tb = target[2] / tSum
        val tolDist = ch.tolerance / 100f * 0.5f
        val softDist = 0.02f + ch.softness / 100f * 0.2f
        val despillF = ch.despill / 100f
        val dom = when (maxOf(target[0], target[1], target[2])) {
            target[0] -> 0
            target[1] -> 1
            else -> 2
        }
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val sum = r + g + b + 1e-6f
            val dr = r / sum - tr
            val dg = g / sum - tg
            val db = b / sum - tb
            val d = kotlin.math.sqrt(dr * dr + dg * dg + db * db)
            val key = 1f - ((d - tolDist) / softDist).coerceIn(0f, 1f)
            val oldA = ((c ushr 24) and 0xFF) / 255f
            val newA = (oldA * (1f - key)).coerceIn(0f, 1f)
            var rr = r
            var gg = g
            var bb = b
            if (despillF > 0f && key < 1f) {
                val others = when (dom) {
                    0 -> maxOf(gg, bb)
                    1 -> maxOf(rr, bb)
                    else -> maxOf(rr, gg)
                }
                when (dom) {
                    0 -> rr = minOf(rr, others + (1f - despillF) * (rr - others).coerceAtLeast(0f))
                    1 -> gg = minOf(gg, others + (1f - despillF) * (gg - others).coerceAtLeast(0f))
                    else -> bb = minOf(bb, others + (1f - despillF) * (bb - others).coerceAtLeast(0f))
                }
            }
            px[i] = (((newA * 255).toInt().coerceIn(0, 255)) shl 24) or
                (((rr * 255f).toInt().coerceIn(0, 255)) shl 16) or
                (((gg * 255f).toInt().coerceIn(0, 255)) shl 8) or
                (bb * 255f).toInt().coerceIn(0, 255)
        }
        return px
    }

    /** Flattens alpha over [bg] (or black when null). Output is opaque. */
    private fun compositeOver(px: IntArray, bg: IntArray?): IntArray {
        for (i in px.indices) {
            val c = px[i]
            val a = ((c ushr 24) and 0xFF) / 255f
            if (a >= 1f) {
                px[i] = c or 0xFF000000.toInt()
                continue
            }
            val bc = if (bg != null && bg.size == px.size) bg[i] else 0xFF000000.toInt()
            val r = ((((c shr 16) and 0xFF) * a + ((bc shr 16) and 0xFF) * (1f - a))).toInt()
            val g = ((((c shr 8) and 0xFF) * a + ((bc shr 8) and 0xFF) * (1f - a))).toInt()
            val b = (((c and 0xFF) * a + (bc and 0xFF) * (1f - a))).toInt()
            px[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        return px
    }

    // ---- CP-78 color correction (§42) ----

    /**
     * Per-frame grade: temp/tint → brightness → contrast → highlights/shadows
     * (luma-weighted) → saturation → HSL hue/lightness. Mutates [px] in place.
     */
    private fun applyColor(px: IntArray, cc: ClipColor?): IntArray {
        if (cc == null || cc.isIdentity) return px
        val tempK = cc.temperature * 255f / 100f * 0.35f
        val tintK = cc.tint * 255f / 100f * 0.35f
        val brightK = cc.brightness * 255f / 100f
        val contF = (100 + cc.contrast) / 100f
        val satF = (100 + cc.saturation) / 100f
        val hiK = cc.highlights * 255f / 100f
        val shK = cc.shadows * 255f / 100f
        val expF = 2f.pow(cc.exposure / 100f)
        val whK = cc.whites * 255f / 100f * 0.75f
        val blK = cc.blacks * 255f / 100f * 0.75f
        val hue = cc.hueShift.toFloat()
        val lightK = cc.lightness / 100f
        val doHsl = cc.hueShift != 0 || cc.lightness != 0
        for (i in px.indices) {
            val c = px[i]
            var r = ((c shr 16) and 0xFF).toFloat()
            var g = ((c shr 8) and 0xFF).toFloat()
            var b = (c and 0xFF).toFloat()
            r += tempK
            b -= tempK
            g += tintK
            r += brightK
            g += brightK
            b += brightK
            r = 128f + (r - 128f) * contF
            g = 128f + (g - 128f) * contF
            b = 128f + (b - 128f) * contF
            r *= expF
            g *= expF
            b *= expF
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val wHi = ((lum - 128f) / 127f).coerceIn(0f, 1f)
            val wSh = ((128f - lum) / 128f).coerceIn(0f, 1f)
            val wWh = ((lum - 192f) / 63f).coerceIn(0f, 1f).let { it * it }
            val wBl = ((64f - lum) / 64f).coerceIn(0f, 1f).let { it * it }
            r += hiK * wHi + shK * wSh + whK * wWh + blK * wBl
            g += hiK * wHi + shK * wSh + whK * wWh + blK * wBl
            b += hiK * wHi + shK * wSh + whK * wWh + blK * wBl
            val lum2 = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 255f)
            r = lum2 + (r - lum2) * satF
            g = lum2 + (g - lum2) * satF
            b = lum2 + (b - lum2) * satF
            // CP-90 §34: 3-way wheels — lift/gain are zonal, gamma bends mids.
            if (cc.lift != 0 || cc.gamma != 0 || cc.gain != 0) {
                val lum3 = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 255f)
                val wLift = ((128f - lum3) / 128f).coerceIn(0f, 1f)
                val wGain = ((lum3 - 128f) / 127f).coerceIn(0f, 1f)
                val liftK = cc.lift * 255f / 100f * 0.5f
                val gainF = 1f + cc.gain / 100f * 0.5f * wGain
                r = (r + liftK * wLift) * gainF
                g = (g + liftK * wLift) * gainF
                b = (b + liftK * wLift) * gainF
                if (cc.gamma != 0) {
                    val gm = 1f / (1f + cc.gamma / 100f)
                    r = 255f * (r.coerceIn(0f, 255f) / 255f).toDouble().pow(gm.toDouble()).toFloat()
                    g = 255f * (g.coerceIn(0f, 255f) / 255f).toDouble().pow(gm.toDouble()).toFloat()
                    b = 255f * (b.coerceIn(0f, 255f) / 255f).toDouble().pow(gm.toDouble()).toFloat()
                }
            }
            if (doHsl) {
                val hsl = rgbToHsl(r / 255f, g / 255f, b / 255f)
                var h2 = hsl[0] + hue / 360f
                h2 -= kotlin.math.floor(h2)
                val l2 = (hsl[2] + lightK).coerceIn(0f, 1f)
                val rgb = hslToRgb(h2, hsl[1], l2)
                r = rgb[0] * 255f
                g = rgb[1] * 255f
                b = rgb[2] * 255f
            }
            px[i] = (c and 0xFF000000.toInt()) or
                (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or
                b.toInt().coerceIn(0, 255)
        }
        return px
    }

    private val lutCache = LinkedHashMap<String, com.aicodemax.tools.media.ColorLut?>()

    /** CP-81 §42: .cube LUT stage (after grade). Unreadable files are skipped. */
    private fun applyLut(px: IntArray, lut: com.aicodemax.data.media.ClipLut?): IntArray {
        if (lut == null || lut.strength <= 0) return px
        val cached = lutCache.getOrElse(lut.path) {
            val parsed = try {
                val f = java.io.File(lut.path)
                if (!f.isFile || f.length() > 8 * 1024 * 1024) null
                else com.aicodemax.tools.media.CubeLut.parse(f.readText()).getOrNull()
            } catch (_: Exception) {
                null
            }
            if (lutCache.size > 8) lutCache.remove(lutCache.keys.first())
            lutCache[lut.path] = parsed
            parsed
        } ?: return px
        return cached.applyTo(px, lut.strength)
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val rr = r.coerceIn(0f, 1f)
        val gg = g.coerceIn(0f, 1f)
        val bb = b.coerceIn(0f, 1f)
        val mx = maxOf(rr, gg, bb)
        val mn = minOf(rr, gg, bb)
        val l = (mx + mn) / 2f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            rr -> (gg - bb) / d + (if (gg < bb) 6f else 0f)
            gg -> (bb - rr) / d + 2f
            else -> (rr - gg) / d + 4f
        } / 6f
        return floatArrayOf(h, s, l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s == 0f) return floatArrayOf(l, l, l)
        fun f(n: Float): Float {
            val k = (n + h * 12f) % 12f
            val a = s * minOf(l, 1f - l)
            return l - a * maxOf(-1f, minOf(k - 3f, 9f - k, 1f))
        }
        return floatArrayOf(f(0f), f(8f), f(4f))
    }

    // ---- CP-77 transitions (§21) + basic fx (§20) ----

    /**
     * Transition-in blend. dissolve/wipe mix with the same-track predecessor's
     * last frame ([prevTail]); without one they fall back to black (honest v0:
     * rendered inside clip bounds, no A/B overlap).
     */
    private fun applyTransitionIn(
        px: IntArray,
        w: Int,
        h: Int,
        tr: ClipTransition?,
        prev: IntArray?,
        offsetMs: Long,
    ): IntArray {
        if (tr == null || tr.kind == "cut" || offsetMs >= tr.durationMs) return px
        val f = (offsetMs.toFloat() / tr.durationMs).coerceIn(0f, 1f)
        return when (tr.kind) {
            "fade" -> darken(px, 1f - f)
            "dissolve" -> if (prev != null && prev.size == px.size) mixArgb(prev, px, f) else darken(px, 1f - f)
            "wipeleft" -> wipe(px, w, h, prev, f, 0)
            "wiperight" -> wipe(px, w, h, prev, f, 1)
            "wipeup" -> wipe(px, w, h, prev, f, 2)
            "wipedown" -> wipe(px, w, h, prev, f, 3)
            else -> px
        }
    }

    /** Transition-out: fade to black over the clip tail (out only allows fade/cut). */
    private fun applyTransitionOut(px: IntArray, tr: ClipTransition?, offsetMs: Long, lenMs: Long): IntArray {
        if (tr == null || tr.kind == "cut") return px
        val start = lenMs - tr.durationMs
        if (offsetMs < start) return px
        val f = ((offsetMs - start).toFloat() / tr.durationMs).coerceIn(0f, 1f)
        return darken(px, f)
    }

    private fun darken(px: IntArray, amt: Float): IntArray {
        val keep = 1f - amt.coerceIn(0f, 1f)
        if (keep >= 1f) return px
        for (i in px.indices) {
            val c = px[i]
            px[i] = (c and 0xFF000000.toInt()) or
                ((((c shr 16) and 0xFF) * keep).toInt() shl 16) or
                ((((c shr 8) and 0xFF) * keep).toInt() shl 8) or
                (((c and 0xFF) * keep).toInt())
        }
        return px
    }

    private fun mixArgb(a: IntArray, b: IntArray, f: Float): IntArray {
        val t = f.coerceIn(0f, 1f)
        val out = IntArray(b.size)
        for (i in b.indices) {
            val ca = a[i]
            val cb = b[i]
            val r = (((ca shr 16) and 0xFF) + ((((cb shr 16) and 0xFF) - ((ca shr 16) and 0xFF)) * t)).toInt()
            val g = (((ca shr 8) and 0xFF) + ((((cb shr 8) and 0xFF) - ((ca shr 8) and 0xFF)) * t)).toInt()
            val bl = ((ca and 0xFF) + (((cb and 0xFF) - (ca and 0xFF)) * t)).toInt()
            out[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
        }
        return out
    }

    /** Wipe reveal of [px] over [prev] (or black). dir: 0=L 1=R 2=U 3=D. */
    private fun wipe(px: IntArray, w: Int, h: Int, prev: IntArray?, f: Float, dir: Int): IntArray {
        val out = if (prev != null && prev.size == px.size) prev.copyOf() else IntArray(px.size) { 0xFF000000.toInt() }
        val t = f.coerceIn(0f, 1f)
        when (dir) {
            0 -> {
                val x0 = (w * (1f - t)).toInt()
                for (y in 0 until h) for (x in x0 until w) out[y * w + x] = px[y * w + x]
            }
            1 -> {
                val x1 = (w * t).toInt()
                for (y in 0 until h) for (x in 0 until x1) out[y * w + x] = px[y * w + x]
            }
            2 -> {
                val y0 = (h * (1f - t)).toInt()
                for (y in y0 until h) for (x in 0 until w) out[y * w + x] = px[y * w + x]
            }
            else -> {
                val y1 = (h * t).toInt()
                for (y in 0 until y1) for (x in 0 until w) out[y * w + x] = px[y * w + x]
            }
        }
        return out
    }

    private fun applyFx(px: IntArray, w: Int, h: Int, fx: ClipFx?, seed: Long): IntArray {
        if (fx == null || fx.isIdentity) return px
        var cur = if (fx.blur > 0) boxBlur(px, w, h, fx.blur) else px
        if (fx.denoise > 0) cur = mixBlur(cur, w, h, fx.denoise)
        if (fx.sharpen > 0) cur = unsharp(cur, w, h, fx.sharpen)
        if (fx.vignette > 0) applyVignette(cur, w, h, fx.vignette)
        if (fx.grain > 0) applyGrain(cur, fx.grain, seed)
        return cur
    }

    /** CP-91: denoise = blend toward a radius-1 blur. */
    private fun mixBlur(src: IntArray, w: Int, h: Int, amount: Int): IntArray {
        val k = (amount / 100f * 0.8f).coerceIn(0f, 0.8f)
        val soft = boxBlur(src, w, h, 1)
        val out = IntArray(src.size)
        for (i in src.indices) {
            val a = src[i]
            val b = soft[i]
            val r = (((a shr 16) and 0xFF) * (1 - k) + ((b shr 16) and 0xFF) * k).toInt()
            val g = (((a shr 8) and 0xFF) * (1 - k) + ((b shr 8) and 0xFF) * k).toInt()
            val bl = ((a and 0xFF) * (1 - k) + (b and 0xFF) * k).toInt()
            out[i] = 0xFF000000.toInt() or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
        }
        return out
    }

    /** CP-91: unsharp mask against a radius-1 blur. */
    private fun unsharp(src: IntArray, w: Int, h: Int, amount: Int): IntArray {
        val k = amount / 100f * 1.2f
        val soft = boxBlur(src, w, h, 1)
        val out = IntArray(src.size)
        for (i in src.indices) {
            val a = src[i]
            val b = soft[i]
            val r = (((a shr 16) and 0xFF) * (1 + k) - ((b shr 16) and 0xFF) * k).toInt()
            val g = (((a shr 8) and 0xFF) * (1 + k) - ((b shr 8) and 0xFF) * k).toInt()
            val bl = ((a and 0xFF) * (1 + k) - (b and 0xFF) * k).toInt()
            out[i] = 0xFF000000.toInt() or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
        }
        return out
    }

    /** Separable box blur, O(w*h) per pass (sliding window). */
    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val r = radius.coerceIn(1, 10)
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        val window = r * 2 + 1
        for (y in 0 until h) {
            var rs = 0
            var gs = 0
            var bs = 0
            for (x in -r..r) {
                val c = src[y * w + x.coerceIn(0, w - 1)]
                rs += (c shr 16) and 0xFF
                gs += (c shr 8) and 0xFF
                bs += c and 0xFF
            }
            for (x in 0 until w) {
                tmp[y * w + x] = 0xFF000000.toInt() or ((rs / window) shl 16) or ((gs / window) shl 8) or (bs / window)
                val add = src[y * w + (x + r + 1).coerceIn(0, w - 1)]
                val sub = src[y * w + (x - r).coerceIn(0, w - 1)]
                rs += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                gs += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                bs += (add and 0xFF) - (sub and 0xFF)
            }
        }
        for (x in 0 until w) {
            var rs = 0
            var gs = 0
            var bs = 0
            for (y in -r..r) {
                val c = tmp[y.coerceIn(0, h - 1) * w + x]
                rs += (c shr 16) and 0xFF
                gs += (c shr 8) and 0xFF
                bs += c and 0xFF
            }
            for (y in 0 until h) {
                out[y * w + x] = 0xFF000000.toInt() or ((rs / window) shl 16) or ((gs / window) shl 8) or (bs / window)
                val add = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                val sub = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                rs += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                gs += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                bs += (add and 0xFF) - (sub and 0xFF)
            }
        }
        return out
    }

    private fun applyVignette(px: IntArray, w: Int, h: Int, amount: Int) {
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val maxD = kotlin.math.sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        val strength = amount.coerceIn(0, 100) / 100f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x - cx) / maxD
                val dy = (y - cy) / maxD
                val keep = 1f - strength * (dx * dx + dy * dy).coerceIn(0f, 1f)
                val i = y * w + x
                val c = px[i]
                px[i] = (c and 0xFF000000.toInt()) or
                    ((((c shr 16) and 0xFF) * keep).toInt() shl 16) or
                    ((((c shr 8) and 0xFF) * keep).toInt() shl 8) or
                    (((c and 0xFF) * keep).toInt())
            }
        }
    }

    private fun applyGrain(px: IntArray, amount: Int, seed: Long) {
        val amp = (amount.coerceIn(0, 100) * 255 / 100).coerceAtLeast(1)
        val rnd = kotlin.random.Random(seed)
        for (i in px.indices) {
            val n = rnd.nextInt(-amp, amp + 1)
            val c = px[i]
            val r = (((c shr 16) and 0xFF) + n).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + n).coerceIn(0, 255)
            val b = ((c and 0xFF) + n).coerceIn(0, 255)
            px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    // ---- CP-76 keyframes (§14) ----

    /** Per-frame sampled visual state: effective transform + free rotation degrees. */
    private data class FrameLook(val transform: ClipTransform?, val rotFree: Float)

    private fun frameLook(base: ClipTransform?, keys: ClipKeyframes?, offsetMs: Long): FrameLook {
        if (keys == null || keys.isEmpty) {
            return FrameLook(base?.takeUnless { it.isIdentity }, base?.rotation?.toFloat() ?: 0f)
        }
        val b = base ?: ClipTransform()
        val rot = keys.valueAt("rotation", offsetMs) ?: b.rotation.toFloat()
        val t = b.copy(
            posX = keys.valueAt("posX", offsetMs)?.toInt() ?: b.posX,
            posY = keys.valueAt("posY", offsetMs)?.toInt() ?: b.posY,
            scale = keys.valueAt("scale", offsetMs)?.toInt() ?: b.scale,
            rotation = rot.toInt(),
            opacity = keys.valueAt("opacity", offsetMs)?.toInt() ?: b.opacity,
            cropX = keys.valueAt("cropX", offsetMs)?.toInt() ?: b.cropX,
            cropY = keys.valueAt("cropY", offsetMs)?.toInt() ?: b.cropY,
            cropW = keys.valueAt("cropW", offsetMs)?.toInt() ?: b.cropW,
            cropH = keys.valueAt("cropH", offsetMs)?.toInt() ?: b.cropH,
        )
        return FrameLook(t.takeUnless { it.isIdentity && isStep90(rot) }, rot)
    }

    /** CP-84 §45: Ken Burns — interpolates zoom/pan over clip progress 0..1. */
    private fun withMotion(look: FrameLook, motion: com.aicodemax.data.media.ClipMotion?, progress: Float, outW: Int, outH: Int): FrameLook {
        if (motion == null || motion.isIdentity) return look
        val pr = progress.coerceIn(0f, 1f)
        val b = look.transform ?: ClipTransform()
        val z = motion.zoom.toFloat()
        return when (motion.direction) {
            "in" -> look.copy(transform = b.copy(scale = (b.scale + z * pr).toInt()))
            "out" -> look.copy(transform = b.copy(scale = (b.scale + z * (1f - pr)).toInt()))
            else -> {
                val span = z / 100f * minOf(outW, outH) * 0.25f * (pr - 0.5f) * 2f
                val (dx, dy) = when (motion.direction) {
                    "left" -> -span to 0f
                    "right" -> span to 0f
                    "up" -> 0f to -span
                    else -> 0f to span
                }
                look.copy(transform = b.copy(scale = (b.scale + z / 2f).toInt(), posX = b.posX + dx.toInt(), posY = b.posY + dy.toInt()))
            }
        }
    }

    private fun isStep90(deg: Float): Boolean {
        val r = ((deg % 360 + 360) % 360).toInt()
        return r == 0 || r == 90 || r == 180 || r == 270
    }

    private fun composeLook(
        pixels: IntArray,
        w: Int,
        h: Int,
        look: FrameLook,
        outW: Int,
        outH: Int,
    ): IntArray {
        val st = look.transform ?: return scaleArgb(pixels, w, h, outW, outH)
        return if (isStep90(look.rotFree)) {
            composeFrame(pixels, w, h, st, outW, outH)
        } else {
            composeFrameFree(pixels, w, h, st, look.rotFree, outW, outH)
        }
    }

    /** Arbitrary-rotation variant of [composeFrame] (Matrix path, keyframed spin). */
    private fun composeFrameFree(
        pixels: IntArray,
        w: Int,
        h: Int,
        t: ClipTransform,
        rotDeg: Float,
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
        val (oriented, ow, oh) = rotateFlip(cropped, cw, ch, 0, t.flipH, t.flipV)
        val src = android.graphics.Bitmap.createBitmap(ow, oh, android.graphics.Bitmap.Config.ARGB_8888)
        src.setPixels(oriented, 0, ow, 0, 0, ow, oh)
        val canvas = android.graphics.Bitmap.createBitmap(outW, outH, android.graphics.Bitmap.Config.ARGB_8888)
        canvas.eraseColor(0xFF000000.toInt())
        val s = (maxOf(outW.toDouble() / ow, outH.toDouble() / oh) * t.scale / 100.0).toFloat()
        val m = android.graphics.Matrix()
        m.postScale(s, s)
        m.postRotate(rotDeg, ow * s / 2f, oh * s / 2f)
        val bounds = android.graphics.RectF(0f, 0f, ow.toFloat(), oh.toFloat())
        m.mapRect(bounds)
        m.postTranslate(outW / 2f + t.posX - bounds.centerX(), outH / 2f + t.posY - bounds.centerY())
        val paint = android.graphics.Paint().apply {
            alpha = (t.opacity * 255 / 100).coerceIn(0, 255)
            isFilterBitmap = true
        }
        android.graphics.Canvas(canvas).drawBitmap(src, m, paint)
        src.recycle()
        val out = IntArray(outW * outH)
        canvas.getPixels(out, 0, outW, 0, 0, outW, outH)
        canvas.recycle()
        return out
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
                    val fadeIn = seg.clip.transitionIn?.takeUnless { it.kind == "cut" }?.durationMs ?: 0L
                    val fadeOut = seg.clip.transitionOut?.takeUnless { it.kind == "cut" }?.durationMs ?: 0L
                    mixAudioSlice(mix, frames, stereo, startFrame, endFrame, atFrame, gain, seg.speed, seg.clip.keyframes?.takeUnless { it.points("volume").isEmpty() }, fadeIn, fadeOut)
                }
            }
        }
        return encodeAac(mix, rate, dst)
    }

    /**
     * CP-75: mixes one audio slice with optional speed (rate/reverse/curve).
     * Curves walk the normalized profile incrementally (O(n), linear interp).
     */
    private fun mixAudioSlice(
        mix: FloatArray,
        mixFrames: Int,
        stereo: PcmAudio,
        startFrame: Int,
        endFrame: Int,
        atFrame: Int,
        gain: Float,
        speed: ClipSpeed?,
        volKeys: ClipKeyframes? = null,
        fadeInMs: Long = 0,
        fadeOutMs: Long = 0,
    ) {
        fun volGain(outMs: Long): Float =
            volKeys?.valueAt("volume", outMs)?.div(100f) ?: 1f
        fun fadeGain(outMs: Double, totalMs: Double): Float {
            var f = 1f
            if (fadeInMs > 0) f *= (outMs / fadeInMs).toFloat().coerceIn(0f, 1f)
            if (fadeOutMs > 0) f *= ((totalMs - outMs) / fadeOutMs).toFloat().coerceIn(0f, 1f)
            return f
        }
        val stepMs = 1000.0 / stereo.sampleRate
        if (speed == null) {
            val totalMs = (endFrame - startFrame) * stepMs
            var s = startFrame
            var d = atFrame
            while (s < endFrame && d < mixFrames) {
                val outMs = (d - atFrame) * stepMs
                val g = gain * volGain(outMs.toLong()) * fadeGain(outMs, totalMs)
                mix[d * 2] += stereo.samples[s * 2] * g
                mix[d * 2 + 1] += stereo.samples[s * 2 + 1] * g
                s += 1
                d += 1
            }
            return
        }
        val sliceLen = (endFrame - startFrame).coerceAtLeast(1)
        val outLen = (sliceLen * 100 / speed.rate).coerceAtLeast(1)
        val prof = speed.profile()
        var srcMs = 0.0
        var o = 0
        var d = atFrame
        while (o < outLen && d < mixFrames) {
            val permill = ((o.toLong() * 1000) / outLen).toInt().coerceIn(0, 1000)
            srcMs += prof[permill] * stepMs
            var f = startFrame + srcMs * stereo.sampleRate / 1000.0
            if (speed.reverse) f = endFrame - (f - startFrame)
            val clamped = f.coerceIn(startFrame.toDouble(), (endFrame - 1).coerceAtLeast(startFrame).toDouble())
            val i0 = clamped.toInt()
            val i1 = (i0 + 1).coerceAtMost((endFrame - 1).coerceAtLeast(startFrame))
            val frac = (clamped - i0).toFloat()
            val l = stereo.samples[i0 * 2] * (1 - frac) + stereo.samples[i1 * 2] * frac
            val r = stereo.samples[i0 * 2 + 1] * (1 - frac) + stereo.samples[i1 * 2 + 1] * frac
            val g = gain * volGain((o * stepMs).toLong()) * fadeGain(o * stepMs, outLen * stepMs)
            mix[d * 2] += l * g
            mix[d * 2 + 1] += r * g
            o += 1
            d += 1
        }
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
