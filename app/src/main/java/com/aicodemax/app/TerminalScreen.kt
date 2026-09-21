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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.aicodemax.core.common.fold
import com.aicodemax.tools.terminal.TerminalSession
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dev Workspace terminal (CP-33). The terminal is a compatibility engine, not
 * the core: until the Phase-16 runtime lands, commands honestly report
 * TERMINAL_UNWIRED instead of faking a shell.
 */
@Composable
fun TerminalScreen(services: ServiceLocator) {
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
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                "โหมดนักพัฒนา — terminal เป็น compatibility engine (PTY runtime จริงมาใน Phase 16)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(spacing.sm),
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
                placeholder = { Text("คำสั่ง…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { run() }),
            )
            Button(onClick = { run() }, enabled = !running) { Text("รัน") }
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Text(
                text = output.ifBlank { "(ยังไม่มีผลลัพธ์)" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(spacing.sm).verticalScroll(rememberScrollState()),
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
                shape = MaterialTheme.shapes.small,
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
