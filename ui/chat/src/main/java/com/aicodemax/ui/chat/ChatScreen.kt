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
fun ChatRoute(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val conversations by viewModel.conversationsList.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
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
                error = state.error,
                onSend = viewModel::send,
                onMenu = { scope.launch { drawerState.open() } },
                onNewChat = viewModel::newChat,
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
    onSend: (String) -> Unit,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var input by remember { mutableStateOf("") }
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
                items(messages, key = { it.id }) { message -> MessageBubble(message) }
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
        ChatComposer(
            value = input,
            onValueChange = { input = it },
            sending = sending,
            onSend = {
                onSend(input)
                input = ""
            },
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
private fun MessageBubble(message: ChatMessage) {
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
        MessageRole.AI -> Row(modifier = Modifier.fillMaxWidth().padding(end = 48.dp)) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(text = message.text, modifier = Modifier.padding(12.dp))
            }
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
        Text(text = text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
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
        Button(onClick = onSend, enabled = !sending && value.isNotBlank()) {
            Text("ส่ง")
        }
    }
}
