package com.aicodemax.data.media

/**
 * CP-72 pure timeline operations (§11). Each returns a new [Timeline];
 * failures throw [IllegalArgumentException] with a Thai message (the port
 * turns them into honest tool errors inside a rolled-back transaction).
 */
object TimelineOps {
    fun split(timeline: Timeline, clipId: String, atTimelineMs: Long, newId: String): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        val start = clip.atMs
        val end = clip.atMs + clip.outputDurationMs()
        if (atTimelineMs <= start || atTimelineMs >= end) {
            throw IllegalArgumentException("จุดแยกต้องอยู่ระหว่าง ${start}..${end}ms")
        }
        val cut = clip.startMs + clip.outputToSource(atTimelineMs - start)
        val leftLen = atTimelineMs - start
        val left = clip.copy(endMs = cut, keyframes = clip.keyframes?.splitAt(leftLen)?.first)
        val right = clip.copy(id = newId, startMs = cut, atMs = atTimelineMs, keyframes = clip.keyframes?.splitAt(leftLen)?.second)
        return timeline.replaceClips(track.id, track.clips.flatMap { if (it.id == clipId) listOf(left, right) else listOf(it) })
    }

    fun trim(
        timeline: Timeline,
        clipId: String,
        startMs: Long?,
        endMs: Long?,
        atMs: Long?,
        maxEndMs: Long? = null,
    ): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        val next = clip.copy(
            startMs = startMs ?: clip.startMs,
            endMs = endMs ?: clip.endMs,
            atMs = atMs ?: clip.atMs,
        )
        if (next.startMs < 0 || next.atMs < 0) throw IllegalArgumentException("เวลาเริ่ม/ตำแหน่งติดลบไม่ได้")
        if (next.endMs <= next.startMs) throw IllegalArgumentException("จุดจบต้องหลังจุดเริ่ม")
        if (maxEndMs != null && next.endMs > maxEndMs) {
            throw IllegalArgumentException("ไฟล์ยาวแค่ ${maxEndMs}ms")
        }
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) next else it })
    }

    fun move(
        timeline: Timeline,
        clipId: String,
        toAtMs: Long,
        toTrack: String?,
        assetKind: MediaKind,
    ): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (toAtMs < 0) throw IllegalArgumentException("ตำแหน่งติดลบไม่ได้")
        if (toTrack == null || toTrack == track.id) {
            return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) it.copy(atMs = toAtMs) else it })
        }
        val dest = timeline.tracks.firstOrNull { it.id == toTrack }
            ?: throw IllegalArgumentException("ไม่มีแทร็ก $toTrack")
        checkUnlocked(dest)
        if (dest.kind != assetKind) {
            throw IllegalArgumentException("ย้ายข้ามชนิดไม่ได้ (${track.kind}→${dest.kind})")
        }
        val moved = clip.copy(atMs = toAtMs)
        return timeline.copy(
            tracks = timeline.tracks.map {
                when (it.id) {
                    track.id -> it.copy(clips = it.clips.filter { c -> c.id != clipId })
                    toTrack -> it.copy(clips = it.clips + moved)
                    else -> it
                }
            },
        )
    }

    fun delete(timeline: Timeline, clipId: String): Timeline {
        val (track, _) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        return timeline.replaceClips(track.id, track.clips.filter { it.id != clipId })
    }

    fun duplicate(timeline: Timeline, clipId: String, atMs: Long?, newId: String): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        val pos = atMs ?: (clip.atMs + clip.outputDurationMs())
        if (pos < 0) throw IllegalArgumentException("ตำแหน่งติดลบไม่ได้")
        return timeline.replaceClips(track.id, track.clips + clip.copy(id = newId, atMs = pos))
    }

    fun addMarker(timeline: Timeline, marker: TimelineMarker): Timeline {
        if (marker.atMs < 0) throw IllegalArgumentException("มาร์กเกอร์ตำแหน่งติดลบไม่ได้")
        return timeline.copy(markers = (timeline.markers + marker).sortedBy { it.atMs })
    }

    fun removeMarker(timeline: Timeline, markerId: String): Timeline {
        if (timeline.markers.none { it.id == markerId }) {
            throw IllegalArgumentException("ไม่มีมาร์กเกอร์ $markerId")
        }
        return timeline.copy(markers = timeline.markers.filter { it.id != markerId })
    }

    fun trackFlags(
        timeline: Timeline,
        trackId: String,
        locked: Boolean?,
        muted: Boolean?,
        hidden: Boolean?,
        color: String?,
    ): Timeline {
        val track = timeline.tracks.firstOrNull { it.id == trackId }
            ?: throw IllegalArgumentException("ไม่มีแทร็ก $trackId")
        val next = track.copy(
            locked = locked ?: track.locked,
            muted = muted ?: track.muted,
            hidden = hidden ?: track.hidden,
            color = color ?: track.color,
        )
        return timeline.copy(tracks = timeline.tracks.map { if (it.id == trackId) next else it })
    }

    /** CP-73: replaces the clip's visual transform (§12). Identity clears to null. */
    fun transform(timeline: Timeline, clipId: String, transform: ClipTransform): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (track.kind == MediaKind.AUDIO) {
            throw IllegalArgumentException("คลิปเสียงใช้ transform ภาพไม่ได้")
        }
        val problems = transform.validate()
        if (problems.isNotEmpty()) {
            throw IllegalArgumentException(problems.joinToString("; "))
        }
        val next = if (transform.isIdentity) null else transform
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) clip.copy(transform = next) else it })
    }

    /**
     * CP-73: freeze support — splits [clipId] at [atTimelineMs], ripples the
     * right part + later same-track clips by [holdMs], inserts [still].
     */
    fun insertHold(
        timeline: Timeline,
        clipId: String,
        atTimelineMs: Long,
        holdMs: Long,
        still: Clip,
    ): Timeline {
        if (holdMs < 100 || holdMs > 30_000) {
            throw IllegalArgumentException("ฟรีซได้ครั้งละ 100..30000ms (ได้ $holdMs)")
        }
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        val start = clip.atMs
        val end = clip.atMs + clip.outputDurationMs()
        if (atTimelineMs < start || atTimelineMs > end) {
            throw IllegalArgumentException("จุดฟรีซต้องอยู่ระหว่าง ${start}..${end}ms")
        }
        val cut = clip.startMs + clip.outputToSource(atTimelineMs - start)
        // Edge-exact freezes keep only the non-empty side (no zero-length clips).
        val left = if (atTimelineMs > start) listOf(clip.copy(endMs = cut)) else emptyList()
        val right = if (atTimelineMs < end) {
            listOf(clip.copy(id = still.id + "_r", startMs = cut, atMs = atTimelineMs + holdMs))
        } else {
            emptyList()
        }
        val moved = track.clips.flatMap {
            when {
                it.id == clipId -> left + right
                it.atMs >= atTimelineMs -> listOf(it.copy(atMs = it.atMs + holdMs))
                else -> listOf(it)
            }
        }
        val withStill = (moved + still.copy(atMs = atTimelineMs)).sortedBy { it.atMs }
        return timeline.replaceClips(track.id, withStill)
    }

    /** CP-74 text overlays (§22): texts live on the timeline, not on tracks. */
    fun addText(timeline: Timeline, overlay: OverlayText): Timeline {
        val problems = overlay.validate()
        if (problems.isNotEmpty()) {
            throw IllegalArgumentException(problems.joinToString("; "))
        }
        if (timeline.texts.any { it.id == overlay.id }) {
            throw IllegalArgumentException("มีข้อความ ${overlay.id} แล้ว")
        }
        return timeline.copy(texts = (timeline.texts + overlay).sortedBy { it.startMs })
    }

    fun updateText(timeline: Timeline, id: String, patch: (OverlayText) -> OverlayText): Timeline {
        val current = timeline.texts.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("ไม่มีข้อความ $id")
        val next = patch(current).copy(id = id)
        val problems = next.validate()
        if (problems.isNotEmpty()) {
            throw IllegalArgumentException(problems.joinToString("; "))
        }
        return timeline.copy(texts = timeline.texts.map { if (it.id == id) next else it }.sortedBy { it.startMs })
    }

    fun removeText(timeline: Timeline, id: String): Timeline {
        if (timeline.texts.none { it.id == id }) {
            throw IllegalArgumentException("ไม่มีข้อความ $id")
        }
        return timeline.copy(texts = timeline.texts.filter { it.id != id })
    }

    /** CP-75: replaces the clip's playback speed (§13). Identity clears to null. */
    fun speed(timeline: Timeline, clipId: String, speed: ClipSpeed): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        val problems = speed.validate()
        if (problems.isNotEmpty()) {
            throw IllegalArgumentException(problems.joinToString("; "))
        }
        val next = if (speed.isIdentity) null else speed
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) clip.copy(speed = next) else it })
    }

    /** CP-76: adds/replaces one key point on a clip property (§14). */
    fun setKeyframe(
        timeline: Timeline,
        clipId: String,
        prop: String,
        atMs: Long,
        value: Float,
        ease: String = "linear",
    ): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (prop !in ClipKeyframes.PROPS) throw IllegalArgumentException("property ไม่รู้จัก ($prop)")
        if (ease !in ClipKeyframes.EASES) throw IllegalArgumentException("ease ไม่รู้จัก ($ease)")
        if (track.kind == MediaKind.AUDIO && prop != "volume") {
            throw IllegalArgumentException("คลิปเสียงใช้ keyframe ภาพไม่ได้")
        }
        if (atMs < 0) throw IllegalArgumentException("เวลา keyframe ติดลบไม่ได้")
        val outLen = clip.outputDurationMs()
        if (atMs > outLen) throw IllegalArgumentException("keyframe เกินความยาวคลิป (${outLen}ms)")
        val range = ClipKeyframes.RANGES[prop]!!
        if (value < range.first || value > range.second) {
            throw IllegalArgumentException("$prop ค่าต้องอยู่ ${range.first}..${range.second}")
        }
        val keys = clip.keyframes ?: ClipKeyframes()
        val next = (keys.points(prop).filterNot { it.atMs == atMs } +
            KeyPoint(atMs, value, ease)).sortedBy { it.atMs }
        if (next.size > 64) throw IllegalArgumentException("$prop มีคีย์เกิน 64 จุด")
        val done = keys.withPoints(prop, next)
        val problems = done.validate()
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) clip.copy(keyframes = done) else it })
    }

    /** CP-76: deletes the key point nearest [atMs] (within 120ms). */
    fun removeKeyframe(timeline: Timeline, clipId: String, prop: String, atMs: Long): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (prop !in ClipKeyframes.PROPS) throw IllegalArgumentException("property ไม่รู้จัก ($prop)")
        val keys = clip.keyframes ?: throw IllegalArgumentException("คลิปนี้ไม่มี keyframe")
        val near = keys.points(prop).minByOrNull { kotlin.math.abs(it.atMs - atMs) }
            ?: throw IllegalArgumentException("$prop ไม่มีคีย์ให้ลบ")
        if (kotlin.math.abs(near.atMs - atMs) > 120) {
            throw IllegalArgumentException("ไม่พบคีย์ใกล้ ${atMs}ms")
        }
        val done = keys.withPoints(prop, keys.points(prop).filterNot { it === near })
        val next = done.takeUnless { it.isEmpty }
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) clip.copy(keyframes = next) else it })
    }

    /** CP-76: clears all keys of [prop], or every property when null. */
    fun clearKeyframes(timeline: Timeline, clipId: String, prop: String? = null): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        val keys = clip.keyframes ?: return timeline
        val next = if (prop == null) {
            null
        } else {
            if (prop !in ClipKeyframes.PROPS) throw IllegalArgumentException("property ไม่รู้จัก ($prop)")
            keys.withPoints(prop, emptyList()).takeUnless { it.isEmpty }
        }
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) clip.copy(keyframes = next) else it })
    }

    /** CP-76: splits output-relative keyframes at [leftLen] into (left, right-shifted). */
    private fun ClipKeyframes.splitAt(leftLen: Long): Pair<ClipKeyframes?, ClipKeyframes?> {
        var left = ClipKeyframes()
        var right = ClipKeyframes()
        for (prop in ClipKeyframes.PROPS) {
            left = left.withPoints(prop, points(prop).filter { it.atMs < leftLen })
            right = right.withPoints(
                prop,
                points(prop).filter { it.atMs >= leftLen }.map { it.copy(atMs = it.atMs - leftLen) },
            )
        }
        return left.takeUnless { it.isEmpty } to right.takeUnless { it.isEmpty }
    }

    private fun checkUnlocked(track: Track) {
        if (track.locked) throw IllegalArgumentException("แทร็ก ${track.id} ล็อกอยู่")
    }

    private fun Timeline.replaceClips(trackId: String, clips: List<Clip>): Timeline =
        copy(tracks = tracks.map { if (it.id == trackId) it.copy(clips = clips) else it })
}
