package com.aicodemax.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aicodemax.data.conversations.ChatMessage
import com.aicodemax.data.conversations.Conversation
import com.aicodemax.data.conversations.MessageRole
import com.aicodemax.ui.designsystem.AicodeRadii
import com.aicodemax.ui.designsystem.AicodeSize
import com.aicodemax.ui.designsystem.ConfirmDialog
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.ShimmerSkeleton
import com.aicodemax.ui.designsystem.StatusKind
import com.aicodemax.ui.designsystem.statusColor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** CP-135: model option for the chat model selector (mapped by the app layer). */
data class ChatModelOption(
    val id: String,
    val name: String,
    val statusLabel: String,
    val active: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoute(
    viewModel: ChatViewModel,
    onOpenTasks: () -> Unit = {},
    workingSet: com.aicodemax.core.state.WorkingSetStore? = null,
    models: List<ChatModelOption> = emptyList(),
    onOpenModels: () -> Unit = {},
    // CP-139: first-run one-tap default-model download.
    showModelDownload: Boolean = false,
    modelDownload: ModelDownloadUi? = null,
    onDownloadDefault: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val ws by workingSet?.workingSet?.collectAsState()
        ?: remember { mutableStateOf<com.aicodemax.core.state.WorkingSet?>(null) }
    val pendingHandoff = ws?.pendingPrompt
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var micText by remember { mutableStateOf<String?>(null) }
    var micDenied by remember { mutableStateOf(false) }
    var micError by remember { mutableStateOf<String?>(null) }
    var attachments by remember(state.conversationId) { mutableStateOf<List<String>>(emptyList()) }
    var showModels by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.voiceInput(onText = { micText = it }, onError = { micError = it })
        } else {
            micDenied = true
        }
    }
    fun onMic() {
        micDenied = false
        micError = null
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.voiceInput(onText = { micText = it }, onError = { micError = it })
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val copied = uris.mapNotNull { copyUriIntoStorage(context, it) }
            withContext(Dispatchers.Main) { attachments = attachments + copied }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.ensureOpen()
        viewModel.refreshConversations()
    }
    ChatScreen(
        messages = state.messages,
        sending = state.sending,
        error = state.error ?: micError
            ?: if (micDenied) "ต้องอนุญาตไมโครโฟนก่อนถึงจะใช้เสียงได้ครับ" else null,
        lastTaskId = state.lastTaskId,
        onSend = { text ->
            viewModel.send(text, attachments)
            attachments = emptyList()
        },
        onStop = viewModel::stop,
        onOpenModels = { showModels = true },
        activeModelName = models.firstOrNull { it.active }?.name,
        onOpenTasks = onOpenTasks,
        pendingPrompt = pendingHandoff ?: micText,
        onPromptConsumed = { workingSet?.consumePrompt(); micText = null },
        onMic = ::onMic,
        onSpeak = viewModel::speak,
        phase = state.phase,
        runningTool = state.runningTool,
        onRetry = viewModel::retryLast,
        attachments = attachments,
        onAttach = { pickLauncher.launch("*/*") },
        onRemoveAttachment = { path -> attachments = attachments - path },
        projectChip = ws?.projectId,
        fileChipCount = ws?.selectedFiles?.size ?: 0,
        onClearProject = { workingSet?.setProject(null) },
        onClearFiles = { workingSet?.setSelectedFiles(emptyList()) },
        showModelDownload = showModelDownload,
        modelDownload = modelDownload,
        onDownloadDefault = onDownloadDefault,
    )
    if (showModels) {
        ModelSelectorSheet(
            models = models,
            onDismiss = { showModels = false },
            onOpenModels = {
                showModels = false
                onOpenModels()
            },
        )
    }
}

/**
 * CP-135: copies a picked content URI into app-private attachment storage so the
 * path stays readable by tools after the picker grant expires. Returns the
 * absolute path, or null when the copy fails.
 */
private fun copyUriIntoStorage(context: android.content.Context, uri: Uri): String? {
    return try {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: "attachment-${System.currentTimeMillis()}"
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val dest = File(dir, "${System.currentTimeMillis()}-$safe")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: return null
        dest.absolutePath
    } catch (_: Exception) {
        null
    }
}

/**
 * CP-135 conversation section of the shell drawer (§12): new chat + history
 * with rename (long-press) and delete (confirmed).
 */
@Composable
fun ConversationDrawerContent(
    conversations: List<Conversation>,
    activeId: String?,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    Column(modifier = modifier) {
        TextButton(
            onClick = onNew,
            modifier = Modifier.heightIn(min = AicodeSize.MinTouch),
        ) { Text("＋ แชทใหม่") }
        if (conversations.isEmpty()) {
            Text(
                text = "ยังไม่มีบทสนทนาที่บันทึก",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(spacing.sm),
            )
        }
        for (conv in conversations) {
            ConversationRow(
                conversation = conv,
                selected = conv.id == activeId,
                onSelect = { onSelect(conv.id) },
                onRename = {
                    renameTarget = conv
                    renameText = conv.title
                },
                onDelete = { deleteTarget = conv },
            )
        }
    }
    val rename = renameTarget
    if (rename != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("เปลี่ยนชื่อแชท") },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(rename.id, renameText)
                        renameTarget = null
                    },
                ) { Text("บันทึก") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("ยกเลิก") }
            },
        )
    }
    val dying = deleteTarget
    if (dying != null) {
        ConfirmDialog(
            title = "ลบแชทนี้?",
            explanation = "“${dying.title.ifBlank { "(ไม่มีชื่อ)" }}” จะถูกลบถาวรพร้อมข้อความทั้งหมด",
            confirmLabel = "ลบ",
            onConfirm = {
                onDelete(dying.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            destructive = true,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(AicodeRadii.M),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalSpacing.current.sm, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .combinedClickable(onClick = onSelect, onLongClick = onRename)
                .padding(start = LocalSpacing.current.md)
                .heightIn(min = AicodeSize.MinTouch),
        ) {
            Text(
                conversation.title.ifBlank { "(ไม่มีชื่อ)" },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDelete) { Text("ลบ") }
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
    onOpenModels: () -> Unit = {},
    activeModelName: String? = null,
    onOpenTasks: () -> Unit = {},
    pendingPrompt: String? = null,
    onPromptConsumed: () -> Unit = {},
    onMic: () -> Unit = {},
    onSpeak: (String) -> Unit = {},
    phase: ChatPhase = ChatPhase.READY,
    runningTool: String? = null,
    onRetry: () -> Unit = {},
    attachments: List<String> = emptyList(),
    onAttach: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    projectChip: String? = null,
    fileChipCount: Int = 0,
    onClearProject: () -> Unit = {},
    onClearFiles: () -> Unit = {},
    // CP-139: first-run one-tap default-model download.
    showModelDownload: Boolean = false,
    modelDownload: ModelDownloadUi? = null,
    onDownloadDefault: () -> Unit = {},
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

    LaunchedEffect(messages.size, sending) {
        if (messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(messages.size - 1 + if (sending) 1 else 0) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // CP-135 context chips (§24): model + project + selected files.
        ContextChipRow(
            activeModelName = activeModelName,
            onOpenModels = onOpenModels,
            projectChip = projectChip,
            fileChipCount = fileChipCount,
            onClearProject = onClearProject,
            onClearFiles = onClearFiles,
        )
        if (messages.isEmpty()) {
            EmptyChat(
                modifier = Modifier.weight(1f),
                onSuggest = onSend,
                showModelDownload = showModelDownload,
                modelDownload = modelDownload,
                onDownloadDefault = onDownloadDefault,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                items(messages, key = { it.id }) { message -> MessageBubble(message, onSpeak) }
                if (sending) {
                    item(key = "__sending__") { SendingRow(phase, runningTool, onOpenTasks) }
                }
            }
        }
        if (error != null) {
            Row(
                modifier = Modifier.padding(horizontal = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text("ลองใหม่") }
            }
        }
        if (lastTaskId != null && !sending) {
            TaskLinkCard(onOpen = onOpenTasks)
        }
        if (attachments.isNotEmpty()) {
            PendingAttachments(
                attachments = attachments,
                onRemove = onRemoveAttachment,
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
            onStop = onStop,
            onMic = onMic,
            onAttach = onAttach,
            sendEnabled = input.isNotBlank() || attachments.isNotEmpty(),
        )
    }
}

@Composable
private fun ContextChipRow(
    activeModelName: String?,
    onOpenModels: () -> Unit,
    projectChip: String?,
    fileChipCount: Int,
    onClearProject: () -> Unit,
    onClearFiles: () -> Unit,
) {
    val spacing = LocalSpacing.current
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item(key = "model") {
            AssistChip(
                onClick = onOpenModels,
                label = { Text("🤖 ${activeModelName ?: "เลือกโมเดล"}") },
            )
        }
        if (projectChip != null) {
            item(key = "project") {
                AssistChip(
                    onClick = onClearProject,
                    label = { Text("📁 $projectChip ✕") },
                )
            }
        }
        if (fileChipCount > 0) {
            item(key = "files") {
                AssistChip(
                    onClick = onClearFiles,
                    label = { Text("📎 $fileChipCount ไฟล์ ✕") },
                )
            }
        }
    }
}

@Composable
private fun EmptyChat(
    modifier: Modifier = Modifier,
    onSuggest: (String) -> Unit,
    showModelDownload: Boolean = false,
    modelDownload: ModelDownloadUi? = null,
    onDownloadDefault: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxWidth().padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // CP-139: first-run one-tap download card above the greeting.
        if (showModelDownload && modelDownload != null) {
            ModelDownloadBanner(ui = modelDownload, onDownload = onDownloadDefault)
        }
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

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
private fun MessageBubble(message: ChatMessage, onSpeak: (String) -> Unit = {}) {
    when (message.role) {
        MessageRole.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth(AicodeSize.UserBubbleWidth)) {
                Surface(
                    shape = RoundedCornerShape(AicodeRadii.ChatBubble),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (message.text.isNotBlank()) {
                            Text(
                                text = message.text,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        for (path in message.attachments) {
                            Text(
                                text = "📎 " + path.substringAfterLast('/').substringAfterLast('\\'),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
                Text(
                    text = timeFormat.format(Date(message.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        MessageRole.AI -> Row(
            modifier = Modifier.fillMaxWidth(AicodeSize.AiBubbleWidth),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Surface(
                    shape = RoundedCornerShape(AicodeRadii.ChatBubble),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    MarkdownText(text = message.text, modifier = Modifier.padding(12.dp))
                }
                Text(
                    text = timeFormat.format(Date(message.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
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
        shape = RoundedCornerShape(AicodeRadii.M),
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
        shape = RoundedCornerShape(AicodeRadii.S),
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
private fun SendingRow(phase: ChatPhase, runningTool: String?, onOpenTasks: () -> Unit) {
    // CP-116: skeleton shimmer (§86) instead of a spinner.
    // CP-132: phase label (spec §33). CP-135: tool card for RUNNING_TOOL.
    val label = when (phase) {
        ChatPhase.THINKING -> "AI กำลังคิด…"
        ChatPhase.RUNNING_TOOL -> "AI กำลังใช้ ${runningTool ?: "เครื่องมือ"}…"
        ChatPhase.RETRY -> "AI กำลังลองใหม่…"
        ChatPhase.LISTENING -> "🎤 กำลังฟัง… พูดได้เลย"
        ChatPhase.READY -> "AI กำลังทำงาน…"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (phase == ChatPhase.RUNNING_TOOL && runningTool != null) {
            ToolCard(toolId = runningTool, onOpenTasks = onOpenTasks)
        } else {
            ShimmerSkeleton(lines = 2, modifier = Modifier.padding(end = 48.dp))
        }
    }
}

/** CP-135 tool card (§26): which tool is running + progress + jump to task. */
@Composable
private fun ToolCard(toolId: String, onOpenTasks: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(AicodeRadii.M),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(AicodeSize.AiBubbleWidth),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(LocalSpacing.current.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("🔧 $toolId", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            TextButton(onClick = onOpenTasks) { Text("ดูงาน") }
        }
    }
}

@Composable
private fun PendingAttachments(attachments: List<String>, onRemove: (String) -> Unit) {
    val spacing = LocalSpacing.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        items(attachments, key = { it }) { path ->
            AssistChip(
                onClick = { onRemove(path) },
                label = { Text("📎 " + path.substringAfterLast('/').substringAfterLast('\\') + " ✕") },
            )
        }
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
    onAttach: () -> Unit = {},
    sendEnabled: Boolean = true,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        TextButton(
            onClick = onAttach,
            enabled = !sending,
            modifier = Modifier.heightIn(min = AicodeSize.MinTouch),
        ) { Text("＋", style = MaterialTheme.typography.titleLarge) }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("เขียนสิ่งที่ต้องการ…") },
            singleLine = false,
            maxLines = 4,
            shape = RoundedCornerShape(AicodeRadii.XXL),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (sendEnabled && !sending) onSend() }),
        )
        TextButton(
            onClick = onMic,
            enabled = !sending,
            modifier = Modifier.heightIn(min = AicodeSize.MinTouch),
        ) { Text("\uD83C\uDFA4") }
        if (sending) {
            Button(onClick = onStop, modifier = Modifier.heightIn(min = AicodeSize.MinTouch)) {
                Text("หยุด")
            }
        } else {
            Button(
                onClick = onSend,
                enabled = sendEnabled,
                modifier = Modifier.heightIn(min = AicodeSize.MinTouch),
            ) {
                Text("ส่ง")
            }
        }
    }
}

/** CP-135 model selector sheet (§24/§54): active model + manage entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectorSheet(
    models: List<ChatModelOption>,
    onDismiss: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text("โมเดล", style = MaterialTheme.typography.titleMedium)
            if (models.isEmpty()) {
                Text(
                    "ยังไม่มีโมเดลที่ลงทะเบียน",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            for (option in models) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(option.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            option.statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (option.active) {
                        Text("✓ ใช้อยู่", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Button(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
                Text("จัดการโมเดล")
            }
        }
    }
}
