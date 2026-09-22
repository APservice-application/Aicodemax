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
        val left = clip.copy(endMs = cut, keyframes = clip.keyframes?.splitAt(leftLen)?.first, transitionOut = null)
        val right = clip.copy(id = newId, startMs = cut, atMs = atTimelineMs, keyframes = clip.keyframes?.splitAt(leftLen)?.second, transitionIn = null)
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

    /** CP-77: sets the transition on one clip edge (§21). kind=cut clears. */
    fun transition(
        timeline: Timeline,
        clipId: String,
        edge: String,
        kind: String,
        durationMs: Long = 500,
    ): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (edge != "in" && edge != "out") throw IllegalArgumentException("edge ต้องเป็น in/out")
        if (track.kind == MediaKind.AUDIO && kind != "fade" && kind != "cut") {
            throw IllegalArgumentException("คลิปเสียงใช้ได้แค่ fade/cut")
        }
        if (kind == "cut") return clearTransition(timeline, clipId, edge)
        val tr = ClipTransition(kind, durationMs)
        val problems = tr.validate(edge)
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
        if (durationMs > clip.outputDurationMs()) {
            throw IllegalArgumentException("ทรานซิชันยาวกว่าคลิป (${clip.outputDurationMs()}ms)")
        }
        val next = if (edge == "in") clip.copy(transitionIn = tr) else clip.copy(transitionOut = tr)
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) next else it })
    }

    /** CP-77: clears one (or both, when edge=null) transition edges. */
    fun clearTransition(timeline: Timeline, clipId: String, edge: String? = null): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (edge != null && edge != "in" && edge != "out") {
            throw IllegalArgumentException("edge ต้องเป็น in/out")
        }
        val next = clip.copy(
            transitionIn = if (edge == null || edge == "in") null else clip.transitionIn,
            transitionOut = if (edge == null || edge == "out") null else clip.transitionOut,
        )
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) next else it })
    }

    /** CP-77: replaces the clip's basic image effects (§20). Identity clears to null. */
    fun fx(timeline: Timeline, clipId: String, fx: ClipFx): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (track.kind == MediaKind.AUDIO) {
            throw IllegalArgumentException("คลิปเสียงใช้เอฟเฟกต์ภาพไม่ได้")
        }
        val problems = fx.validate()
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
        val next = if (fx.isIdentity) null else fx
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) clip.copy(fx = next) else it })
    }

    /** CP-78: replaces the clip's color correction (§42). Identity clears to null. */
    fun color(timeline: Timeline, clipId: String, color: ClipColor): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (track.kind == MediaKind.AUDIO) {
            throw IllegalArgumentException("คลิปเสียงใช้แก้สีไม่ได้")
        }
        val problems = color.validate()
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
        val next = if (color.isIdentity) null else color
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) clip.copy(color = next) else it })
    }

    /** CP-79: replaces the clip's shape mask (§17). Identity clears to null. */
    fun mask(timeline: Timeline, clipId: String, mask: ClipMask): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (track.kind == MediaKind.AUDIO) {
            throw IllegalArgumentException("คลิปเสียงใช้มาสก์ไม่ได้")
        }
        val problems = mask.validate()
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
        val next = if (mask.isIdentity) null else mask
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) clip.copy(mask = next) else it })
    }

    /** CP-79: replaces the clip's chroma key (§18). Null clears. */
    fun chroma(timeline: Timeline, clipId: String, chroma: ClipChroma?): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (track.kind == MediaKind.AUDIO) {
            throw IllegalArgumentException("คลิปเสียงใช้ chroma ไม่ได้")
        }
        if (chroma != null) {
            val problems = chroma.validate()
            if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
        }
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) clip.copy(chroma = chroma) else it })
    }

    /** CP-81: replaces the clip's .cube LUT (§42 Pro). Null clears. */
    fun lut(timeline: Timeline, clipId: String, lut: ClipLut?): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (track.kind == MediaKind.AUDIO) {
            throw IllegalArgumentException("คลิปเสียงใช้ LUT ไม่ได้")
        }
        if (lut != null) {
            val problems = lut.validate()
            if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
        }
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) clip.copy(lut = lut) else it })
    }

    /** CP-82 §51: instantiates a template timeline, filling slots with real asset ids. */
    fun fromTemplate(template: ProjectTemplate, replacements: Map<String, String>): Timeline {
        val problems = template.validate()
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
        val missing = template.slots.map { it.id }.filter { it !in replacements }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("ขาดมีเดียช่อง: ${missing.joinToString(", ")} (เช่น main=<assetId>)")
        }
        for ((slot, asset) in replacements) {
            if (template.slots.none { it.id == slot }) throw IllegalArgumentException("ไม่มีช่อง $slot ในเทมเพลต")
            if (asset.isBlank()) throw IllegalArgumentException("asset ของช่อง $slot ว่างไม่ได้")
        }
        val slotByClip = template.slots.associate { it.clipId to it.id }
        return template.timeline.copy(
            tracks = template.timeline.tracks.map { track ->
                track.copy(
                    clips = track.clips.map { clip ->
                        val slot = slotByClip[clip.id]
                        if (slot != null) clip.copy(assetId = replacements.getValue(slot)) else clip
                    },
                )
            },
        )
    }

    /** CP-84: replaces the clip's Ken Burns motion (§45). Null/identity clears. */
    fun motion(timeline: Timeline, clipId: String, motion: ClipMotion?): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (track.kind == MediaKind.AUDIO) {
            throw IllegalArgumentException("คลิปเสียงใช้โมชันไม่ได้")
        }
        if (motion != null) {
            val problems = motion.validate()
            if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
        }
        val next = if (motion == null || motion.isIdentity) null else motion
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) clip.copy(motion = next) else it })
    }

    /** CP-84 §45: appends an image slideshow to V1 (single undo entry at port level). */
    fun slideshow(
        timeline: Timeline,
        assetIds: List<String>,
        stillMs: Long,
        fadeMs: Long,
        ids: List<String>,
    ): Timeline {
        if (assetIds.isEmpty()) throw IllegalArgumentException("สไลด์โชว์ต้องมีรูปอย่างน้อย 1 รูป")
        if (assetIds.size > 50) throw IllegalArgumentException("สไลด์โชว์มากสุด 50 รูป (ได้ ${assetIds.size})")
        if (stillMs !in 500..30_000) throw IllegalArgumentException("เวลาต่อรูปต้องอยู่ 500..30000ms (ได้ $stillMs)")
        if (fadeMs !in 0..2000) throw IllegalArgumentException("เฟดต้องอยู่ 0..2000ms (ได้ $fadeMs)")
        if (ids.size != assetIds.size) throw IllegalArgumentException("ids ไม่ครบ")
        val dirs = ClipMotion.DIRS
        val existing = timeline.tracks.find { it.id == "V1" }
        if (existing != null) checkUnlocked(existing)
        var cursor = existing?.clips?.maxOfOrNull { it.atMs + it.outputDurationMs() } ?: 0L
        val clips = assetIds.mapIndexed { i, asset ->
            val fade = if (fadeMs > 0) ClipTransition("fade", fadeMs) else null
            Clip(
                ids[i], asset, 0, stillMs, cursor,
                transitionIn = fade, transitionOut = fade,
                motion = ClipMotion(dirs[i % dirs.size], 22),
            ).also { cursor += stillMs }
        }
        val tracks = if (existing != null) {
            timeline.tracks.map { if (it.id == "V1") it.copy(clips = it.clips + clips) else it }
        } else {
            timeline.tracks + Track("V1", MediaKind.VIDEO, clips)
        }
        return timeline.copy(tracks = tracks)
    }

    /** CP-85 §102: sets clip volume 0..100 (audio + video clips). */
    fun volume(timeline: Timeline, clipId: String, volume: Int): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (volume !in 0..100) throw IllegalArgumentException("เสียงต้องอยู่ 0..100 (ได้ $volume)")
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) clip.copy(volume = volume) else it })
    }

    /** CP-85 §31: adds many markers in one edit (beat grids). */
    fun addMarkers(timeline: Timeline, markers: List<TimelineMarker>): Timeline {
        if (markers.isEmpty()) throw IllegalArgumentException("ไม่มีมาร์กเกอร์ให้เพิ่ม")
        if (markers.size > 200) throw IllegalArgumentException("มาร์กเกอร์มากสุด 200 จุดต่อครั้ง (ได้ ${markers.size})")
        for (m in markers) {
            if (m.atMs < 0) throw IllegalArgumentException("มาร์กเกอร์เวลาติดลบไม่ได้")
        }
        return timeline.copy(markers = timeline.markers + markers)
    }

    /** CP-87 §32: replaces a clip with kept sub-clips (source-ms ranges, packed). */
    fun autocut(timeline: Timeline, clipId: String, keep: List<Pair<Long, Long>>, ids: List<String>): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (clip.speed != null) throw IllegalArgumentException("autocut ยังไม่รองรับคลิปที่เปลี่ยนสปีด")
        if (keep.isEmpty()) throw IllegalArgumentException("ไม่มีช่วงให้เก็บ")
        if (keep.size > 100) throw IllegalArgumentException("ช่วงมากสุด 100 ช่วง (ได้ ${keep.size})")
        if (ids.size != keep.size) throw IllegalArgumentException("ids ไม่ครบ")
        val sorted = keep.sortedBy { it.first }
        for ((s, e) in sorted) {
            if (s < clip.startMs || e > clip.endMs || s >= e) {
                throw IllegalArgumentException("ช่วง $s-$e อยู่นอกคลิป")
            }
        }
        for (i in 1 until sorted.size) {
            if (sorted[i].first < sorted[i - 1].second) throw IllegalArgumentException("ช่วงทับซ้อนกัน")
        }
        var cursor = clip.atMs
        val subs = sorted.mapIndexed { i, (s, e) ->
            Clip(
                ids[i], clip.assetId, s, e, cursor, clip.volume,
                transform = clip.transform, fx = clip.fx, color = clip.color, motion = clip.motion,
            ).also { cursor += (e - s) }
        }
        val clips = track.clips.flatMap { if (it.id == clipId) subs else listOf(it) }
        return timeline.replaceClips(track.id, clips)
    }

    /** CP-88 §33: sets the canvas aspect override ("" clears to auto). */
    fun setCanvas(timeline: Timeline, canvas: String): Timeline {
        if (canvas.isNotEmpty() && canvas !in setOf("16:9", "9:16", "1:1", "4:5")) {
            throw IllegalArgumentException("สัดส่วนไม่รองรับ $canvas (มี 16:9/9:16/1:1/4:5)")
        }
        return timeline.copy(canvas = canvas)
    }

    /** CP-79: replaces the timeline background (§19). Null/identity clears. */
    fun background(timeline: Timeline, background: ClipBackground?): Timeline {
        if (background != null) {
            val problems = background.validate()
            if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
            if (background.mode == "image") {
                val ids = timeline.tracks.flatMap { it.clips }.map { it.assetId }.toSet()
                if (background.assetId !in ids) {
                    throw IllegalArgumentException("พื้นหลังอ้าง asset ที่ไม่มีในไทม์ไลน์ (${background.assetId})")
                }
            }
        }
        val next = if (background == null || background.isIdentity) null else background
        return timeline.copy(background = next)
    }

    /**
     * CP-80: applies a tracked motion path as posX/posY keyframes (§15/§43).
     * Replaces existing pos keys; [zoomBump] raises base scale (stabilize borders).
     */
    fun applyPath(
        timeline: Timeline,
        clipId: String,
        posX: List<KeyPoint>,
        posY: List<KeyPoint>,
        zoomBump: Int = 0,
    ): Timeline {
        val (track, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        checkUnlocked(track)
        if (track.kind == MediaKind.AUDIO) {
            throw IllegalArgumentException("คลิปเสียงใช้ motion path ไม่ได้")
        }
        if (posX.size > 64 || posY.size > 64) throw IllegalArgumentException("track มีจุดเกิน 64")
        if (zoomBump !in 0..50) throw IllegalArgumentException("zoom ต้องอยู่ 0..50")
        val keys = clip.keyframes ?: ClipKeyframes()
        val done = keys.withPoints("posX", posX.sortedBy { it.atMs }).withPoints("posY", posY.sortedBy { it.atMs })
        val problems = done.validate()
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
        val base = clip.transform ?: ClipTransform()
        val nextTransform = if (zoomBump > 0) {
            base.copy(scale = (base.scale + zoomBump).coerceAtMost(400))
        } else {
            clip.transform
        }
        val next = clip.copy(keyframes = done.takeUnless { it.isEmpty }, transform = nextTransform)
        return timeline.replaceClips(track.id, track.clips.map { if (it.id == clipId) next else it })
    }

    private fun checkUnlocked(track: Track) {
        if (track.locked) throw IllegalArgumentException("แทร็ก ${track.id} ล็อกอยู่")
    }

    private fun Timeline.replaceClips(trackId: String, clips: List<Clip>): Timeline =
        copy(tracks = tracks.map { if (it.id == trackId) it.copy(clips = clips) else it })
}
