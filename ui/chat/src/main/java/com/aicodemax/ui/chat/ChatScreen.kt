package com.aicodemax.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDrawerState
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aicodemax.data.conversations.ChatMessage
import com.aicodemax.data.conversations.Conversation
import com.aicodemax.data.conversations.MessageRole
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.StatusKind
import com.aicodemax.ui.designsystem.statusColor
import kotlinx.coroutines.launch

@Composable
fun ChatRoute(
    viewModel: ChatViewModel,
    onOpenTasks: () -> Unit = {},
    workingSet: com.aicodemax.core.state.WorkingSetStore? = null,
) {
    val state by viewModel.state.collectAsState()
    val pendingHandoff = workingSet?.workingSet?.collectAsState()?.value?.pendingPrompt
    val conversations by viewModel.conversationsList.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // CP-60 voice: mic transcript lands in the composer via pendingPrompt.
    val context = LocalContext.current
    var micText by remember { mutableStateOf<String?>(null) }
    var micDenied by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.voiceInput(onText = { micText = it }, onError = { micText = null })
        } else {
            micDenied = true
        }
    }
    fun onMic() {
        micDenied = false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.voiceInput(onText = { micText = it }, onError = { micText = null })
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.ensureOpen()
        viewModel.refreshConversations()
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ConversationDrawer(
                    conversations = conversations,
                    activeId = state.conversationId,
                    onNew = {
                        scope.launch { drawerState.close() }
                        viewModel.newChat()
                    },
                    onSelect = { id ->
                        scope.launch { drawerState.close() }
                        viewModel.openConversation(id)
                    },
                    onDelete = { id -> viewModel.deleteConversation(id) },
                )
            }
        },
        content = {
            ChatScreen(
                messages = state.messages,
                sending = state.sending,
                error = state.error
                    ?: if (micDenied) "ต้องอนุญาตไมโครโฟนก่อนถึงจะใช้เสียงได้ครับ" else null,
                lastTaskId = state.lastTaskId,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onMenu = { scope.launch { drawerState.open() } },
                onNewChat = viewModel::newChat,
                onOpenTasks = onOpenTasks,
                pendingPrompt = pendingHandoff ?: micText,
                onPromptConsumed = { workingSet?.consumePrompt(); micText = null },
                onMic = ::onMic,
                onSpeak = viewModel::speak,
            )
        },
    )
}

@Composable
private fun ConversationDrawer(
    conversations: List<Conversation>,
    activeId: String?,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.padding(spacing.sm)) {
        Text(
            text = "บทสนทนา",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(spacing.sm),
        )
        TextButton(onClick = onNew) { Text("＋ แชทใหม่") }
        if (conversations.isEmpty()) {
            Text(
                text = "ยังไม่มีบทสนทนาที่บันทึก",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(spacing.sm),
            )
        }
        for (conv in conversations) {
            NavigationDrawerItem(
                label = { Text(conv.title.ifBlank { "(ไม่มีชื่อ)" }, maxLines = 1) },
                selected = conv.id == activeId,
                onClick = { onSelect(conv.id) },
                badge = { TextButton(onClick = { onDelete(conv.id) }) { Text("ลบ") } },
            )
        }
    }
}

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    sending: Boolean,
    error: String?,
    lastTaskId: String? = null,
    onSend: (String) -> Unit,
    onStop: () -> Unit = {},
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    onOpenTasks: () -> Unit = {},
    pendingPrompt: String? = null,
    onPromptConsumed: () -> Unit = {},
    onMic: () -> Unit = {},
    onSpeak: (String) -> Unit = {},
) {
    val spacing = LocalSpacing.current
    var input by remember { mutableStateOf("") }

    // Cross-tool handoff (CP-47): another tool handed us a prompt — prefill once.
    LaunchedEffect(pendingPrompt) {
        if (!pendingPrompt.isNullOrBlank()) {
            input = pendingPrompt
            onPromptConsumed()
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(messages.size - 1) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onMenu) { Text("☰") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onNewChat) { Text("＋ แชทใหม่") }
        }
        if (messages.isEmpty()) {
            EmptyChat(modifier = Modifier.weight(1f), onSuggest = onSend)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                items(messages, key = { it.id }) { message -> MessageBubble(message, onSpeak) }
                if (sending) {
                    item(key = "__sending__") { SendingRow() }
                }
            }
        }
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = spacing.md),
            )
        }
        if (lastTaskId != null && !sending) {
            TaskLinkCard(onOpen = onOpenTasks)
        }
        ChatComposer(
            value = input,
            onValueChange = { input = it },
            sending = sending,
            onSend = {
                onSend(input)
                input = ""
            },
            onStop = onStop,
            onMic = onMic,
        )
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier, onSuggest: (String) -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxWidth().padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("สวัสดี 👋", style = MaterialTheme.typography.titleLarge)
        Text(
            "วันนี้ให้ช่วยอะไร?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        for (hint in listOf("สร้างไฟล์ notes.txt: สวัสดี", "ดูไฟล์", "อ่านไฟล์ notes.txt")) {
            TextButton(onClick = { onSuggest(hint) }) { Text(hint) }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onSpeak: (String) -> Unit = {}) {
    when (message.role) {
        MessageRole.USER -> Row(
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        MessageRole.AI -> Row(
            modifier = Modifier.fillMaxWidth().padding(end = 48.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                MarkdownText(text = message.text, modifier = Modifier.padding(12.dp))
            }
            TextButton(onClick = { onSpeak(message.text) }) { Text("\uD83D\uDD0A") }
        }
        MessageRole.STATUS -> StatusCard(message.text)
        MessageRole.SYSTEM -> Text(
            text = message.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun StatusCard(text: String) {
    val color = statusColor(StatusKind.INFO)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        MarkdownText(text = text, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun TaskLinkCard(onOpen: () -> Unit) {
    val spacing = LocalSpacing.current
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "📋 งานล่าสุดของ AI",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = spacing.sm),
            )
            TextButton(onClick = onOpen) { Text("เปิดดู") }
        }
    }
}

@Composable
private fun SendingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.padding(4.dp),
            strokeWidth = 2.dp,
        )
        Text("AI กำลังทำงาน…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("เขียนสิ่งที่ต้องการ…") },
            singleLine = false,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        TextButton(onClick = onMic, enabled = !sending) { Text("\uD83C\uDFA4") }
        if (sending) {
            Button(onClick = onStop) {
                Text("หยุด")
            }
        } else {
            Button(onClick = onSend, enabled = value.isNotBlank()) {
                Text("ส่ง")
            }
        }
    }
}
