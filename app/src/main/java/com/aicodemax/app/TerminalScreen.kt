package com.aicodemax.app

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import com.aicodemax.core.common.fold
import com.aicodemax.tools.terminal.TerminalSession
import com.aicodemax.ui.designsystem.AicodeColors
import com.aicodemax.ui.designsystem.AicodeRadii
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CP-137 terminal re-skin (SCR-DEV-004): dark console theme, same compat
 * engine. The terminal is a Tool, not the brain: until the Phase-16 runtime
 * lands, commands honestly report TERMINAL_UNWIRED instead of faking a shell.
 */
@Composable
fun TerminalScreen(services: ServiceLocator, onHandToChat: (String) -> Unit = {}) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(services.compat.sessions()) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    fun refresh() {
        sessions = services.compat.sessions()
        if (activeId != null && sessions.none { it.id == activeId }) activeId = null
    }

    fun run() {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        running = true
        output = "$ $cmd\n…กำลังรัน…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                services.compat.exec(cmd, activeId)
            }
            result.fold(
                onSuccess = {
                    activeId = it.sessionId
                    output = "$ $cmd\n${it.stdout}${it.stderr}\n[exit ${it.exitCode}]"
                },
                onFailure = { output = "$ $cmd\n❌ ${it.code}: ${it.message}" },
            )
            refresh()
            running = false
        }
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
                "โหมดนักพัฒนา — terminal เป็น compatibility engine (PTY runtime จริงมาใน Phase 16)",
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
                services.compat.openSession("dev").fold(
                    onSuccess = { activeId = it.id },
                    onFailure = { output = "❌ ${it.message}" },
                )
                refresh()
            },
            onClose = {
                services.compat.closeSession(it)
                refresh()
            },
        )
        // Console output (dark terminal tokens).
        Surface(
            shape = RoundedCornerShape(AicodeRadii.M),
            color = AicodeColors.TerminalBackground,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Text(
                text = output.ifBlank { "(ยังไม่มีผลลัพธ์)" },
                style = MaterialTheme.typography.bodySmall,
                color = AicodeColors.TerminalForeground,
                modifier = Modifier.padding(spacing.sm).verticalScroll(rememberScrollState()),
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
            Button(onClick = { run() }, enabled = !running) { Text("รัน") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            TextButton(onClick = { output = "" }) { Text("ล้างจอ") }
            TextButton(
                onClick = { onHandToChat("อธิบายผลลัพธ์คำสั่งนี้:\n$output".take(1000)) },
                enabled = output.isNotBlank(),
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
