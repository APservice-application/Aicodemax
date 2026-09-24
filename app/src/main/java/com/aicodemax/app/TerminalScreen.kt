package com.aicodemax.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.aicodemax.core.common.fold
import com.aicodemax.tools.terminal.RunningHandle
import com.aicodemax.tools.terminal.TerminalSession
import com.aicodemax.ui.designsystem.AicodeColors
import com.aicodemax.ui.designsystem.AicodeRadii
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CP-143 interactive console (SCR-DEV-004): persistent real shell per
 * session (`cd`/`export` carry over), live-streamed output, Stop button,
 * start-folder + timeout controls, tap-to-reuse history. The terminal is
 * a Tool, not the brain — AI drives it through the gateway (same backend).
 */
@Composable
fun TerminalScreen(services: ServiceLocator, onHandToChat: (String) -> Unit = {}) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(services.compat.sessions()) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var liveHandle by remember { mutableStateOf<RunningHandle?>(null) }
    var cwdField by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<String>()) }
    var timeoutMs by remember { mutableStateOf(60_000L) }
    val scroll = rememberScrollState()

    LaunchedEffect(transcript.length) { scroll.scrollTo(scroll.maxValue) }

    fun append(text: String) {
        transcript = (transcript + text).takeLast(120_000)
    }

    fun refresh() {
        sessions = services.compat.sessions()
        if (activeId != null && sessions.none { it.id == activeId }) activeId = null
    }

    fun run() {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        running = true
        command = ""
        if (history.lastOrNull() != cmd) history = (history + cmd).takeLast(12)
        append("$ $cmd\n")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                services.compat.execLive(cmd, activeId, timeoutMs) { chunk ->
                    if (chunk.text.isNotEmpty()) append(chunk.text)
                    if (chunk.finished) {
                        append("[exit ${chunk.exitCode}]\n")
                        running = false
                        liveHandle = null
                        refresh()
                    }
                }
            }
            outcome.fold(
                onSuccess = {
                    activeId = it.sessionId
                    liveHandle = it.handle
                    refresh()
                },
                onFailure = {
                    append("❌ ${it.code}: ${it.message}\n")
                    running = false
                    refresh()
                },
            )
        }
    }

    fun stop() {
        liveHandle?.cancel()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Surface(
            shape = RoundedCornerShape(AicodeRadii.S),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                "เชลล์จริงในแอป — cd/export อยู่ข้ามคำสั่งในเซสชันเดียวกัน • หยุดได้ • timeout ฆ่าแล้วเปิดเชลล์ใหม่",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(spacing.sm),
            )
        }
        SessionRow(
            sessions = sessions,
            activeId = activeId,
            onSelect = { activeId = it },
            onNew = {
                services.compat.openSession("dev", cwdField.ifBlank { null }).fold(
                    onSuccess = { activeId = it.id },
                    onFailure = { append("❌ ${it.message}\n") },
                )
                refresh()
            },
            onClose = {
                services.compat.closeSession(it)
                refresh()
            },
        )
        // Console transcript (dark terminal tokens, auto-scrolls).
        Surface(
            shape = RoundedCornerShape(AicodeRadii.M),
            color = AicodeColors.TerminalBackground,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Text(
                text = transcript.ifBlank { "(พิมพ์คำสั่งด้านล่าง — เช่น ls, pwd, echo สวัสดี)" },
                style = MaterialTheme.typography.bodySmall,
                color = AicodeColors.TerminalForeground,
                modifier = Modifier.padding(spacing.sm).verticalScroll(scroll),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            TextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("$ คำสั่ง…") },
                shape = RoundedCornerShape(AicodeRadii.M),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AicodeColors.TerminalBackground,
                    unfocusedContainerColor = AicodeColors.TerminalBackground,
                    focusedTextColor = AicodeColors.TerminalForeground,
                    unfocusedTextColor = AicodeColors.TerminalForeground,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { run() }),
            )
            if (running) {
                Button(onClick = { stop() }) { Text("หยุด") }
            } else {
                Button(onClick = { run() }) { Text("รัน") }
            }
        }
        // History (tap to reuse).
        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                for (past in history.asReversed()) {
                    TextButton(onClick = { command = past }, enabled = !running) {
                        Text(past.take(24), maxLines = 1)
                    }
                }
            }
        }
        // Start folder + timeout.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            TextField(
                value = cwdField,
                onValueChange = { cwdField = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("โฟลเดอร์เริ่ม (ว่าง = workspace)") },
                shape = RoundedCornerShape(AicodeRadii.M),
            )
            for ((label, ms) in listOf("30วิ" to 30_000L, "60วิ" to 60_000L, "5นาที" to 300_000L)) {
                TextButton(onClick = { timeoutMs = ms }, enabled = !running) {
                    Text(if (ms == timeoutMs) "●$label" else label)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            TextButton(onClick = { transcript = "" }) { Text("ล้างจอ") }
            TextButton(
                onClick = { onHandToChat("อธิบายผลลัพธ์คำสั่งนี้:\n$transcript".take(1000)) },
                enabled = transcript.isNotBlank(),
            ) { Text("🤖 ถาม AI") }
        }
    }
}

@Composable
private fun SessionRow(
    sessions: List<TerminalSession>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onClose: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        item(key = "__new__") {
            TextButton(onClick = onNew) { Text("＋ เซสชันใหม่") }
        }
        items(sessions, key = { it.id }) { session ->
            Surface(
                shape = RoundedCornerShape(AicodeRadii.S),
                color = if (session.id == activeId) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onSelect(session.id) }) {
                        Text("${session.title} • ${session.state.name}")
                    }
                    TextButton(onClick = { onClose(session.id) }) { Text("✕") }
                }
            }
        }
    }
}
