package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.ui.designsystem.LabeledField
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.OutputBlock
import kotlinx.coroutines.launch

private enum class SubTab { MAKE, PARSE, SHIFT, TRANSLATE, BURN }

/**
 * CP-137 subtitle studio (SCR-SUB-001..006): tabbed make/parse/shift/
 * translate/burn over subtitle.* tools. Honest note: make() formats a pasted
 * transcript — there is no on-device STT engine yet.
 */
@Composable
fun SubtitleScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(SubTab.MAKE) }
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

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
            for (value in SubTab.values()) {
                Tab(
                    selected = tab == value,
                    onClick = { tab = value },
                    text = {
                        Text(
                            when (value) {
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
                SubTab.MAKE -> {
                    Text(
                        "วางบทพูด (เว้นบรรทัดว่างคั่นแต่ละคิว) — ยังไม่มี STT อัตโนมัติ",
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
