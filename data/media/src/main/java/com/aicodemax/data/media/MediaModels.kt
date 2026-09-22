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

/** CP-76 one keyframe: output-relative ms → value + easing into the NEXT point. */
@Serializable
data class KeyPoint(
    val atMs: Long,
    val value: Float,
    /** linear/easein/easeout/easeinout/bezier. */
    val ease: String = "linear",
    /** Cubic-bezier control points (used when ease=bezier). */
    val c1x: Float = 0.25f,
    val c1y: Float = 0.1f,
    val c2x: Float = 0.25f,
    val c2y: Float = 1f,
)

/**
 * CP-76 animated clip properties (§14). Times are OUTPUT-relative ms
 * (stable when speed changes). A property with no points uses the base
 * [ClipTransform]/volume value; one point = hold that value.
 */
@Serializable
data class ClipKeyframes(
    val posX: List<KeyPoint> = emptyList(),
    val posY: List<KeyPoint> = emptyList(),
    val scale: List<KeyPoint> = emptyList(),
    val rotation: List<KeyPoint> = emptyList(),
    val opacity: List<KeyPoint> = emptyList(),
    val volume: List<KeyPoint> = emptyList(),
    val cropX: List<KeyPoint> = emptyList(),
    val cropY: List<KeyPoint> = emptyList(),
    val cropW: List<KeyPoint> = emptyList(),
    val cropH: List<KeyPoint> = emptyList(),
) {
    val isEmpty: Boolean get() = count() == 0

    fun count(): Int = posX.size + posY.size + scale.size + rotation.size + opacity.size +
        volume.size + cropX.size + cropY.size + cropW.size + cropH.size

    fun summary(): String = PROPS.mapNotNull { prop ->
        val n = points(prop).size
        if (n > 0) "$prop×$n" else null
    }.joinToString(" ")

    fun points(prop: String): List<KeyPoint> = when (prop) {
        "posX" -> posX
        "posY" -> posY
        "scale" -> scale
        "rotation" -> rotation
        "opacity" -> opacity
        "volume" -> volume
        "cropX" -> cropX
        "cropY" -> cropY
        "cropW" -> cropW
        "cropH" -> cropH
        else -> emptyList()
    }

    fun withPoints(prop: String, next: List<KeyPoint>): ClipKeyframes = when (prop) {
        "posX" -> copy(posX = next)
        "posY" -> copy(posY = next)
        "scale" -> copy(scale = next)
        "rotation" -> copy(rotation = next)
        "opacity" -> copy(opacity = next)
        "volume" -> copy(volume = next)
        "cropX" -> copy(cropX = next)
        "cropY" -> copy(cropY = next)
        "cropW" -> copy(cropW = next)
        "cropH" -> copy(cropH = next)
        else -> this
    }

    /** Sampled value of [prop] at output-relative [atMs] (graph interpolation). */
    fun valueAt(prop: String, atMs: Long): Float? {
        val pts = points(prop).sortedBy { it.atMs }
        if (pts.isEmpty()) return null
        if (atMs <= pts.first().atMs) return pts.first().value
        if (atMs >= pts.last().atMs) return pts.last().value
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (atMs in a.atMs until b.atMs) {
                val span = (b.atMs - a.atMs).coerceAtLeast(1)
                val f = ((atMs - a.atMs).toFloat() / span).coerceIn(0f, 1f)
                return a.value + (b.value - a.value) * Easing.apply(a, f)
            }
        }
        return pts.last().value
    }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        for (prop in PROPS) {
            val pts = points(prop)
            if (pts.size > 64) errors.add("$prop มีคีย์เกิน 64 จุด")
            if (pts.any { it.atMs < 0 }) errors.add("$prop เวลาติดลบไม่ได้")
            for (p in pts) {
                if (p.ease !in EASES) errors.add("$prop ease ไม่รู้จัก (${p.ease})")
                val range = RANGES[prop]!!
                if (p.value < range.first || p.value > range.second) {
                    errors.add("$prop ค่าต้องอยู่ ${range.first}..${range.second} (ได้ ${p.value})")
                }
            }
        }
        return errors
    }

    companion object {
        val PROPS = listOf("posX", "posY", "scale", "rotation", "opacity", "volume", "cropX", "cropY", "cropW", "cropH")
        val EASES = setOf("linear", "easein", "easeout", "easeinout", "bezier")

        /** Value ranges per property (same units as base transform). */
        val RANGES = mapOf(
            "posX" to (-4000f to 4000f),
            "posY" to (-4000f to 4000f),
            "scale" to (1f to 400f),
            "rotation" to (-180f to 180f),
            "opacity" to (0f to 100f),
            "volume" to (0f to 100f),
            "cropX" to (0f to 100f),
            "cropY" to (0f to 100f),
            "cropW" to (1f to 100f),
            "cropH" to (1f to 100f),
        )
    }
}

/** CP-76 easing curves (§14 graph editor data). */
object Easing {
    fun apply(point: KeyPoint, f: Float): Float {
        val x = f.coerceIn(0f, 1f)
        return when (point.ease) {
            "easein" -> x * x
            "easeout" -> 1 - (1 - x) * (1 - x)
            "easeinout" -> if (x < 0.5f) 2 * x * x else 1 - (-2 * x + 2) * (-2 * x + 2) / 2
            "bezier" -> cubic(point.c1x, point.c1y, point.c2x, point.c2y, x)
            else -> x
        }
    }

    /** Cubic-bezier y(x) via Newton iterations (graph-editor compatible). */
    fun cubic(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float): Float {
        var t = x.coerceIn(0f, 1f)
        repeat(5) {
            val cx = 3 * c1x * t * (1 - t) * (1 - t) + 3 * c2x * t * t * (1 - t) + t * t * t
            val dx = 3 * c1x * (1 - t) * (1 - t) + 6 * (c2x - c1x) * t * (1 - t) + 3 * (1 - c2x) * t * t
            if (dx == 0f) return@repeat
            t = (t - (cx - x) / dx).coerceIn(0f, 1f)
        }
        return 3 * c1y * t * (1 - t) * (1 - t) + 3 * c2y * t * t * (1 - t) + t * t * t
    }
}

/** CP-77 transition on one clip edge (§21). v0 renders inside clip bounds (no overlap). */
@Serializable
data class ClipTransition(
    val kind: String = "fade",
    val durationMs: Long = 500,
) {
    fun validate(edge: String): List<String> {
        val errors = mutableListOf<String>()
        val allowed = if (edge == "out") OUT_KINDS else IN_KINDS
        if (kind !in allowed) errors.add("ทรานซิชันขา$edge ใช้ได้แค่ ${allowed.joinToString("/")}")
        if (durationMs !in 100..2000) errors.add("ทรานซิชันยาว 100..2000ms (ได้ $durationMs)")
        return errors
    }

    fun summary(): String = "$kind$durationMs"

    companion object {
        val IN_KINDS = listOf("cut", "fade", "dissolve", "wipeleft", "wiperight", "wipeup", "wipedown")
        val OUT_KINDS = listOf("cut", "fade")
    }
}

/** CP-77 basic per-clip image effects (§20). All zero = off. */
@Serializable
data class ClipFx(
    val blur: Int = 0,
    val vignette: Int = 0,
    val grain: Int = 0,
) {
    val isIdentity: Boolean get() = blur == 0 && vignette == 0 && grain == 0

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (blur !in 0..10) errors.add("blur ต้องอยู่ 0..10 (ได้ $blur)")
        if (vignette !in 0..100) errors.add("vignette ต้องอยู่ 0..100 (ได้ $vignette)")
        if (grain !in 0..100) errors.add("grain ต้องอยู่ 0..100 (ได้ $grain)")
        return errors
    }

    fun summary(): String = buildList {
        if (blur > 0) add("b$blur")
        if (vignette > 0) add("v$vignette")
        if (grain > 0) add("g$grain")
    }.joinToString("/")
}

/** CP-78/81 per-clip color correction (§42 Basic complete + HSL shifts). All zero = identity. */
@Serializable
data class ClipColor(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val temperature: Int = 0,
    val tint: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    val hueShift: Int = 0,
    val lightness: Int = 0,
    /** CP-81 §42: exposure ±100 → gain 2^(v/100) (0.5x..2x). */
    val exposure: Int = 0,
    /** CP-81 §42: white point push on near-white zone. */
    val whites: Int = 0,
    /** CP-81 §42: black point lift/crush on near-black zone. */
    val blacks: Int = 0,
) {
    val isIdentity: Boolean get() = this == ClipColor()

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        fun range(name: String, v: Int, lo: Int, hi: Int) {
            if (v !in lo..hi) errors.add("$name ต้องอยู่ $lo..$hi (ได้ $v)")
        }
        range("brightness", brightness, -100, 100)
        range("contrast", contrast, -100, 100)
        range("saturation", saturation, -100, 100)
        range("temperature", temperature, -100, 100)
        range("tint", tint, -100, 100)
        range("highlights", highlights, -100, 100)
        range("shadows", shadows, -100, 100)
        range("hueShift", hueShift, -180, 180)
        range("lightness", lightness, -100, 100)
        range("exposure", exposure, -100, 100)
        range("whites", whites, -100, 100)
        range("blacks", blacks, -100, 100)
        return errors
    }

    fun summary(): String = buildList {
        if (brightness != 0) add("br$brightness")
        if (contrast != 0) add("ct$contrast")
        if (saturation != 0) add("st$saturation")
        if (temperature != 0) add("tp$temperature")
        if (tint != 0) add("tn$tint")
        if (highlights != 0) add("hi$highlights")
        if (shadows != 0) add("sh$shadows")
        if (hueShift != 0) add("hu$hueShift")
        if (lightness != 0) add("li$lightness")
        if (exposure != 0) add("ex$exposure")
        if (whites != 0) add("wh$whites")
        if (blacks != 0) add("bl$blacks")
    }.joinToString(" ")

    companion object {
        val PRESETS = listOf("none", "cinema", "warm", "cool", "vivid", "bw")

        fun preset(name: String): ClipColor = when (name) {
            "cinema" -> ClipColor(contrast = 15, saturation = 10, shadows = 8, temperature = 5, blacks = -6)
            "warm" -> ClipColor(temperature = 30, tint = 5)
            "cool" -> ClipColor(temperature = -30)
            "vivid" -> ClipColor(saturation = 30, contrast = 10)
            "bw" -> ClipColor(saturation = -100)
            else -> ClipColor()
        }
    }
}

/** CP-81 §42 LUT: .cube file applied after [ClipColor]. strength 0..100 mixes graded↔lut. */
@Serializable
data class ClipLut(
    val path: String,
    val strength: Int = 100,
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (path.isBlank()) errors.add("lut path ว่างไม่ได้")
        if (strength !in 0..100) errors.add("lut strength ต้องอยู่ 0..100 (ได้ $strength)")
        return errors
    }

    fun summary(): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        return "LUT:$name@$strength%"
    }
}

/** CP-84 §45 Ken Burns motion: slow zoom/pan over the clip. zoom = extra % at the far end. */
@Serializable
data class ClipMotion(
    val direction: String = "in",
    val zoom: Int = 20,
) {
    val isIdentity: Boolean get() = zoom == 0

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (direction !in DIRS) errors.add("ทิศต้องเป็น ${DIRS.joinToString("/")} (ได้ $direction)")
        if (zoom !in 0..60) errors.add("ซูมต้องอยู่ 0..60 (ได้ $zoom)")
        return errors
    }

    fun summary(): String = "KB:$direction+$zoom"

    companion object {
        val DIRS = listOf("in", "out", "left", "right", "up", "down")
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
    /** Animated properties (null/empty = static base values). */
    val keyframes: ClipKeyframes? = null,
    /** CP-77 how this clip enters (needs same-track predecessor for dissolve/wipe). */
    val transitionIn: ClipTransition? = null,
    /** CP-77 how this clip exits (fade to black; cut = hard). */
    val transitionOut: ClipTransition? = null,
    /** CP-77 basic image effects (null/identity = off). */
    val fx: ClipFx? = null,
    /** CP-78 color correction (null/identity = off). */
    val color: ClipColor? = null,
    /** CP-79 shape mask (null/identity = off). */
    val mask: ClipMask? = null,
    /** CP-79 chroma key (null = off). */
    val chroma: ClipChroma? = null,
    /** CP-81 .cube LUT (null = off). Applied after [color]. */
    val lut: ClipLut? = null,
    /** CP-84 Ken Burns motion (null/identity = static). */
    val motion: ClipMotion? = null,
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

/** CP-80 one tracked offset: output-relative ms + % of frame (resolution-free). */
@Serializable
data class TrackPoint(
    val atMs: Long,
    val dx: Float,
    val dy: Float,
)

/**
 * CP-80 motion path from template tracking (§15). Times are output-relative
 * to the analyzed clip; offsets are % of frame (dx: % of width, dy: % of height).
 */
@Serializable
data class TrackPath(
    val points: List<TrackPoint> = emptyList(),
) {
    val isEmpty: Boolean get() = points.isEmpty()

    /** Linear-interpolated offset at [atMs] (clamped to ends). */
    fun offsetAt(atMs: Long): Pair<Float, Float> {
        if (points.isEmpty()) return 0f to 0f
        val pts = points.sortedBy { it.atMs }
        if (atMs <= pts.first().atMs) return pts.first().dx to pts.first().dy
        if (atMs >= pts.last().atMs) return pts.last().dx to pts.last().dy
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (atMs in a.atMs until b.atMs) {
                val span = (b.atMs - a.atMs).coerceAtLeast(1)
                val f = (atMs - a.atMs).toFloat() / span
                return (a.dx + (b.dx - a.dx) * f) to (a.dy + (b.dy - a.dy) * f)
            }
        }
        return pts.last().dx to pts.last().dy
    }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (points.size > 64) errors.add("track มีจุดเกิน 64")
        if (points.any { it.atMs < 0 }) errors.add("track เวลาติดลบไม่ได้")
        if (points.any { it.dx !in -100f..100f || it.dy !in -100f..100f }) {
            errors.add("track offset ต้องอยู่ ±100%")
        }
        return errors
    }

    fun summary(): String {
        if (points.isEmpty()) return "ว่าง"
        val peak = points.maxOf { kotlin.math.hypot(it.dx, it.dy) }
        return "${points.size} จุด ขยับสูงสุด %.1f%%".format(peak)
    }
}

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
    /** CP-80 follow path (added to x/y % at render). */
    val follow: TrackPath? = null,
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
        follow?.validate()?.forEach { errors.add("follow: $it") }
        return errors
    }

    fun summary(): String = "\"$text\" $startMs..$endMs" + if (animIn != "none") " +$animIn" else "" + if (follow != null && !follow.isEmpty) " +follow" else ""

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

/** CP-79 per-clip shape mask (§17). Rect/ellipse v0; pen-path later. Percent units. */
@Serializable
data class ClipMask(
    val shape: String = "rect",
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 100,
    val h: Int = 100,
    val feather: Int = 0,
    val invert: Boolean = false,
) {
    /** Full-frame non-inverted mask = identity (nothing cut). */
    val isIdentity: Boolean get() =
        shape != "ellipse" && x <= 0 && y <= 0 && w >= 100 && h >= 100 && !invert

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (shape != "rect" && shape != "ellipse") errors.add("mask shape ต้องเป็น rect/ellipse (ได้ $shape)")
        if (x !in 0..100 || y !in 0..100) errors.add("mask x/y ต้องอยู่ 0..100")
        if (w !in 1..100 || h !in 1..100) errors.add("mask w/h ต้องอยู่ 1..100")
        if (feather !in 0..100) errors.add("mask feather ต้องอยู่ 0..100 (ได้ $feather)")
        return errors
    }

    fun summary(): String =
        "$shape $x,$y ${w}x$h" + (if (feather > 0) " f$feather" else "") + (if (invert) " inv" else "")
}

/** CP-79 green/blue-screen key (§18). Greenness-distance key + despill. */
@Serializable
data class ClipChroma(
    /** Target hue in degrees (120 = green, 240 = blue). */
    val hue: Int = 120,
    /** 0..100 key threshold. */
    val tolerance: Int = 30,
    /** 0..100 edge softness. */
    val softness: Int = 10,
    /** 0..100 spill suppression on kept pixels. */
    val despill: Int = 50,
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (hue !in 0..360) errors.add("chroma hue ต้องอยู่ 0..360 (ได้ $hue)")
        if (tolerance !in 0..100) errors.add("chroma tolerance ต้องอยู่ 0..100 (ได้ $tolerance)")
        if (softness !in 0..100) errors.add("chroma softness ต้องอยู่ 0..100 (ได้ $softness)")
        if (despill !in 0..100) errors.add("chroma despill ต้องอยู่ 0..100 (ได้ $despill)")
        return errors
    }

    fun summary(): String = "h$hue t$tolerance" +
        (if (softness != 10) " s$softness" else "") + (if (despill != 50) " d$despill" else "")
}

/**
 * CP-79 timeline background (§19). v0 shows through only where a clip's
 * mask/chroma cuts holes (no fit-mode yet, so the frame stays fill).
 */
@Serializable
data class ClipBackground(
    val mode: String = "black",
    /** RGB hex "RRGGBB" when mode=color. */
    val color: String = "000000",
    /** Blur radius 1..10 when mode=blur. */
    val blur: Int = 8,
    /** Asset id when mode=image. */
    val assetId: String? = null,
) {
    val isIdentity: Boolean get() = mode == "black"

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (mode !in MODES) errors.add("background mode ต้องเป็น ${MODES.joinToString("/")} (ได้ $mode)")
        if (mode == "color" && !color.matches(Regex("[0-9a-fA-F]{6}"))) {
            errors.add("background color ต้องเป็น hex RRGGBB (ได้ $color)")
        }
        if (mode == "blur" && blur !in 1..10) errors.add("background blur ต้องอยู่ 1..10 (ได้ $blur)")
        if (mode == "image" && assetId.isNullOrBlank()) errors.add("background image ต้องระบุ assetId")
        return errors
    }

    fun summary(): String = when (mode) {
        "color" -> "color#$color"
        "blur" -> "blur$blur"
        "image" -> "image:${assetId ?: "?"}"
        else -> "black"
    }

    companion object {
        val MODES = listOf("black", "color", "blur", "image")
    }
}

@Serializable
data class Timeline(
    val tracks: List<Track> = emptyList(),
    val markers: List<TimelineMarker> = emptyList(),
    val texts: List<OverlayText> = emptyList(),
    /** CP-79 timeline background (§19). */
    val background: ClipBackground? = null,
    /** CP-88 canvas aspect override (§33): "" = auto, else 16:9/9:16/1:1/4:5. */
    val canvas: String = "",
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
                clip.keyframes?.validate()?.forEach { errors.add("คลิป ${clip.id}: $it") }
                clip.transitionIn?.validate("in")?.forEach { errors.add("คลิป ${clip.id}: $it") }
                clip.transitionOut?.validate("out")?.forEach { errors.add("คลิป ${clip.id}: $it") }
                clip.fx?.validate()?.forEach { errors.add("คลิป ${clip.id}: $it") }
                clip.color?.validate()?.forEach { errors.add("คลิป ${clip.id}: $it") }
                clip.lut?.validate()?.forEach { errors.add("คลิป ${clip.id}: $it") }
                clip.motion?.validate()?.forEach { errors.add("คลิป ${clip.id}: $it") }
                clip.mask?.validate()?.forEach { errors.add("คลิป ${clip.id}: $it") }
                clip.chroma?.validate()?.forEach { errors.add("คลิป ${clip.id}: $it") }
                for (tr in listOfNotNull(clip.transitionIn, clip.transitionOut)) {
                    if (tr.kind != "cut" && tr.durationMs > clip.outputDurationMs()) {
                        errors.add("คลิป ${clip.id}: ทรานซิชันยาวกว่าคลิป")
                    }
                }
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
        if (canvas.isNotEmpty() && canvas !in setOf("16:9", "9:16", "1:1", "4:5")) {
            errors.add("สัดส่วนแคนวาสไม่รองรับ ($canvas)")
        }
        background?.let { bg ->
            bg.validate().forEach { errors.add("พื้นหลัง: $it") }
            if (bg.mode == "image" && bg.assetId != null && bg.assetId !in assets) {
                errors.add("พื้นหลังอ้าง asset ที่ไม่มี (${bg.assetId})")
            }
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
