package com.aicodemax.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.media.GrayFrame
import com.aicodemax.tools.media.StabAnalysis
import com.aicodemax.tools.media.StabRequest
import com.aicodemax.tools.media.TemplateMatch
import com.aicodemax.tools.media.TrackAnalysis
import com.aicodemax.tools.media.TrackRequest
import com.aicodemax.tools.media.TrackingPort
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CP-80 real motion analysis (§15/§43): frames via MediaMetadataRetriever,
 * NCC template matching (no ML). Analysis width capped at 160px; samples
 * capped at 48; low-score frames hold the last offset (counted as lost).
 */
class AndroidTrackingPort : TrackingPort {
    override suspend fun analyzeTrack(request: TrackRequest): Outcome<TrackAnalysis> =
        withContext(Dispatchers.IO) {
            val file = File(request.assetPath)
            if (!file.isFile) {
                return@withContext Outcome.Failure(AppError("TRACK_NO_FILE", "ไม่พบไฟล์ ${request.assetPath}"))
            }
            val end = request.endMs.coerceAtLeast(request.startMs + 300)
            val times = sampleTimes(request.startMs, end, request.stepMs)
            val frames = grabFrames(request.assetPath, times)
            if (frames.size < 2) {
                return@withContext Outcome.Failure(AppError("TRACK_FRAMES", "ดึงเฟรมไม่ได้ (ได้ ${frames.size} เฟรม)"))
            }
            val fw = frames[0].width
            val fh = frames[0].height
            val rx = (request.x * fw / 100).coerceIn(0, fw - 1)
            val ry = (request.y * fh / 100).coerceIn(0, fh - 1)
            val rw = (request.w * fw / 100).coerceIn(8, fw - rx)
            val rh = (request.h * fh / 100).coerceIn(8, fh - ry)
            val radius = (minOf(fw, fh) / 8).coerceIn(6, 20)
            val matches = TemplateMatch.trackAbsolute(frames, rx, ry, rw, rh, radius)
            val used = times.take(matches.size)
            var lost = 0
            var scoreSum = 0.0
            var lastDx = 0f
            var lastDy = 0f
            val dx = mutableListOf<Float>()
            val dy = mutableListOf<Float>()
            val at = mutableListOf<Long>()
            for (i in matches.indices) {
                val m = matches[i]
                scoreSum += m.score.coerceIn(0.0, 1.0)
                if (m.score < 0.55 && i > 0) {
                    lost += 1
                } else {
                    lastDx = m.dx.toFloat()
                    lastDy = m.dy.toFloat()
                }
                dx += lastDx
                dy += lastDy
                at += used[i] - request.startMs
            }
            if (lost > matches.size / 2) {
                return@withContext Outcome.Failure(
                    AppError("TRACK_LOST", "แทร็กหลุด $lost/${matches.size} เฟรม — ลองกรอบใหญ่ขึ้นหรือช่วงชัดกว่านี้"),
                )
            }
            val path = TemplateMatch.toPath(at, dx, dy, fw, fh)
            Outcome.Success(TrackAnalysis(path, matches.size, lost, scoreSum / matches.size))
        }

    override suspend fun analyzeStab(request: StabRequest): Outcome<StabAnalysis> =
        withContext(Dispatchers.IO) {
            val file = File(request.assetPath)
            if (!file.isFile) {
                return@withContext Outcome.Failure(AppError("STAB_NO_FILE", "ไม่พบไฟล์ ${request.assetPath}"))
            }
            val end = request.endMs.coerceAtLeast(request.startMs + 500)
            val times = sampleTimes(request.startMs, end, request.stepMs)
            val frames = grabFrames(request.assetPath, times)
            if (frames.size < 3) {
                return@withContext Outcome.Failure(AppError("STAB_FRAMES", "ดึงเฟรมไม่ได้ (ได้ ${frames.size} เฟรม)"))
            }
            val fw = frames[0].width
            val fh = frames[0].height
            // Center patch differential tracking = camera motion.
            val rw = (fw / 4).coerceAtLeast(16)
            val rh = (fh / 4).coerceAtLeast(16)
            val radius = (minOf(fw, fh) / 10).coerceIn(4, 14)
            val steps = TemplateMatch.trackDifferential(frames, (fw - rw) / 2, (fh - rh) / 2, rw, rh, radius)
            // Accumulate trajectory (starts at 0 for frame 0).
            val traj = mutableListOf(0f to 0f)
            var ax = 0f
            var ay = 0f
            for (m in steps) {
                if (m.score >= 0.5) {
                    ax += m.dx
                    ay += m.dy
                }
                traj += ax to ay
            }
            val window = ((request.smoothMs / request.stepMs.coerceAtLeast(1)).toInt() / 2 * 2 + 1).coerceIn(1, 15)
            val smooth = TemplateMatch.smooth(traj, window)
            // Correction = smooth - raw (inverse residual), % units, range-relative times.
            val used = times.take(traj.size)
            val dx = traj.indices.map { (smooth[it].first - traj[it].first) * 100f / fw }
            val dy = traj.indices.map { (smooth[it].second - traj[it].second) * 100f / fh }
            val path = com.aicodemax.data.media.TrackPath(
                used.indices.map { i ->
                    com.aicodemax.data.media.TrackPoint(used[i] - request.startMs, dx[i], dy[i])
                },
            )
            var peak = 0f
            for (i in dx.indices) {
                peak = maxOf(peak, kotlin.math.hypot(dx[i], dy[i]))
            }
            val zoom = request.zoom ?: (peak + 2f).toInt().coerceIn(0, 20)
            Outcome.Success(StabAnalysis(path, traj.size, peak.toDouble(), zoom.coerceIn(0, 50)))
        }

    /** Sample times in ms, capped at 48 samples. */
    private fun sampleTimes(startMs: Long, endMs: Long, stepMs: Long): List<Long> {
        val step = stepMs.coerceAtLeast(50)
        val times = mutableListOf<Long>()
        var t = startMs
        while (t <= endMs && times.size < 48) {
            times += t
            t += step
        }
        if (times.isEmpty()) times += startMs
        return times
    }

    private fun grabFrames(path: String, timesMs: List<Long>): List<GrayFrame> {
        val out = mutableListOf<GrayFrame>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            for (t in timesMs) {
                val bmp = try {
                    retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (_: Exception) {
                    null
                } ?: continue
                val gray = toGray(bmp)
                bmp.recycle()
                if (out.isEmpty() || (gray.width == out[0].width && gray.height == out[0].height)) {
                    out += gray
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** Downscale to ≤160px wide + grayscale. */
    private fun toGray(bmp: Bitmap): GrayFrame {
        val scale = 160.0 / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
        val w = (bmp.width * scale).toInt().coerceIn(16, 160)
        val h = (bmp.height * scale).toInt().coerceIn(16, 160)
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small != bmp) small.recycle()
        val gray = ByteArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = ((0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)).toInt()).toByte()
        }
        return GrayFrame(w, h, gray)
    }
}
