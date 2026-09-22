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
) {
    val durationMs: Long get() = endMs - startMs
}

/** A single lane of clips (V1/A1/...). Overlaps are allowed (top clip wins). */
@Serializable
data class Track(
    val id: String,
    val kind: MediaKind,
    val clips: List<Clip> = emptyList(),
)

/**
 * Timeline — the source of truth every plan/render reads (§9).
 * Pure data + validation; rendering lives in CP-67.
 */
@Serializable
data class Timeline(
    val tracks: List<Track> = emptyList(),
) {
    val durationMs: Long get() = tracks.flatMap { it.clips }.maxOfOrNull { it.atMs + it.durationMs } ?: 0

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
