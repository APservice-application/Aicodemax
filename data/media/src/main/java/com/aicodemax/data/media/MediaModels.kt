package com.aicodemax.data.media

import kotlinx.serialization.Serializable

/** Asset kinds the creative engine handles (§9). */
@Serializable
enum class MediaKind {
    VIDEO,
    AUDIO,
    IMAGE,
}

/** An imported file: copied under the project dir with probe facts. */
@Serializable
data class MediaAsset(
    val id: String,
    val kind: MediaKind,
    /** File name inside the project's assets/ dir. */
    val fileName: String,
    val originalName: String,
    val sizeBytes: Long,
    /** Probe facts (format/duration/dims/...) as stored by the importer. */
    val facts: Map<String, String> = emptyMap(),
    val addedAt: Long = 0,
)

/** CP-73 basic video transform (§12): crop/rotate/flip/scale/position/opacity. */
@Serializable
data class ClipTransform(
    /** Clockwise rotation: 0/90/180/270. */
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Crop window in percent (0..100); 0,0,100,100 = no crop. */
    val cropX: Int = 0,
    val cropY: Int = 0,
    val cropW: Int = 100,
    val cropH: Int = 100,
    /** Zoom percent 1..400 (100 = fit). */
    val scale: Int = 100,
    /** Offset in output px from center. */
    val posX: Int = 0,
    val posY: Int = 0,
    /** Opacity 0..100 (v0: blended over black). */
    val opacity: Int = 100,
) {
    val isIdentity: Boolean get() = this == ClipTransform()

    /** Thai error strings (empty = valid). */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (rotation !in setOf(0, 90, 180, 270)) errors.add("มุมหมุนต้องเป็น 0/90/180/270 (ได้ $rotation)")
        if (cropX !in 0..100 || cropY !in 0..100) errors.add("จุดเริ่มครอปต้องอยู่ 0..100")
        if (cropW < 1 || cropH < 1 || cropX + cropW > 100 || cropY + cropH > 100) {
            errors.add("ขนาดครอปไม่ถูก ($cropX,$cropY ${cropW}x$cropH)")
        }
        if (scale !in 1..400) errors.add("ซูมต้องอยู่ 1..400% (ได้ $scale)")
        if (posX !in -4000..4000 || posY !in -4000..4000) errors.add("ตำแหน่งต้องอยู่ ±4000px")
        if (opacity !in 0..100) errors.add("ความทึบต้องอยู่ 0..100 (ได้ $opacity)")
        return errors
    }

    /** Compact chat/UI summary, e.g. R90 ↔ 150% α80. */
    fun summary(): String = buildList {
        if (rotation != 0) add("R$rotation")
        if (flipH) add("↔")
        if (flipV) add("↕")
        if (cropX != 0 || cropY != 0 || cropW != 100 || cropH != 100) add("crop")
        if (scale != 100) add("$scale%")
        if (posX != 0 || posY != 0) add("$posX,$posY")
        if (opacity != 100) add("α$opacity")
    }.joinToString(" ")
}

/** CP-75 speed point: output permille (0..1000) → relative rate (25..400). */
@Serializable
data class SpeedPoint(
    val at: Int,
    val rate: Int,
)

/**
 * CP-75 clip speed (§13): constant rate + reverse + ramp curve.
 * Output duration = source × 100/rate; the curve shapes pacing WITHIN that
 * duration (normalized), so ops math stays exact. No frame interpolation
 * (nearest sampling) and no optical flow — stated honestly in the descriptor.
 */
@Serializable
data class ClipSpeed(
    val rate: Int = 100,
    val reverse: Boolean = false,
    val curve: List<SpeedPoint> = emptyList(),
) {
    val isIdentity: Boolean get() = rate == 100 && !reverse && curve.isEmpty()

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (rate !in 25..400) errors.add("ความเร็วต้องอยู่ 25..400% (ได้ $rate)")
        if (curve.isNotEmpty()) {
            if (curve.size !in 2..8) errors.add("curve ต้องมี 2..8 จุด")
            if (curve.zipWithNext().any { (a, b) -> b.at <= a.at }) {
                errors.add("จุด curve ต้องเรียงเวลา strictly")
            }
            curve.firstOrNull()?.let { if (it.at != 0) errors.add("curve ต้องเริ่มที่ 0") }
            curve.lastOrNull()?.let { if (it.at != 1000) errors.add("curve ต้องจบที่ 1000") }
            if (curve.any { it.rate !in 25..400 }) errors.add("rate ใน curve ต้องอยู่ 25..400")
            if (reverse) errors.add("ย้อนกลับใช้กับ curve ไม่ได้ (v0)")
        }
        return errors
    }

    /** Output length for [srcLen] source ms. */
    fun outputDuration(srcLen: Long): Long =
        if (srcLen <= 0) 0 else (srcLen * 100 / rate).coerceAtLeast(1)

    /** Interpolated relative rate (25..400) at output permille [f]. */
    fun curveRateAt(f: Int): Double {
        if (curve.isEmpty()) return rate.toDouble()
        val x = f.coerceIn(0, 1000)
        val pts = curve
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (x in a.at..b.at) {
                if (b.at == a.at) return b.rate.toDouble()
                val t = (x - a.at).toDouble() / (b.at - a.at)
                return a.rate + (b.rate - a.rate) * t
            }
        }
        return pts.last().rate.toDouble()
    }

    /**
     * Normalized profile: source-ms consumed per output-ms, averaged over
     * 1001 taps so the curve keeps the constant-rate total duration.
     */
    fun profile(): DoubleArray {
        val table = DoubleArray(1001) { curveRateAt(it) }
        if (curve.isEmpty()) return DoubleArray(1001) { rate / 100.0 }
        val avg = table.average().coerceAtLeast(1.0)
        val norm = rate / avg
        return DoubleArray(1001) { table[it] * norm / 100.0 }
    }

    /** Source offset (0..srcLen) for output offset [outMs]. */
    fun outputToSource(outMs: Long, srcLen: Long): Long {
        if (curve.isEmpty()) return (outMs * rate / 100).coerceIn(0, srcLen)
        val out = outputDuration(srcLen)
        if (out <= 0) return 0
        val clamped = outMs.coerceIn(0, out)
        // Exact endpoints (numeric integration only shapes the interior).
        if (clamped <= 0) return 0
        if (clamped >= out) return srcLen
        val prof = profile()
        val upto = ((clamped * 1000) / out).toInt().coerceIn(0, 1000)
        var acc = 0.0
        for (i in 0 until upto) acc += prof[i]
        return (acc * out / 1000).toLong().coerceIn(0, srcLen)
    }

    /** Output offset for source offset [srcMs] (inverse walk). */
    fun sourceToOutput(srcMs: Long, srcLen: Long): Long {
        if (curve.isEmpty()) return (srcMs * 100 / rate).coerceAtLeast(0)
        val out = outputDuration(srcLen)
        val target = srcMs.coerceIn(0, srcLen)
        var lo = 0L
        var hi = out
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (outputToSource(mid, srcLen) < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun summary(): String = buildList {
        if (rate != 100) add((rate / 100.0).toString().trimEnd('0').trimEnd('.') + "x")
        if (reverse) add("REV")
        if (curve.isNotEmpty()) add("~curve")
    }.joinToString(" ")

    companion object {
        /** Named ramp presets (§13 curves). */
        fun preset(name: String): List<SpeedPoint> = when (name.lowercase()) {
            "linear" -> listOf(SpeedPoint(0, 100), SpeedPoint(1000, 100))
            "easein" -> listOf(SpeedPoint(0, 40), SpeedPoint(500, 80), SpeedPoint(1000, 160))
            "easeout" -> listOf(SpeedPoint(0, 160), SpeedPoint(500, 80), SpeedPoint(1000, 40))
            "easeinout" -> listOf(SpeedPoint(0, 50), SpeedPoint(500, 140), SpeedPoint(1000, 50))
            "montage" -> listOf(SpeedPoint(0, 60), SpeedPoint(300, 180), SpeedPoint(600, 70), SpeedPoint(1000, 160))
            "hero" -> listOf(SpeedPoint(0, 35), SpeedPoint(700, 60), SpeedPoint(1000, 250))
            "bullet" -> listOf(SpeedPoint(0, 250), SpeedPoint(400, 120), SpeedPoint(1000, 45))
            else -> emptyList()
        }

        fun presetNames(): List<String> = listOf("linear", "easein", "easeout", "easeinout", "montage", "hero", "bullet")
    }
}

/** One placed piece of an asset on a track. Times in ms. */
@Serializable
data class Clip(
    val id: String,
    val assetId: String,
    /** Source range [startMs, endMs). */
    val startMs: Long,
    val endMs: Long,
    /** Position on the timeline. */
    val atMs: Long,
    /** Volume 0..100 for audio/video clips (default 100). */
    val volume: Int = 100,
    /** Visual transform (null = identity). */
    val transform: ClipTransform? = null,
    /** Playback speed (null = identity). */
    val speed: ClipSpeed? = null,
) {
    val durationMs: Long get() = endMs - startMs

    /** Timeline-facing length after speed. */
    fun outputDurationMs(): Long = speed?.outputDuration(durationMs) ?: durationMs

    /** Source offset for an output offset (0..output). */
    fun outputToSource(outMs: Long): Long =
        speed?.outputToSource(outMs.coerceIn(0, outputDurationMs()), durationMs)
            ?: outMs.coerceIn(0, durationMs)

    /** Output offset for a source offset (0..duration). */
    fun sourceToOutput(srcMs: Long): Long =
        speed?.sourceToOutput(srcMs.coerceIn(0, durationMs), durationMs)
            ?: srcMs.coerceIn(0, durationMs)
}

/** A single lane of clips (V1/A1/...). Overlaps are allowed (top clip wins). */
@Serializable
data class Track(
    val id: String,
    val kind: MediaKind,
    val clips: List<Clip> = emptyList(),
    /** CP-72: locked = no edits; muted (audio) = skipped in mix; hidden (video) = skipped in render. */
    val locked: Boolean = false,
    val muted: Boolean = false,
    val hidden: Boolean = false,
    val color: String = "",
)

/**
 * Timeline — the source of truth every plan/render reads (§9).
 * Pure data + validation; rendering lives in CP-67.
 */
@Serializable
data class TimelineMarker(
    val id: String,
    val atMs: Long,
    val label: String = "",
    val color: String = "",
)

/** CP-74 styled text overlay on the timeline (§22). Times in ms. */
@Serializable
data class OverlayText(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** Anchor center in percent of output (0..100). */
    val xPct: Int = 50,
    val yPct: Int = 82,
    /** Text size as percent of output height (1..30). */
    val sizePct: Int = 6,
    /** ARGB as Long (0xAARRGGBB). */
    val color: Long = 0xFFFFFFFFL,
    /** left/center/right. */
    val align: String = "center",
    val bold: Boolean = false,
    /** Outline width at 720p (0 = none, scaled with output). */
    val strokePx: Int = 0,
    val strokeColor: Long = 0xFF000000L,
    val shadow: Boolean = true,
    val background: Boolean = true,
    /** Background box ARGB (used when background=true). */
    val bgColor: Long = 0xA8000000L,
    val rotation: Int = 0,
    val opacity: Int = 100,
    /** none/fade/slide/pop/typewriter. */
    val animIn: String = "none",
    /** none/fade. */
    val animOut: String = "none",
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (text.isBlank()) errors.add("ข้อความว่างไม่ได้")
        if (endMs <= startMs || startMs < 0) errors.add("ช่วงเวลาไม่ถูก ($startMs..$endMs)")
        if (xPct !in 0..100 || yPct !in 0..100) errors.add("ตำแหน่งต้องอยู่ 0..100")
        if (sizePct !in 1..30) errors.add("ขนาดต้องอยู่ 1..30% (ได้ $sizePct)")
        if (align !in setOf("left", "center", "right")) errors.add("align ต้องเป็น left/center/right")
        if (strokePx !in 0..40) errors.add("เส้นขอบต้องอยู่ 0..40")
        if (rotation !in -180..180) errors.add("มุมหมุนต้องอยู่ ±180")
        if (opacity !in 0..100) errors.add("ความทึบต้องอยู่ 0..100")
        if (animIn !in setOf("none", "fade", "slide", "pop", "typewriter")) errors.add("animIn ไม่รู้จัก ($animIn)")
        if (animOut !in setOf("none", "fade")) errors.add("animOut ไม่รู้จัก ($animOut)")
        return errors
    }

    fun summary(): String = "\"$text\" $startMs..$endMs" + if (animIn != "none") " +$animIn" else ""

    companion object {
        /** Named style presets (§22 quick styles). */
        fun preset(name: String): OverlayText = when (name.lowercase()) {
            "title" -> OverlayText("", "", 0, 0, yPct = 20, sizePct = 9, bold = true, animIn = "fade")
            "lower" -> OverlayText("", "", 0, 0, xPct = 30, yPct = 78, sizePct = 5, align = "left", background = true)
            "caption" -> OverlayText("", "", 0, 0, yPct = 88, sizePct = 5, animIn = "fade")
            "hook" -> OverlayText("", "", 0, 0, yPct = 35, sizePct = 8, bold = true, color = 0xFFFFFF00L, strokePx = 3, animIn = "pop")
            "cta" -> OverlayText("", "", 0, 0, yPct = 65, sizePct = 7, bold = true, color = 0xFF000000L, background = true, bgColor = 0xE8FFFFFFL, animIn = "slide")
            else -> OverlayText("", "", 0, 0)
        }
    }
}

@Serializable
data class Timeline(
    val tracks: List<Track> = emptyList(),
    val markers: List<TimelineMarker> = emptyList(),
    val texts: List<OverlayText> = emptyList(),
) {
    val durationMs: Long get() = tracks.flatMap { it.clips }.maxOfOrNull { it.atMs + it.outputDurationMs() } ?: 0

    /** Clips in timeline order (by position, then track). CP-72 chat index = 1-based into this list. */
    fun orderedClips(): List<Pair<Track, Clip>> =
        tracks.flatMap { track -> track.clips.map { track to it } }
            .sortedWith(compareBy({ it.second.atMs }, { it.first.id }))

    fun findClip(clipId: String): Pair<Track, Clip>? =
        tracks.firstNotNullOfOrNull { track -> track.clips.firstOrNull { it.id == clipId }?.let { track to it } }

    /** Returns Thai error strings (empty = valid). Unknown asset ids are reported. */
    fun validate(assets: Set<String>): List<String> {
        val errors = mutableListOf<String>()
        for (track in tracks) {
            for (clip in track.clips) {
                if (clip.assetId !in assets) {
                    errors.add("คลิป ${clip.id} อ้าง asset ที่ไม่มี (${clip.assetId})")
                }
                if (clip.endMs <= clip.startMs || clip.startMs < 0) {
                    errors.add("คลิป ${clip.id} ช่วงเวลาไม่ถูก (${clip.startMs}..${clip.endMs})")
                }
                if (clip.atMs < 0) {
                    errors.add("คลิป ${clip.id} ตำแหน่งติดลบ (${clip.atMs})")
                }
                if (clip.volume !in 0..100) {
                    errors.add("คลิป ${clip.id} เสียงต้อง 0..100 (ได้ ${clip.volume})")
                }
                clip.transform?.validate()?.forEach { errors.add("คลิป ${clip.id}: $it") }
                clip.speed?.validate()?.forEach { errors.add("คลิป ${clip.id}: $it") }
            }
        }
        for (marker in markers) {
            if (marker.atMs < 0) {
                errors.add("มาร์กเกอร์ ${marker.id} ตำแหน่งติดลบ (${marker.atMs})")
            }
        }
        for (overlay in texts) {
            overlay.validate().forEach { errors.add("ข้อความ ${overlay.id}: $it") }
        }
        return errors
    }
}

/** A creative project: name + timeline + version counter (§23). */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val timeline: Timeline = Timeline(),
    val version: Int = 1,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
