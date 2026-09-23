package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.launch

/**
 * Subtitle Studio (CP-112): full UI for the subtitle tool (กฎข้อ 5).
 * make (transcript→SRT) / parse / shift / burn / translate (dict+LLM).
 * Honest note: make() formats a pasted transcript — there is no on-device STT engine yet.
 */
@Composable
fun SubtitleScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
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

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                Text("สตูดิโอซับไตเติล", style = MaterialTheme.typography.titleMedium)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Text("ทำซับจากบทพูด", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        "วางบทพูด (เว้นบรรทัดว่างคั่นแต่ละคิว) — ยังไม่มี STT อัตโนมัติ",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextField(value = transcript, onValueChange = { transcript = it }, label = { Text("บทพูด") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    TextField(value = mediaPath, onValueChange = { mediaPath = it }, label = { Text("พาธมีเดีย (ไม่บังคับ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = durationMs, onValueChange = { durationMs = it }, label = { Text("ความยาว ms (ไม่บังคับ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        val args = mutableMapOf("transcript" to transcript.trim())
                        if (mediaPath.isNotBlank()) args["mediaPath"] = mediaPath.trim()
                        if (durationMs.isNotBlank()) args["durationMs"] = durationMs.trim()
                        if (src.isNotBlank()) args["dst"] = src.trim()
                        run("subtitle", "make", args)
                    }, enabled = !busy && transcript.isNotBlank()) { Text("ทำซับ (.srt)") }
                }
            }
            item {
                Text("อ่านไฟล์ซับ", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextField(value = src, onValueChange = { src = it }, label = { Text("พาธไฟล์ .srt") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        run("subtitle", "parse", mapOf("path" to src.trim()))
                    }, enabled = !busy && src.isNotBlank()) { Text("อ่านซับ") }
                }
            }
            item {
                Text("เลื่อนเวลาซับ", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextField(value = offsetMs, onValueChange = { offsetMs = it }, label = { Text("เลื่อน ms (เช่น 500 / -1000)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        run("subtitle", "shift", mapOf("src" to src.trim(), "offsetMs" to offsetMs.trim()))
                    }, enabled = !busy && src.isNotBlank() && offsetMs.isNotBlank()) { Text("เลื่อนเวลา") }
                }
            }
            item {
                Text("แปลซับ ไทย↔อังกฤษ", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
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
            }
            item {
                Text("ฝังซับลงวิดีโอ", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextField(value = videoSrc, onValueChange = { videoSrc = it }, label = { Text("พาธวิดีโอ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = srtPath, onValueChange = { srtPath = it }, label = { Text("พาธไฟล์ .srt") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        run("subtitle", "burn", mapOf("src" to videoSrc.trim(), "srt" to srtPath.trim()))
                    }, enabled = !busy && videoSrc.isNotBlank() && srtPath.isNotBlank()) { Text("ฝังซับ") }
                }
            }
        }
    }
}
