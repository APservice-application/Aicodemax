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
        val end = clip.atMs + clip.durationMs
        if (atTimelineMs <= start || atTimelineMs >= end) {
            throw IllegalArgumentException("จุดแยกต้องอยู่ระหว่าง ${start}..${end}ms")
        }
        val cut = clip.startMs + (atTimelineMs - start)
        val left = clip.copy(endMs = cut)
        val right = clip.copy(id = newId, startMs = cut, atMs = atTimelineMs)
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
        val pos = atMs ?: (clip.atMs + clip.durationMs)
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

    private fun checkUnlocked(track: Track) {
        if (track.locked) throw IllegalArgumentException("แทร็ก ${track.id} ล็อกอยู่")
    }

    private fun Timeline.replaceClips(trackId: String, clips: List<Clip>): Timeline =
        copy(tracks = tracks.map { if (it.id == trackId) it.copy(clips = clips) else it })
}
