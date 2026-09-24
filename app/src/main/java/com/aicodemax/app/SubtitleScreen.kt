package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicodemax.ai.runtime.WhisperCatalog
import com.aicodemax.ai.runtime.WhisperSegments
import com.aicodemax.ai.runtime.WhisperStatus
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.ui.designsystem.LabeledField
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.OutputBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SubTab { STT, MAKE, PARSE, SHIFT, TRANSLATE, BURN }

/**
 * CP-137 subtitle studio (SCR-SUB-001..006): tabbed make/parse/shift/
 * translate/burn over subtitle.* tools.
 * CP-140: STT tab — on-device speech-to-text (whisper.cpp base) turns a
 * media file into a transcript + cues, then hands off to MAKE.
 */
@Composable
fun SubtitleScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(SubTab.STT) }
    var transcript by remember { mutableStateOf("") }
    var mediaPath by remember { mutableStateOf("") }
    var durationMs by remember { mutableStateOf("") }
    var src by remember { mutableStateOf("") }
    var offsetMs by remember { mutableStateOf("500") }
    var direction by remember { mutableStateOf("th-en") }
    var engine by remember { mutableStateOf("dict") }
    var videoSrc by remember { mutableStateOf("") }
    var srtPath by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // CP-140 STT state.
    var sttPath by remember { mutableStateOf("") }
    var sttLang by remember { mutableStateOf("auto") }
    var sttBusy by remember { mutableStateOf(false) }
    var sttStatus by remember { mutableStateOf<String?>(null) }
    var sttDlProgress by remember { mutableStateOf<Float?>(null) }
    var sttModelTick by remember { mutableStateOf(0) }
    var sttResult by remember { mutableStateOf<String?>(null) }
    var sttCues by remember { mutableStateOf(0) }
    var sttDurationMs by remember { mutableStateOf(0L) }
    val sttProfile = remember { WhisperCatalog.BASE }
    val sttModelStatus = remember(sttModelTick) { services.whisperManager.status(sttProfile) }
    val sttEngineState by services.whisperEngine.state.collectAsState()

    fun run(tool: String, action: String, args: Map<String, String>) {
        busy = true
        scope.launch {
            services.gateway.call(ToolCall(Ids.newId("ui"), tool, action, args, actor = "HUMAN")).fold(
                onSuccess = { message = if (it.ok) it.output else it.error },
                onFailure = { message = it.message },
            )
            busy = false
        }
    }

    fun downloadSttModel() {
        if (sttBusy) return
        sttBusy = true
        sttDlProgress = 0f
        sttStatus = "กำลังดาวน์โหลดโมเดล (~148MB ครั้งเดียว)…"
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                services.whisperManager.install(sttProfile) { done, total ->
                    sttDlProgress = if (total != null && total > 0) {
                        (done.toFloat() / total).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                }
            }
            res.fold(
                onSuccess = { sttStatus = "ดาวน์โหลดเสร็จ — พร้อมถอดเสียง" },
                onFailure = { sttStatus = "ผิดพลาด: ${it.message}" },
            )
            sttBusy = false
            sttDlProgress = null
            sttModelTick++
        }
    }

    fun transcribeFile() {
        if (sttBusy) return
        sttBusy = true
        sttStatus = "กำลังถอดรหัสเสียง…"
        sttResult = null
        sttCues = 0
        scope.launch {
            val decoded = withContext(Dispatchers.IO) { WhisperAudioDecoder.decode(sttPath.trim()) }
            val segs: Outcome<List<com.aicodemax.ai.runtime.WhisperSegment>> = decoded.fold(
                onSuccess = { d ->
                    sttDurationMs = d.durationMs
                    sttStatus = "กำลังถอดเสียง (${d.samples.size / 16000} วินาที, เอนจิน ${sttEngineState.name})…"
                    withContext(Dispatchers.IO) { services.whisperEngine.transcribe(d.samples, sttLang) }
                },
                onFailure = { Outcome.Failure(it) },
            )
            segs.fold(
                onSuccess = { list ->
                    sttCues = list.size
                    sttResult = WhisperSegments.toText(list)
                    sttStatus = "เสร็จ: ${list.size} คิว — กด “ส่งไปทำซับ” เพื่อทำไฟล์ .srt"
                },
                onFailure = { sttStatus = "ผิดพลาด: ${it.message}" },
            )
            sttBusy = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
            for (value in SubTab.values()) {
                Tab(
                    selected = tab == value,
                    onClick = { tab = value },
                    text = {
                        Text(
                            when (value) {
                                SubTab.STT -> "ถอดเสียง"
                                SubTab.MAKE -> "ทำซับ"
                                SubTab.PARSE -> "อ่าน"
                                SubTab.SHIFT -> "เลื่อนเวลา"
                                SubTab.TRANSLATE -> "แปล"
                                SubTab.BURN -> "ฝังซับ"
                            },
                        )
                    },
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            when (tab) {
                SubTab.STT -> {
                    Text(
                        "ถอดเสียงจากไฟล์วิดีโอ/เสียงในเครื่อง (Whisper base) — ไม่ส่งเสียงออกนอกเครื่อง",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LabeledField("พาธไฟล์วิดีโอ/เสียง", sttPath, { sttPath = it })
                    Text("ภาษาเสียง", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        listOf("auto", "th", "en").forEach { lang ->
                            OutlinedButton(onClick = { sttLang = lang }, enabled = !sttBusy) {
                                Text(if (lang == sttLang) "●$lang" else lang)
                            }
                        }
                    }
                    when (val modelStatus = sttModelStatus) {
                        is WhisperStatus.Ready -> {
                            Text("โมเดลพร้อมแล้ว (${modelStatus.bytes / 1024 / 1024}MB) • เอนจิน ${sttEngineState.name}")
                        }
                        else -> {
                            Text("ต้องดาวน์โหลดโมเดลก่อน (~148MB ครั้งเดียว ใช้ตลอดไป)")
                            Button(onClick = ::downloadSttModel, enabled = !sttBusy) {
                                Text("ดาวน์โหลดโมเดล")
                            }
                        }
                    }
                    if (sttDlProgress != null) {
                        val progress = sttDlProgress
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Button(
                        onClick = ::transcribeFile,
                        enabled = !sttBusy && sttModelStatus is WhisperStatus.Ready && sttPath.isNotBlank(),
                    ) { Text("ถอดเสียง") }
                    if (sttBusy && sttDlProgress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    sttStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    sttResult?.let { result ->
                        OutputBlock("($sttCues คิว)\n${result.take(2000)}")
                        OutlinedButton(onClick = {
                            transcript = result
                            durationMs = sttDurationMs.toString()
                            mediaPath = sttPath.trim()
                            tab = SubTab.MAKE
                        }) { Text("ส่งไปทำซับ") }
                    }
                }
                SubTab.MAKE -> {
                    Text(
                        "วางบทพูด (เว้นบรรทัดว่างคั่นแต่ละคิว) — หรือถอดจากไฟล์ในแท็บ “ถอดเสียง”",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextField(
                        value = transcript, onValueChange = { transcript = it },
                        label = { Text("บทพูด") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    )
                    LabeledField("พาธมีเดีย (ไม่บังคับ)", mediaPath, { mediaPath = it })
                    LabeledField("ความยาว ms (ไม่บังคับ)", durationMs, { durationMs = it })
                    OutlinedButton(onClick = {
                        val args = mutableMapOf("transcript" to transcript.trim())
                        if (mediaPath.isNotBlank()) args["mediaPath"] = mediaPath.trim()
                        if (durationMs.isNotBlank()) args["durationMs"] = durationMs.trim()
                        if (src.isNotBlank()) args["dst"] = src.trim()
                        run("subtitle", "make", args)
                    }, enabled = !busy && transcript.isNotBlank()) { Text("ทำซับ (.srt)") }
                }
                SubTab.PARSE -> {
                    LabeledField("พาธไฟล์ .srt", src, { src = it })
                    OutlinedButton(onClick = {
                        run("subtitle", "parse", mapOf("path" to src.trim()))
                    }, enabled = !busy && src.isNotBlank()) { Text("อ่านซับ") }
                }
                SubTab.SHIFT -> {
                    Text("ไฟล์: ${src.ifBlank { "(ใส่พาธในแท็บอ่านก่อน หรือพิมพ์ด้านล่าง)" }}")
                    LabeledField("พาธไฟล์ .srt", src, { src = it })
                    LabeledField("เลื่อน ms (เช่น 500 / -1000)", offsetMs, { offsetMs = it })
                    OutlinedButton(onClick = {
                        run("subtitle", "shift", mapOf("src" to src.trim(), "offsetMs" to offsetMs.trim()))
                    }, enabled = !busy && src.isNotBlank() && offsetMs.isNotBlank()) { Text("เลื่อนเวลา") }
                }
                SubTab.TRANSLATE -> {
                    Text("ไฟล์: ${src.ifBlank { "(ใส่พาธในแท็บอ่านก่อน หรือพิมพ์ด้านล่าง)" }}")
                    LabeledField("พาธไฟล์ .srt", src, { src = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        listOf("th-en", "en-th").forEach { d ->
                            OutlinedButton(onClick = { direction = d }, enabled = !busy) {
                                Text(if (d == direction) "●$d" else d)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        listOf("dict", "llm").forEach { e ->
                            OutlinedButton(onClick = { engine = e }, enabled = !busy) {
                                Text(if (e == engine) "●$e" else e)
                            }
                        }
                    }
                    Text("llm = ใช้โมเดลที่ผู้ใช้ตั้งค่าไว้ (ต้องมีคีย์)", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        run("subtitle", "translate", mapOf("src" to src.trim(), "direction" to direction, "engine" to engine))
                    }, enabled = !busy && src.isNotBlank()) { Text("แปลซับ") }
                }
                SubTab.BURN -> {
                    LabeledField("พาธวิดีโอ", videoSrc, { videoSrc = it })
                    LabeledField("พาธไฟล์ .srt", srtPath, { srtPath = it })
                    OutlinedButton(onClick = {
                        run("subtitle", "burn", mapOf("src" to videoSrc.trim(), "srt" to srtPath.trim()))
                    }, enabled = !busy && videoSrc.isNotBlank() && srtPath.isNotBlank()) { Text("ฝังซับ") }
                }
            }
            message?.let { OutputBlock(it.take(2000)) }
        }
    }
}
