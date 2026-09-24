package com.aicodemax.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.Clip
import com.aicodemax.data.media.ClipChroma
import com.aicodemax.data.media.ClipColor
import com.aicodemax.data.media.ClipFx
import com.aicodemax.data.media.ClipKeyframes
import com.aicodemax.data.media.ClipMask
import com.aicodemax.data.media.ClipMotion
import com.aicodemax.data.media.ClipSpeed
import com.aicodemax.data.media.ClipTransform
import com.aicodemax.data.media.OverlayText
import com.aicodemax.data.media.Timeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Bottom sheet covers the lower part of the editor, leaving preview visible. */
@Composable
internal fun VideoToolSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.46f)).clickable(onClickLabel = "ปิดแผงเครื่องมือ", onClick = onClose)) {
        var dragged by remember { mutableFloatStateOf(0f) }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 440.dp)
                .background(VideoInk.surface, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .clickable(onClickLabel = "แผง $title") { }
                .padding(bottom = 8.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(24.dp).pointerInput(title) {
                detectVerticalDragGestures(onVerticalDrag = { _, delta -> dragged += delta }, onDragEnd = {
                    if (dragged > 70) onClose()
                    dragged = 0f
                })
            }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(38.dp).height(4.dp).background(VideoInk.muted, RoundedCornerShape(50)))
            }
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), color = VideoInk.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                VideoIconButton("✓", "เสร็จสิ้น", onClose, tint = VideoInk.green)
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ToolRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

@Composable
private fun ToolLabel(text: String) {
    Text(text, color = VideoInk.muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ToolSlider(label: String, initial: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true, onFinished: (Float) -> Unit) {
    var draft by remember(label, initial) { mutableFloatStateOf(initial) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), color = VideoInk.text, style = MaterialTheme.typography.bodyMedium)
            Text("${draft.toInt()}%", color = VideoInk.green, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = draft.coerceIn(range.start, range.endInclusive), onValueChange = { draft = it },
            onValueChangeFinished = { onFinished(draft) }, valueRange = range, enabled = enabled)
    }
}

@Composable
private fun ColorDial(label: String, value: Int, range: IntRange, enabled: Boolean, onValue: (Int) -> Unit) {
    var draft by remember(label, value) { mutableFloatStateOf(value.toFloat()) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(77.dp)) {
        Box(Modifier.size(67.dp).pointerInput(label, value, enabled) {
            if (enabled) detectDragGestures(
                onDragEnd = { onValue(draft.toInt().coerceIn(range.first, range.last)) },
                onDrag = { change, amount ->
                    change.consume()
                    draft = (draft + (amount.x - amount.y) * 0.6f).coerceIn(range.first.toFloat(), range.last.toFloat())
                },
            )
        }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                drawArc(VideoInk.border, startAngle = 135f, sweepAngle = 270f, useCenter = false,
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
                val fraction = (draft - range.first) / (range.last - range.first).coerceAtLeast(1)
                drawArc(VideoInk.green, startAngle = 135f, sweepAngle = 270f * fraction, useCenter = false,
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
            }
            Text("${draft.toInt()}", color = VideoInk.text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Text(label, color = VideoInk.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Every enabled control calls a real port. Unsupported requests are explicitly not shown as working buttons. */
@Composable
internal fun VideoSheetControls(
    sheet: VideoSheet, services: ServiceLocator, projectId: String,
    clip: Clip?, timeline: Timeline?, timeMs: Long, busy: Boolean,
    onRun: (suspend () -> Outcome<*>) -> Unit,
    onGateway: (String, Map<String, String>) -> Unit,
    onImportVideo: () -> Unit, onImportAudio: () -> Unit,
    onAdvanced: () -> Unit, onOpenRoute: (String) -> Unit,
    onChat: (String) -> Unit, onClose: () -> Unit,
) {
    fun transform(change: (ClipTransform) -> ClipTransform) {
        val chosen = clip ?: return
        onRun { services.media.transformClip(projectId, chosen.id, change(chosen.transform ?: ClipTransform()), "HUMAN") }
    }
    fun color(change: (ClipColor) -> ClipColor) {
        val chosen = clip ?: return
        onRun { services.media.setClipColor(projectId, chosen.id, change(chosen.color ?: ClipColor()), "HUMAN") }
    }
    fun fx(change: (ClipFx) -> ClipFx) {
        val chosen = clip ?: return
        onRun { services.media.setClipFx(projectId, chosen.id, change(chosen.fx ?: ClipFx()), "HUMAN") }
    }
    when (sheet) {
        VideoSheet.MEDIA -> {
            ToolLabel("คลิปใหม่จะวางต่อท้ายไทม์ไลน์ · สื่อเดิมยังอยู่ในโปรเจกต์")
            ToolRow { VideoButton("เลือกรูป / วิดีโอ", onImportVideo, prominent = true, glyph = "＋", enabled = !busy) }
            ToolLabel("สัดส่วนแคนวาส")
            ToolRow {
                listOf("9:16", "16:9", "1:1").forEach { a ->
                    VideoChip(a, timeline?.canvas == a, { onRun { services.media.setCanvas(projectId, a, "HUMAN") } }, enabled = !busy)
                }
            }
            ToolLabel("ไฟล์เสียง: เปิดจากแผงเสียง")
        }
        VideoSheet.TRIM -> {
            if (clip != null) {
                ToolLabel("ลากขอบสีเหลืองบนคลิปเพื่อทริม หรือเลื่อนปุ่มด้านล่างเพื่อปรับแบบละเอียด")
                val assetDuration = clip.endMs.coerceAtLeast(clip.durationMs)
                var start by remember(clip.id, clip.startMs, clip.endMs) { mutableFloatStateOf(clip.startMs.toFloat()) }
                var end by remember(clip.id, clip.startMs, clip.endMs) { mutableFloatStateOf(clip.endMs.toFloat()) }
                ToolSlider("จุดเริ่ม ${videoTime(start.toLong())}", start, 0f..(clip.endMs - 100).coerceAtLeast(0).toFloat(), !busy) { start = it }
                ToolSlider("จุดจบ ${videoTime(end.toLong())}", end, (clip.startMs + 100).toFloat()..assetDuration.toFloat(), !busy) { end = it }
                VideoButton("บันทึกช่วงที่ตัด", onClick = {
                    val newStart = start.toLong().coerceAtMost(end.toLong() - 100)
                    val newEnd = end.toLong().coerceAtLeast(newStart + 100)
                    val rate = (clip.speed?.rate ?: 100) / 100f
                    val at = (clip.atMs + (newStart - clip.startMs) / rate).toLong().coerceAtLeast(0)
                    onRun { services.media.trimClip(projectId, clip.id, newStart, newEnd, at, "HUMAN") }
                }, prominent = true, enabled = !busy)
            } else ToolLabel("เลือกคลิปบนไทม์ไลน์ก่อน")
        }
        VideoSheet.TRANSFORM -> {
            if (clip != null) {
                ToolLabel("หมุน · พลิก · ซูม · ครอป · ตำแหน่ง · ความทึบ")
                ToolRow {
                    VideoButton("หมุน 90°", { transform { it.copy(rotation = (it.rotation + 90) % 360) } }, enabled = !busy)
                    VideoButton("พลิก ↔", { transform { it.copy(flipH = !it.flipH) } }, enabled = !busy)
                    VideoButton("พลิก ↕", { transform { it.copy(flipV = !it.flipV) } }, enabled = !busy)
                }
                ToolRow {
                    VideoButton("ซูม −", { transform { it.copy(scale = (it.scale - 10).coerceAtLeast(10)) } }, enabled = !busy)
                    VideoButton("ซูม + ${clip.transform?.scale ?: 100}%", { transform { it.copy(scale = (it.scale + 10).coerceAtMost(400)) } }, enabled = !busy)
                    VideoButton("รีเซ็ต", { transform { ClipTransform() } }, enabled = !busy)
                }
                ToolRow {
                    VideoButton("ทึบ −", { transform { it.copy(opacity = (it.opacity - 10).coerceAtLeast(0)) } }, enabled = !busy)
                    VideoButton("ทึบ + ${clip.transform?.opacity ?: 100}%", { transform { it.copy(opacity = (it.opacity + 10).coerceAtMost(100)) } }, enabled = !busy)
                }
                ToolLabel("ครอป")
                ToolRow {
                    listOf(100, 75, 50).forEach { amount ->
                        VideoChip("$amount%", clip.transform?.cropW == amount,
                            { transform { it.copy(cropX = (100 - amount) / 2, cropY = (100 - amount) / 2, cropW = amount, cropH = amount) } }, enabled = !busy)
                    }
                }
                ToolRow {
                    listOf("←" to (-5 to 0), "↑" to (0 to -5), "↓" to (0 to 5), "→" to (5 to 0)).forEach { (glyph, xy) ->
                        VideoButton(glyph, { transform { it.copy(posX = (it.posX + xy.first).coerceIn(-4000, 4000), posY = (it.posY + xy.second).coerceIn(-4000, 4000)) } }, enabled = !busy)
                    }
                }
                ToolLabel("ภาพตัวอย่างแสดงสื่อต้นฉบับ ผลการปรับแต่งแสดงหลังเรนเดอร์")
            } else ToolLabel("เลือกคลิปเพื่อปรับแต่ง")
        }
        VideoSheet.SPEED -> {
            if (clip != null) {
                val speed = clip.speed ?: ClipSpeed()
                ToolSlider("ความเร็ว", speed.rate.toFloat(), 25f.0.400f, !busy) { rate ->
                    onRun { services.media.setClipSpeed(projectId, clip.id, speed.copy(rate = rate.toInt()), "HUMAN") }
                }
                ToolRow {
                    listOf(50, 100, 200, 300).forEach { r ->
                        VideoChip("${r / 100f}×", speed.rate == r, { onRun { services.media.setClipSpeed(projectId, clip.id, speed.copy(rate = r), "HUMAN") } }, enabled = !busy)
                    }
                }
                VideoButton(if (speed.reverse) "ปิดการเล่นย้อนกลับ" else "เล่นย้อนกลับ", {
                    onRun { services.media.setClipSpeed(projectId, clip.id, speed.copy(reverse = !speed.reverse, curve = emptyList()), "HUMAN") }
                }, enabled = !busy)
                ToolLabel("กราฟความเร็ว (Ramp)")
                ToolRow {
                    (listOf("none") + ClipSpeed.presetNames()).forEach { name ->
                        VideoChip(name, speed.curve == ClipSpeed.preset(name), {
                            onRun { services.media.setClipSpeed(projectId, clip.id, speed.copy(curve = ClipSpeed.preset(name), reverse = false), "HUMAN") }
                        }, enabled = !busy)
                    }
                }
            } else ToolLabel("เลือกคลิปเพื่อปรับความเร็ว")
        }
        VideoSheet.KEYFRAME -> {
            if (clip != null) {
                var prop by remember(clip.id) { mutableStateOf("scale") }
                val bounds = ClipKeyframes.RANGES[prop] ?: (0f to 100f)
                var value by remember(clip.id, prop) { mutableFloatStateOf(if (prop in listOf("scale", "opacity", "volume")) 100f else 0f) }
                ToolLabel("กำหนดคุณสมบัติที่ตำแหน่งเส้นเล่น (${videoTime(timeMs)})")
                ToolRow {
                    listOf("scale", "posX", "posY", "rotation", "opacity", "volume", "cropW").forEach { item ->
                        VideoChip(item, prop == item, { prop = item })
                    }
                }
                ToolSlider(prop, value, bounds.first..bounds.second, !busy) { value = it }
                ToolRow {
                    VideoButton("＋ คีย์เฟรม", {
                        val at = (timeMs - clip.atMs).coerceIn(0, clip.outputDurationMs())
                        onRun { services.media.setKeyframe(projectId, clip.id, prop, at, value, "linear", "HUMAN") }
                    }, prominent = true, enabled = !busy)
                    VideoButton("ล้าง $prop", { onRun { services.media.clearKeyframes(projectId, clip.id, prop, "HUMAN") } }, enabled = !busy)
                }
                clip.keyframes?.points(prop)?.forEach { point ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${videoTime(point.atMs)}  =  ${point.value}", color = VideoInk.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        VideoButton("ลบ", { onRun { services.media.removeKeyframe(projectId, clip.id, prop, point.atMs, "HUMAN") } }, enabled = !busy)
                    }
                }
            } else ToolLabel("เลือกคลิปก่อนเพิ่มคีย์เฟรม")
        }
        VideoSheet.TRANSITION -> {
            if (clip != null) {
                ToolLabel("ทรานซิชันเข้าคลิป")
                ToolRow {
                    listOf("cut", "fade", "dissolve", "wipeleft", "wiperight", "wipeup", "wipedown").forEach { name ->
                        VideoChip(name, clip.transitionIn?.kind == name || (name == "cut" && clip.transitionIn == null), {
                            onRun { services.media.setTransition(projectId, clip.id, "in", name, 500L.coerceAtMost(clip.outputDurationMs()), "HUMAN") }
                        }, enabled = !busy)
                    }
                }
                ToolLabel("ทรานซิชันออก")
                ToolRow {
                    listOf("cut", "fade").forEach { name ->
                        VideoChip(name, clip.transitionOut?.kind == name || (name == "cut" && clip.transitionOut == null), {
                            onRun { services.media.setTransition(projectId, clip.id, "out", name, 500L.coerceAtMost(clip.outputDurationMs()), "HUMAN") }
                        }, enabled = !busy)
                    }
                }
                ToolRow {
                    VideoButton("สั้น 250ms", { onRun { services.media.setTransition(projectId, clip.id, "in", clip.transitionIn?.kind ?: "fade", 250L.coerceAtMost(clip.outputDurationMs()), "HUMAN") } }, enabled = !busy)
                    VideoButton("ยาว 800ms", { onRun { services.media.setTransition(projectId, clip.id, "in", clip.transitionIn?.kind ?: "fade", 800L.coerceAtMost(clip.outputDurationMs()), "HUMAN") } }, enabled = !busy)
                }
            } else ToolLabel("เลือกคลิปก่อนใส่ทรานซิชัน")
        }
        VideoSheet.AUDIO -> {
            ToolRow { VideoButton("เพิ่มแทร็กเสียง", onImportAudio, prominent = true, glyph = "♫", enabled = !busy) }
            if (clip != null) {
                ToolSlider("ระดับเสียง", clip.volume.toFloat(), 0f.0.100f, !busy) { v ->
                    onRun { services.media.setClipVolume(projectId, clip.id, v.toInt(), "HUMAN") }
                }
                ToolRow {
                    VideoButton("ดัง", { onRun { services.media.setClipVolume(projectId, clip.id, (clip.volume + 10).coerceAtMost(100), "HUMAN") } }, enabled = !busy)
                    VideoButton("เบา", { onRun { services.media.setClipVolume(projectId, clip.id, (clip.volume - 10).coerceAtLeast(0), "HUMAN") } }, enabled = !busy)
                    VideoButton(if (clip.volume == 0) "คืนเสียง" else "ปิดเสียง", { onRun { services.media.setClipVolume(projectId, clip.id, if (clip.volume == 0) 100 else 0, "HUMAN") } }, enabled = !busy)
                }
                ToolRow {
                    VideoButton("แยกเสียงจากคลิป", {
                        onRun { detachVideoAudio(services, projectId, clip) }
                    }, enabled = !busy && timeline?.findClip(clip.id)?.first?.kind == com.aicodemax.data.media.MediaKind.VIDEO)
                    VideoButton("ตัดช่วงเงียบ", { onGateway("timeline.autocut", emptyMap()) }, enabled = !busy)
                }
            }
            ToolLabel("ระดับเสียงของเอนจินปัจจุบันรองรับ 0–100% (ไม่ใช่ 200%)")
        }
        VideoSheet.COLOR -> {
            if (clip != null) {
                val cc = clip.color ?: ClipColor()
                ToolLabel("แตะค้างแล้วลากบนวงกลมเพื่อปรับค่า • สีจริงแสดงหลังเรนเดอร์")
                ToolRow {
                    ColorDial("สว่าง", cc.brightness, -100..100, !busy) { v -> color { it.copy(brightness = v) } }
                    ColorDial("มืด", cc.shadows, -100..100, !busy) { v -> color { it.copy(shadows = v) } }
                    ColorDial("สด+", cc.saturation, -100..100, !busy) { v -> color { it.copy(saturation = v) } }
                    ColorDial("โทน", cc.temperature, -100..100, !busy) { v -> color { it.copy(temperature = v) } }
                    ColorDial("รับแสง", cc.exposure, -100..100, !busy) { v -> color { it.copy(exposure = v) } }
                }
                ToolLabel("พรีเซ็ตสี")
                ToolRow {
                    ClipColor.PRESETS.forEach { name ->
                        VideoChip(name, cc == ClipColor.preset(name), { onRun { services.media.setClipColor(projectId, clip.id, ClipColor.preset(name), "HUMAN") } }, enabled = !busy)
                    }
                }
                VideoButton("✧ ออโต้สี (AI)", { onGateway("timeline.colorAuto", emptyMap()) }, prominent = true, enabled = !busy)
            } else ToolLabel("เลือกคลิปก่อนปรับสี")
        }
        VideoSheet.FX -> {
            if (clip != null) {
                val effect = clip.fx ?: ClipFx()
                ToolSlider("เบลอ", effect.blur.toFloat(), 0f.0.10f, !busy) { v -> fx { it.copy(blur = v.toInt()) } }
                ToolSlider("วิกเน็ต", effect.vignette.toFloat(), 0f.0.100f, !busy) { v -> fx { it.copy(vignette = v.toInt()) } }
                ToolSlider("เกรน", effect.grain.toFloat(), 0f.0.100f, !busy) { v -> fx { it.copy(grain = v.toInt()) } }
                VideoButton("ปรับปรุงภาพอัตโนมัติ", { onGateway("timeline.enhance", emptyMap()) }, enabled = !busy)
            } else ToolLabel("เลือกคลิปก่อนใส่เอฟเฟกต์")
        }
        VideoSheet.MASK -> {
            if (clip != null) {
                val mask = clip.mask ?: ClipMask()
                ToolLabel("รูปทรงหน้ากาก")
                ToolRow {
                    listOf("rect" to "สี่เหลี่ยม", "ellipse" to "วงกลม").forEach { (shape, name) ->
                        VideoChip(name, mask.shape == shape && !mask.isIdentity, {
                            onRun { services.media.setClipMask(projectId, clip.id, mask.copy(shape = shape, x = 20, y = 20, w = 60, h = 60), "HUMAN") }
                        }, enabled = !busy)
                    }
                    VideoButton("ล้างมาสก์", { onRun { services.media.setClipMask(projectId, clip.id, ClipMask(), "HUMAN") } }, enabled = !busy)
                }
                ToolSlider("ขอบนุ่ม", mask.feather.toFloat(), 0f.0.100f, !busy) { v ->
                    onRun { services.media.setClipMask(projectId, clip.id, mask.copy(feather = v.toInt()), "HUMAN") }
                }
                ToolLabel("กรีนสกรีน / คีย์สี")
                ToolRow {
                    VideoButton(if (clip.chroma == null) "เปิดกรีนสกรีน" else "ปิดกรีนสกรีน", {
                        onRun { services.media.setClipChroma(projectId, clip.id, if (clip.chroma == null) ClipChroma() else null, "HUMAN") }
                    }, enabled = !busy)
                    VideoButton("เขียว / ฟ้า", { onRun { services.media.setClipChroma(projectId, clip.id, (clip.chroma ?: ClipChroma()).copy(hue = if (clip.chroma?.hue == 240) 120 else 240), "HUMAN") } }, enabled = !busy)
                }
            } else ToolLabel("เลือกคลิปก่อนใช้มาสก์")
        }
        VideoSheet.MOTION -> {
            if (clip != null) {
                ToolLabel("แพน / ซูม (Ken Burns)")
                ToolRow {
                    ClipMotion.DIRS.forEach { direction ->
                        VideoChip(direction, clip.motion?.direction == direction, { onRun { services.media.setClipMotion(projectId, clip.id, (clip.motion ?: ClipMotion()).copy(direction = direction), "HUMAN") } }, enabled = !busy)
                    }
                }
                ToolSlider("ระยะซูม", (clip.motion?.zoom ?: 20).toFloat(), 0f.0.60f, !busy) { v ->
                    onRun { services.media.setClipMotion(projectId, clip.id, (clip.motion ?: ClipMotion()).copy(zoom = v.toInt()), "HUMAN") }
                }
                ToolRow {
                    VideoButton("กันสั่น", { onGateway("timeline.stabilize", emptyMap()) }, enabled = !busy)
                    VideoButton("ติดตามวัตถุ", { onGateway("timeline.track", mapOf("target" to "self")) }, enabled = !busy)
                    VideoButton("ปิดโมชั่น", { onRun { services.media.setClipMotion(projectId, clip.id, null, "HUMAN") } }, enabled = !busy)
                }
            } else ToolLabel("เลือกคลิปก่อนปรับโมชั่น")
        }
        VideoSheet.TEXT -> {
            var text by remember { mutableStateOf("") }
            var style by remember { mutableStateOf("caption") }
            OutlinedTextField(text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ข้อความบนวิดีโอ") }, maxLines = 3)
            ToolRow {
                listOf("title", "lower", "caption", "hook", "cta").forEach { name ->
                    VideoChip(name, style == name, { style = name })
                }
            }
            VideoButton("เพิ่มข้อความที่เส้นเล่น", {
                val max = timeline?.durationMs ?: 0
                val at = timeMs.coerceAtMost((max - 500).coerceAtLeast(0))
                val overlay = OverlayText.preset(style).copy(
                    id = Ids.newId("text"), text = text.trim(), startMs = at, endMs = (at + 3000).coerceAtMost(max),
                )
                onRun { services.media.addText(projectId, overlay, "HUMAN") }
                text = ""
            }, enabled = !busy && text.isNotBlank() && (timeline?.durationMs ?: 0) > 500, prominent = true)
            timeline?.texts?.forEach { overlay ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${videoTime(overlay.startMs)}  ${overlay.text}", color = VideoInk.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    VideoButton("ลบ", { onRun { services.media.removeText(projectId, overlay.id, "HUMAN") } }, enabled = !busy)
                }
            }
            VideoButton("เปิดเครื่องมือซับไตเติล", { onOpenRoute(Routes.SUBTITLE) }, enabled = !busy)
        }
        VideoSheet.AI -> {
            ToolLabel("เครื่องมือที่เชื่อมต่อกับเอนจินจริง • เลือกคลิปก่อนใช้")
            ToolRow {
                VideoButton("✧ ปรับสี", { onGateway("timeline.colorAuto", emptyMap()) }, enabled = !busy && clip != null)
                VideoButton("ตัดเงียบ", { onGateway("timeline.autocut", emptyMap()) }, enabled = !busy && clip != null)
                VideoButton("หาช็อตเด่น", { onGateway("timeline.highlights", emptyMap()) }, enabled = !busy && clip != null)
            }
            ToolRow {
                VideoButton("กันสั่น", { onGateway("timeline.stabilize", emptyMap()) }, enabled = !busy && clip != null)
                VideoButton("ถอดคำพูด / ซับ", { onOpenRoute(Routes.SUBTITLE) }, enabled = !busy)
            }
            VideoButton("คุยกับ AI เกี่ยวกับโปรเจกต์", { onChat("ช่วยตัดต่อวิดีโอโปรเจกต์ $projectId") })
        }
        VideoSheet.MORE -> {
            ToolLabel("เครื่องมือเชิงลึกเดิมยังอยู่ครบ: LUT, Multicam, Proxy, Mixer, Reframe, Slideshow, Track ฯลฯ")
            VideoButton("เปิดเครื่องมือขั้นสูง", onAdvanced, prominent = true)
            VideoButton("ไปคิวงานเรนเดอร์", { onOpenRoute(Routes.RENDER) })
        }
    }
}

/** Produces a real audio asset/clip and mutes the source; no fake detached track. */
private suspend fun detachVideoAudio(services: ServiceLocator, projectId: String, clip: Clip): Outcome<*> {
    if (clip.speed?.isIdentity == false) return Outcome.Failure(AppError("VIDEO_AUDIO", "โปรดคืนความเร็วคลิปเป็น 1× ก่อนแยกเสียง เพื่อรักษาการซิงก์"))
    val path = when (val src = services.media.assetPath(projectId, clip.assetId)) {
        is Outcome.Failure -> return src
        is Outcome.Success -> src.value
    }
    val output = File(services.workspaceDir, "video-derived/${clip.id}-${System.currentTimeMillis()}.m4a")
    return try {
        withContext(Dispatchers.IO) { output.parentFile?.mkdirs() }
        val extracted = services.video.extractAudio(path, output.absolutePath)
        if (extracted is Outcome.Failure) return extracted
        val imported = services.media.importAsset(projectId, output.absolutePath, "HUMAN")
        if (imported is Outcome.Failure) return imported
        val audioId = (imported as Outcome.Success).value.id
        val before = services.media.getTimeline(projectId)
        val added = services.media.addClip(projectId, audioId, clip.startMs, clip.endMs, clip.atMs, actor = "HUMAN")
        if (added is Outcome.Failure) return added
        // The older addClip path drops timeline canvas/background; restore those values.
        if (before is Outcome.Success) {
            if (before.value.canvas.isNotEmpty()) services.media.setCanvas(projectId, before.value.canvas, "HUMAN")
            before.value.background?.let { services.media.setBackground(projectId, it, "HUMAN") }
        }
        services.media.setClipVolume(projectId, clip.id, 0, "HUMAN")
    } finally { withContext(Dispatchers.IO) { output.delete() } }
}
