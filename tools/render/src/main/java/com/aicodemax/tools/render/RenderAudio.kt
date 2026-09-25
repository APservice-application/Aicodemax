package com.aicodemax.tools.render

import com.aicodemax.data.media.Clip
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.Timeline
import com.aicodemax.tools.audio.PcmAudio
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure audio decisions shared by the Android render planner and JVM tests.
 * The video's own audio track is a first-class source, not an implicit side
 * effect of fast-copy. Muting/detaching a video must also silence fast-copy.
 */
object RenderAudio {
    data class SourceWindow(val startMs: Long, val endMs: Long) // [start, end)

    fun selectedClips(
        timeline: Timeline,
        includeAudio: Boolean,
        videoAssetsWithAudio: Map<String, Boolean>,
    ): List<Clip> {
        if (!includeAudio) return emptyList()
        return timeline.tracks.sortedBy { it.id }.flatMap { track ->
            if (track.muted || (track.kind != MediaKind.AUDIO && track.hidden)) {
                emptyList()
            } else {
                track.clips.sortedBy { it.atMs }.filter { clip ->
                    clip.volume > 0 && when (track.kind) {
                        MediaKind.AUDIO -> true
                        MediaKind.VIDEO -> videoAssetsWithAudio[clip.assetId] == true
                        MediaKind.IMAGE -> false
                    }
                }
            }
        }
    }

    /** Only a single unmodified native source (or no audio) may bypass mixing. */
    fun canFastCopy(singleVideo: Clip, selectedAudio: List<Clip>): Boolean =
        selectedAudio.isEmpty() ||
            (selectedAudio.size == 1 && selectedAudio[0].id == singleVideo.id && singleVideo.volume == 100)

    /**
     * The source window required to mix an output time window, including a
     * small interpolation/decoder guard. Endpoints are source-ms, end exclusive.
     */
    fun sourceRange(clip: Clip, outputStartMs: Double, outputEndMs: Double, guardMs: Long = 20): SourceWindow? {
        val localStart = (outputStartMs - clip.atMs).coerceAtLeast(0.0)
        val localEnd = (outputEndMs - clip.atMs).coerceAtMost(clip.outputDurationMs().toDouble())
        if (localEnd <= localStart || clip.endMs <= clip.startMs) return null
        val mapper = SourceMapper(clip)
        val a = mapper.sourceMs(localStart)
        val b = mapper.sourceMs(localEnd)
        val first = (floor(minOf(a, b)).toLong() - guardMs).coerceAtLeast(clip.startMs)
        val exclusiveEnd = (ceil(maxOf(a, b)).toLong() + guardMs + 1).coerceAtMost(clip.endMs)
        return if (exclusiveEnd > first) SourceWindow(first, exclusiveEnd) else null
    }

    /**
     * Add one decoded source window to an interleaved stereo float output
     * chunk. [sourceStartMs] is the requested decoder window start, including
     * any PCM silence before an audio track's first sample. All time mapping
     * is global, so split chunks have the same positions and fade envelopes.
     */
    fun mixChunk(
        into: FloatArray,
        chunkFirstFrame: Long,
        rate: Int,
        clip: Clip,
        source: PcmAudio,
        sourceStartMs: Long,
    ) {
        require(into.size % 2 == 0 && rate > 0)
        if (source.frames == 0 || clip.volume == 0) return
        val mapper = SourceMapper(clip)
        val duration = clip.outputDurationMs().toDouble()
        val keys = clip.keyframes?.takeUnless { it.points("volume").isEmpty() }
        val fadeInMs = clip.transitionIn?.takeUnless { it.kind == "cut" }?.durationMs ?: 0L
        val fadeOutMs = clip.transitionOut?.takeUnless { it.kind == "cut" }?.durationMs ?: 0L
        val baseGain = clip.volume / 100.0f
        for (i in 0 until into.size / 2) {
            val local = (chunkFirstFrame + i) * 1000.0 / rate - clip.atMs
            if (local < 0.0 || local >= duration) continue
            val srcFrame = (mapper.sourceMs(local) - sourceStartMs) * source.sampleRate / 1000.0
            // A late-starting or short audio track is silence outside its samples.
            if (srcFrame < 0.0 || srcFrame > source.frames) continue
            // Reverse starts exactly at the exclusive source endpoint. Use
            // the last real sample rather than dropping the first output frame.
            val a = floor(srcFrame).toInt().coerceIn(0, source.frames - 1)
            val b = (a + 1).coerceAtMost(source.frames - 1)
            val fraction = (srcFrame - a).toFloat().coerceIn(0f, 1f)
            var gain = baseGain
            if (keys != null) gain *= (keys.valueAt("volume", local.toLong()) ?: 100f) / 100f
            if (fadeInMs > 0) gain *= (local / fadeInMs).toFloat().coerceIn(0f, 1f)
            if (fadeOutMs > 0) gain *= ((duration - local) / fadeOutMs).toFloat().coerceIn(0f, 1f)
            val l0 = source.samples[a * source.channels]
            val l1 = source.samples[b * source.channels]
            val r0 = source.samples[a * source.channels + source.channels - 1]
            val r1 = source.samples[b * source.channels + source.channels - 1]
            into[i * 2] += (l0 * (1f - fraction) + l1 * fraction) * gain
            into[i * 2 + 1] += (r0 * (1f - fraction) + r1 * fraction) * gain
        }
    }

    /** Piecewise integral evaluated once per clip/window, not once per sample. */
    private class SourceMapper(private val clip: Clip) {
        private val speed = clip.speed
        private val length = clip.durationMs.toDouble()
        private val duration = clip.outputDurationMs().toDouble().coerceAtLeast(1.0)
        private val curvePrefix: DoubleArray? = speed?.takeIf { it.curve.isNotEmpty() }?.profile()?.let { rates ->
            DoubleArray(1002).also { prefix ->
                for (i in 0..1000) prefix[i + 1] = prefix[i] + rates[i]
            }
        }

        fun sourceMs(localMs: Double): Double {
            val t = localMs.coerceIn(0.0, duration)
            val offset = if (curvePrefix != null) {
                val tap = (t * 1000.0 / duration).coerceIn(0.0, 1000.0)
                val i = floor(tap).toInt().coerceIn(0, 999)
                val used = curvePrefix[i] + (tap - i) * (curvePrefix[i + 1] - curvePrefix[i])
                (used / curvePrefix[1000]) * length
            } else {
                (t * (speed?.rate ?: 100) / 100.0).coerceIn(0.0, length)
            }
            return if (speed?.reverse == true) clip.endMs - offset else clip.startMs + offset
        }
    }
}
