package com.aicodemax.app

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aicodemax.data.media.Clip
import com.aicodemax.data.media.MediaAsset
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.OverlayText
import com.aicodemax.data.media.Timeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.min

/** Original-media player: scrubbing shows actual frames. Color/FX/mix are rendered in export, not faked in preview. */
@Composable
internal fun VideoPlaybackPreview(
    source: String?, kind: String, clip: Clip?, aspect: String, timeMs: Long, durationMs: Long,
    playing: Boolean, texts: List<OverlayText>, onToggle: () -> Unit,
    onPosition: (Long) -> Unit, onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aspectValue = when (aspect) { "9:16" -> 9f / 16; "1:1" -> 1f; "4:5" -> 4f / 5; else -> 16f / 9 }
    val sourceTime = clip?.let { it.startMs + it.outputToSource(timeMs - it.atMs) } ?: 0L
    val videoView = remember(source, clip?.id) { mutableStateOf<VideoView?>(null) }
    val isRealVideo = source != null && kind == "VIDEO" && clip != null
    val latestTime by rememberUpdatedState(timeMs)
    LaunchedEffect(playing, source, clip?.id) {
        if (!playing) return@LaunchedEffect
        while (true) {
            delay(110)
            val current = videoView.value
            if (isRealVideo && current != null && current.isPlaying) {
                val pos = current.currentPosition.toLong()
                val target = clip!!.atMs + clip.sourceToOutput((pos - clip.startMs).coerceAtLeast(0))
                if (pos >= clip.endMs - 90 || target >= clip.atMs + clip.outputDurationMs() - 90) {
                    if (target >= durationMs - 90) onStop() else onPosition((clip.atMs + clip.outputDurationMs()).coerceAtMost(durationMs))
                } else onPosition(target.coerceIn(0, durationMs))
            } else if (!isRealVideo) {
                if (latestTime >= durationMs) onStop() else onPosition((latestTime + 110).coerceAtMost(durationMs))
            }
        }
    }
    DisposableEffect(source, clip?.id, playing) {
        onDispose { videoView.value?.stopPlayback(); videoView.value = null }
    }
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val canvasWidth = min(maxWidth.value, maxHeight.value * aspectValue).dp
        val canvasHeight = (canvasWidth.value / aspectValue).dp
        Box(
            Modifier.size(canvasWidth, canvasHeight).background(Color(0xFF171819))
                .clickable(onClickLabel = if (playing) "หยุดเล่น" else "เล่น") { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (isRealVideo && playing) {
                androidx.compose.runtime.key(source, clip!!.id) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).also { view ->
                                videoView.value = view
                                view.setOnPreparedListener { player ->
                                    try {
                                        // Playback is original media. The non-destructive render applies effects on export.
                                        player.playbackParams = player.playbackParams.setSpeed((clip.speed?.rate ?: 100) / 100f)
                                    } catch (_: Exception) { }
                                    view.seekTo(sourceTime.toInt())
                                    view.start()
                                }
                                view.setOnCompletionListener {
                                    onPosition((clip.atMs + clip.outputDurationMs()).coerceAtMost(durationMs))
                                }
                                view.setVideoPath(source!! )
                            }
                        },
                        update = { view ->
                            videoView.value = view
                            if (abs(view.currentPosition.toLong() - sourceTime) > 900 && view.isPlaying) {
                                view.seekTo(sourceTime.toInt())
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                VideoFrame(source, kind, sourceTime, Modifier.fillMaxSize(), description = "เฟรมจริง ณ ${videoTime(timeMs)}", precise = true)
            }
            if (!playing && source != null) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Text("▶", color = VideoInk.text, style = MaterialTheme.typography.titleLarge)
                }
            }
            if (source == null && clip == null) Text("เพิ่มวิดีโอหรือรูปภาพลงไทม์ไลน์", color = VideoInk.muted, style = MaterialTheme.typography.bodySmall)
            if (texts.any { timeMs >= it.startMs && timeMs < it.endMs }) {
                // Metadata indicator, not a composited result preview.
                Text("T  ${texts.count { timeMs >= it.startMs && timeMs < it.endMs }} ข้อความในช่วงนี้", color = VideoInk.text, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.7f)).padding(5.dp))
            }
        }
        Text(
            "ตัวอย่างคลิปต้นฉบับ · สี / FX / เสียงผสม ดูผลจริงหลังเรนเดอร์",
            color = VideoInk.muted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Fixed center playhead; real thumbnail lanes, clip selection, trim edges and long-press move. */
@Composable
internal fun VideoTimelinePanel(
    timeline: Timeline?, assets: List<MediaAsset>, paths: Map<String, String>,
    playheadMs: Long, selectedId: String?, zoom: Float, playing: Boolean, externalSeekKey: Boolean,
    onSeek: (Long) -> Unit, onSelect: (String) -> Unit, onZoom: (Float) -> Unit,
    onAdd: () -> Unit,
    onMove: (Clip, Long) -> Unit,
    onTrim: (Clip, Long?, Long?, Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val duration = timeline?.durationMs ?: 0L
    BoxWithConstraints(modifier) {
        val viewport = maxWidth
        val seconds = duration / 1000f
        val pixelsPerSecond = min(zoom, (48000f / seconds.coerceAtLeast(1f)).coerceAtLeast(1f))
        val fullWidth = viewport + (seconds * pixelsPerSecond).dp + 68.dp
        LaunchedEffect(scroll, pixelsPerSecond, playing, duration) {
            snapshotFlow { scroll.value to scroll.isScrollInProgress }.collectLatest { (offset, dragging) ->
                if (dragging && !playing) {
                    val ms = (offset / density.density / pixelsPerSecond * 1000).toLong()
                    onSeek(ms.coerceIn(0, duration))
                }
            }
        }
        // Match a persisted playhead on open, after edits, and after full-screen scrubbing.
        LaunchedEffect(duration, pixelsPerSecond, externalSeekKey) {
            if (!scroll.isScrollInProgress) scroll.scrollTo((playheadMs / 1000f * pixelsPerSecond * density.density).toInt())
        }
        LaunchedEffect(playing, playheadMs, pixelsPerSecond) {
            if (playing) scroll.scrollTo((playheadMs / 1000f * pixelsPerSecond * density.density).toInt())
        }
        // Two-finger pinch changes scale; one-finger drag remains owned by horizontalScroll.
        val pinch = Modifier.pointerInput(zoom) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var event: PointerEvent
                do {
                    event = awaitPointerEvent()
                    if (event.changes.size >= 2) {
                        val factor = event.calculateZoom()
                        if (factor != 1f) onZoom(zoom * factor)
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        Column(Modifier.fillMaxSize().then(pinch).horizontalScroll(scroll).verticalScroll(rememberScrollState())) {
            Canvas(Modifier.width(fullWidth).height(29.dp)) {
                val originPx = viewport.toPx() / 2f
                val stepPx = pixelsPerSecond.dp.toPx()
                val tickStep = when {
                    pixelsPerSecond < 8 -> 10
                    pixelsPerSecond < 28 -> 5
                    else -> 1
                }
                val first = ((scroll.value - originPx).coerceAtLeast(0f) / stepPx).toInt() / tickStep * tickStep
                val visibleEnd = ((scroll.value + viewport.toPx()) / stepPx).toInt() + tickStep * 2
                for (sec in first..min(visibleEnd, (duration / 1000).toInt() + 1) step tickStep) {
                    val x = originPx + sec * stepPx
                    drawLine(VideoInk.border, Offset(x, 17.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                    if (sec % (tickStep * 2) == 0) {
                        drawContext.canvas.nativeCanvas.drawText(videoTime(sec * 1000L), x + 4.dp.toPx(), 13.dp.toPx(),
                            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.LTGRAY; textSize = 10.dp.toPx() })
                    }
                }
            }
            if (timeline == null || timeline.tracks.isEmpty()) {
                Box(Modifier.width(fullWidth).height(66.dp).padding(start = viewport / 2), contentAlignment = Alignment.CenterStart) {
                    Text("แตะ + เพื่อเพิ่มคลิป", color = VideoInk.muted)
                }
            }
            timeline?.tracks?.sortedWith(compareBy({ when (it.kind) { MediaKind.VIDEO -> 0; MediaKind.IMAGE -> 1; MediaKind.AUDIO -> 2 } }, { it.id }))?.forEach { track ->
                val laneHeight = if (track.kind == MediaKind.AUDIO) 43.dp else 69.dp
                Box(Modifier.width(fullWidth).height(laneHeight).background(if (track.kind == MediaKind.AUDIO) VideoInk.background else VideoInk.surface)) {
                    track.clips.forEach { clip ->
                        val asset = assets.firstOrNull { it.id == clip.assetId }
                        val offsetDp = viewport / 2 + (clip.atMs / 1000f * pixelsPerSecond).dp
                        val widthDp = (clip.outputDurationMs() / 1000f * pixelsPerSecond).coerceAtLeast(32f).dp
                        VideoClipTile(
                            clip, track.kind, asset?.originalName ?: track.id, paths[clip.assetId],
                            selected = clip.id == selectedId, pixelsPerSecond = pixelsPerSecond,
                            onSelect = { onSelect(clip.id) }, onMove = { at -> onMove(clip, at) },
                            onTrim = { start, end, at -> onTrim(clip, start, end, at) },
                            modifier = Modifier.offset(x = offsetDp, y = 3.dp).width(widthDp).height(laneHeight - 6.dp),
                        )
                    }
                    if (track.kind == MediaKind.VIDEO || track.kind == MediaKind.IMAGE) {
                        Box(Modifier.offset(x = viewport / 2 + (duration / 1000f * pixelsPerSecond).dp + 4.dp, y = 10.dp).size(44.dp)
                            .clip(RoundedCornerShape(10.dp)).background(VideoInk.green)
                            .clickable(onClickLabel = "เพิ่มคลิป") { onAdd() }, contentAlignment = Alignment.Center) {
                            Text("＋", color = VideoInk.text, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            if (timeline?.texts?.isNotEmpty() == true) {
                Box(Modifier.width(fullWidth).height(28.dp).background(VideoInk.background)) {
                    timeline.texts.forEach { overlay ->
                        val offsetDp = viewport / 2 + (overlay.startMs / 1000f * pixelsPerSecond).dp
                        Box(Modifier.offset(x = offsetDp, y = 3.dp)
                            .width(((overlay.endMs - overlay.startMs) / 1000f * pixelsPerSecond).coerceAtLeast(34f).dp)
                            .height(23.dp).clip(RoundedCornerShape(5.dp)).background(VideoInk.greenSoft).border(1.dp, VideoInk.green, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.CenterStart) {
                            Text("T  ${overlay.text}", color = VideoInk.text, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(start = 5.dp))
                        }
                    }
                }
            }
        }
        // The playhead remains fixed while all lanes scroll underneath it.
        Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(VideoInk.text))
        Box(Modifier.align(Alignment.TopCenter).size(width = 14.dp, height = 11.dp).background(VideoInk.text, RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)))
        Text("V / A / T", color = VideoInk.muted, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).background(VideoInk.background.copy(alpha = 0.8f)).padding(4.dp))
    }
}

@Composable
private fun VideoClipTile(
    clip: Clip, kind: MediaKind, name: String, source: String?, selected: Boolean,
    pixelsPerSecond: Float, onSelect: () -> Unit, onMove: (Long) -> Unit,
    onTrim: (Long?, Long?, Long?) -> Unit, modifier: Modifier,
) {
    val density = LocalDensity.current
    var movePx by remember(clip.id, clip.atMs) { mutableFloatStateOf(0f) }
    val shape = RoundedCornerShape(7.dp)
    Box(modifier.offset(x = (movePx / density.density).dp)
        .clip(shape)
        .background(if (kind == MediaKind.AUDIO) VideoInk.purple else VideoInk.raised)
        .border(if (selected) 2.dp else 1.dp, if (selected) VideoInk.yellow else VideoInk.border, shape)
        .pointerInput(clip.id, pixelsPerSecond) {
            detectDragGesturesAfterLongPress(
                onDragEnd = {
                    val delta = (movePx / density.density / pixelsPerSecond * 1000).toLong()
                    if (delta != 0L) onMove((clip.atMs + delta).coerceAtLeast(0L))
                    movePx = 0f
                },
                onDragCancel = { movePx = 0f },
                onDrag = { change, drag -> change.consume(); movePx += drag.x },
            )
        }
        .clickable(onClickLabel = "เลือกคลิป $name", onClick = onSelect)) {
        if (kind != MediaKind.AUDIO) {
            Row(Modifier.fillMaxSize()) {
                val frameCount = (clip.outputDurationMs() / 1800).toInt().coerceIn(1, 6)
                repeat(frameCount) { index ->
                    VideoFrame(source, kind.name, clip.startMs + clip.durationMs * index / frameCount, Modifier.weight(1f).fillMaxHeight())
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.24f)))
        }
        Text(
            name,
            color = VideoInk.text,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(start = if (selected) 17.dp else 5.dp, top = 2.dp, bottom = 2.dp),
        )
        if (selected) {
            listOf(true, false).forEach { isLeft ->
                var dragPx by remember(clip.id, isLeft) { mutableFloatStateOf(0f) }
                Box(Modifier.align(if (isLeft) Alignment.CenterStart else Alignment.CenterEnd).width(18.dp).fillMaxHeight()
                    .background(VideoInk.yellow.copy(alpha = 0.9f))
                    .pointerInput(clip.id, pixelsPerSecond, isLeft) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val ms = (dragPx / density.density / pixelsPerSecond * 1000).toLong()
                                val rate = (clip.speed?.rate ?: 100) / 100f
                                val srcDelta = (ms * rate).toLong()
                                if (isLeft) {
                                    val start = (clip.startMs + srcDelta).coerceIn(0, clip.endMs - 100)
                                    val at = (clip.atMs + (start - clip.startMs) / rate).toLong().coerceAtLeast(0)
                                    if (start != clip.startMs) onTrim(start, null, at)
                                } else {
                                    val end = (clip.endMs + srcDelta).coerceAtLeast(clip.startMs + 100)
                                    if (end != clip.endMs) onTrim(null, end, null)
                                }
                                dragPx = 0f
                            },
                            onDragCancel = { dragPx = 0f },
                            onHorizontalDrag = { change, dx -> change.consume(); dragPx += dx },
                        )
                    }, contentAlignment = Alignment.Center) {
                    Text("│", color = VideoInk.background, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
