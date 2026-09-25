package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.terminal.PtyHandle
import com.aicodemax.ui.designsystem.AicodeColors
import com.aicodemax.ui.designsystem.AicodeRadii
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PtyModeBar(ptyMode: Boolean, onMode: (Boolean) -> Unit) {
    val spacing = LocalSpacing.current
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
        TextButton(onClick = { onMode(false) }) { Text(if (!ptyMode) "●⌨ คำสั่ง" else "⌨ คำสั่ง") }
        TextButton(onClick = { onMode(true) }) { Text(if (ptyMode) "●🖥 PTY เชิงโต้ตอบ" else "🖥 PTY เชิงโต้ตอบ") }
    }
}

/**
 * CP-32 interactive PTY console (§27): a real pseudo-terminal from the
 * app's own managed native runtime — python REPL, ssh, vim all work.
 * No Termux install needed. arm64 APK only (honest when unpacked).
 */
@Composable
fun PtyConsole(services: ServiceLocator, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var transcript by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf<PtyHandle?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    LaunchedEffect(transcript.length) { scroll.scrollTo(scroll.maxValue) }
    DisposableEffect(Unit) {
        onDispose { handle?.let { services.pty.close(it) } }
    }

    fun append(text: String) {
        transcript = (transcript + text).takeLast(120_000)
    }

    fun start() {
        val port = services.pty
        if (!port.available) {
            message = port.unavailableReason
            return
        }
        if (handle != null) return
        message = null
        scope.launch(Dispatchers.IO) {
            when (val opened = port.open(80, 24)) {
                is Outcome.Failure -> withContext(Dispatchers.Main) { message = opened.error.message }
                is Outcome.Success -> {
                    withContext(Dispatchers.Main) { handle = opened.value }
                    while (true) {
                        val cur = handle ?: break
                        when (val r = port.read(cur, 500)) {
                            is Outcome.Success -> {
                                val data = r.value
                                if (data == null) continue // idle timeout
                                if (data.isEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        append("\n(เชลล์จบแล้ว)\n")
                                        handle = null
                                    }
                                    break
                                }
                                val text = data.toString(Charsets.UTF_8)
                                withContext(Dispatchers.Main) { append(text) }
                            }
                            is Outcome.Failure -> {
                                withContext(Dispatchers.Main) {
                                    message = r.error.message
                                    handle = null
                                }
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    fun send(raw: String) {
        val cur = handle
        if (cur == null) {
            message = "เริ่ม PTY ก่อนครับ"
            return
        }
        scope.launch(Dispatchers.IO) {
            when (val r = services.pty.write(cur, raw.toByteArray())) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> withContext(Dispatchers.Main) { message = r.error.message }
            }
        }
    }

    fun stop() {
        val cur = handle
        handle = null
        if (cur != null) scope.launch(Dispatchers.IO) { services.pty.close(cur) }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (!services.pty.available) {
            Text(
                services.pty.unavailableReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Surface(
            shape = RoundedCornerShape(AicodeRadii.M),
            color = AicodeColors.TerminalBackground,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Text(
                text = transcript.ifBlank { "(กดเริ่ม — เชลล์เชิงโต้ตอบจริง เช่น python3, ssh, vim)" },
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
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("พิมพ์แล้วส่ง…") },
                shape = RoundedCornerShape(AicodeRadii.M),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AicodeColors.TerminalBackground,
                    unfocusedContainerColor = AicodeColors.TerminalBackground,
                    focusedTextColor = AicodeColors.TerminalForeground,
                    unfocusedTextColor = AicodeColors.TerminalForeground,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotEmpty()) {
                        send(input + "\n")
                        input = ""
                    }
                }),
            )
            Button(onClick = {
                if (input.isNotEmpty()) {
                    send(input + "\n")
                    input = ""
                }
            }) { Text("ส่ง") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            if (handle == null) {
                Button(onClick = { start() }) { Text("▶ เริ่ม PTY") }
            } else {
                Button(onClick = { stop() }) { Text("⏹ หยุด") }
            }
            TextButton(onClick = { send("\u0003") }, enabled = handle != null) { Text("Ctrl+C") }
            TextButton(onClick = { transcript = "" }) { Text("ล้างจอ") }
        }
        WorkspaceMessage(message)
    }
}
