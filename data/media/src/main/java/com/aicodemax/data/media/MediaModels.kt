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
) {
    val durationMs: Long get() = endMs - startMs
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

@Serializable
data class Timeline(
    val tracks: List<Track> = emptyList(),
    val markers: List<TimelineMarker> = emptyList(),
) {
    val durationMs: Long get() = tracks.flatMap { it.clips }.maxOfOrNull { it.atMs + it.durationMs } ?: 0

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
            }
        }
        for (marker in markers) {
            if (marker.atMs < 0) {
                errors.add("มาร์กเกอร์ ${marker.id} ตำแหน่งติดลบ (${marker.atMs})")
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
