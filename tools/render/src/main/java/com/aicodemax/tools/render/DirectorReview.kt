package com.aicodemax.tools.render

import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.Timeline

/**
 * CP-110: rule-based director — pre-render timeline audit with fix hints.
 * Honest heuristics (no ML): overlaps, black gaps, flash frames, unsafe text.
 */
enum class DirectorLevel { FAIL, WARN, PASS }

data class DirectorNote(
    val level: DirectorLevel,
    val rule: String,
    val message: String,
    val hint: String = "",
)

data class DirectorReport(
    val notes: List<DirectorNote>,
) {
    val verdict: DirectorLevel get() = when {
        notes.any { it.level == DirectorLevel.FAIL } -> DirectorLevel.FAIL
        notes.any { it.level == DirectorLevel.WARN } -> DirectorLevel.WARN
        else -> DirectorLevel.PASS
    }

    fun verdictText(): String = buildString {
        append(
            when (verdict) {
                DirectorLevel.FAIL -> "ผู้กำกับ: ไม่ผ่าน — แก้ก่อนเรนเดอร์\n"
                DirectorLevel.WARN -> "ผู้กำกับ: ผ่านแบบมีข้อควรระวัง\n"
                DirectorLevel.PASS -> "ผู้กำกับ: ผ่านฉลุย พร้อมเรนเดอร์\n"
            },
        )
        notes.forEach { note ->
            val mark = when (note.level) {
                DirectorLevel.FAIL -> "✗"
                DirectorLevel.WARN -> "!"
                DirectorLevel.PASS -> "✓"
            }
            append("$mark [${note.rule}] ${note.message}")
            if (note.hint.isNotEmpty()) append(" (แก้: ${note.hint})")
            append('\n')
        }
    }.trimEnd('\n')
}

object DirectorReview {
    fun review(timeline: Timeline): DirectorReport {
        val notes = mutableListOf<DirectorNote>()
        val clips = timeline.orderedClips()
        if (clips.isEmpty()) {
            notes += DirectorNote(DirectorLevel.FAIL, "empty", "ไม่มีคลิปเลย", "เพิ่มคลิปก่อน (timeline.addClip)")
            return DirectorReport(notes)
        }
        // Overlaps per track (video/image tracks; audio overlaps are mixes).
        for (track in timeline.tracks) {
            if (track.kind != MediaKind.VIDEO && track.kind != MediaKind.IMAGE) continue
            val sorted = track.clips.sortedBy { it.atMs }
            for (i in 0 until sorted.size - 1) {
                val a = sorted[i]
                val b = sorted[i + 1]
                val aEnd = a.atMs + a.outputDurationMs()
                if (aEnd > b.atMs + 50) {
                    notes += DirectorNote(
                        DirectorLevel.FAIL, "overlap",
                        "แทร็ก ${track.id}: คลิป ${a.id} ทับ ${b.id} ${aEnd - b.atMs}ms",
                        "ขยับคลิปหลังไปที่ $aEnd หรือทริมคลิปหน้า",
                    )
                }
            }
        }
        // Black gaps on the primary video track.
        val primary = timeline.tracks.firstOrNull { it.kind == MediaKind.VIDEO && !it.hidden }
        if (primary != null) {
            val sorted = primary.clips.sortedBy { it.atMs }
            for (i in 0 until sorted.size - 1) {
                val gap = sorted[i + 1].atMs - (sorted[i].atMs + sorted[i].outputDurationMs())
                if (gap > 500) {
                    notes += DirectorNote(
                        DirectorLevel.WARN, "gap",
                        "ช่องว่างดำ ${gap}ms ที่ ${sorted[i].atMs + sorted[i].outputDurationMs()}ms",
                        "ขยับคลิปชิดกันหรือใส่ B-roll/ข้อความคั่น",
                    )
                }
            }
            if (sorted.isNotEmpty() && sorted[0].atMs > 500) {
                notes += DirectorNote(
                    DirectorLevel.WARN, "gap",
                    "เริ่มด้วยจอดำ ${sorted[0].atMs}ms",
                    "ขยับคลิปแรกมาที่ 0",
                )
            }
        } else if (timeline.tracks.none { it.kind == MediaKind.IMAGE && it.clips.isNotEmpty() }) {
            notes += DirectorNote(DirectorLevel.WARN, "novideo", "ไม่มีแทร็กวิดีโอ (ได้เสียง+ข้อความอย่างเดียว)", "เพิ่มคลิปวิดีโอหรือภาพ")
        }
        // Flash frames.
        for ((track, clip) in clips) {
            if (clip.outputDurationMs() in 1..299) {
                notes += DirectorNote(
                    DirectorLevel.WARN, "flash",
                    "คลิป ${clip.id} สั้น ${clip.outputDurationMs()}ms (กระพริบ)",
                    "ยืดคลิปให้เกิน 300ms หรือลบออก",
                )
            }
        }
        // Unsafe / overlong text.
        val duration = timeline.durationMs
        for (overlay in timeline.texts) {
            if (overlay.xPct !in 5..95 || overlay.yPct !in 5..95) {
                notes += DirectorNote(
                    DirectorLevel.WARN, "safe",
                    "ข้อความ \"${overlay.text.take(20)}\" อยู่นอกเขตปลอดภัย (${overlay.xPct},${overlay.yPct})",
                    "ขยับเข้า 5..95%",
                )
            }
            if (overlay.endMs > duration) {
                notes += DirectorNote(
                    DirectorLevel.WARN, "safe",
                    "ข้อความ \"${overlay.text.take(20)}\" เลยความยาวไทม์ไลน์",
                    "จบข้อความที่ ${duration}ms",
                )
            }
            if (overlay.text.length > 60) {
                notes += DirectorNote(
                    DirectorLevel.WARN, "read",
                    "ข้อความยาว ${overlay.text.length} ตัวอักษร (อ่านไม่ทัน)",
                    "ตัดเหลือไม่เกิน 60 ตัวอักษรหรือแยก 2 บรรทัด",
                )
            }
        }
        if (notes.isEmpty()) {
            notes += DirectorNote(DirectorLevel.PASS, "ok", "${clips.size} คลิป ${timeline.texts.size} ข้อความ ไม่มีปัญหา")
        }
        return DirectorReport(notes)
    }
}
