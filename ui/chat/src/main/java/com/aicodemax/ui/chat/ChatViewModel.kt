package com.aicodemax.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicodemax.ai.core.Orchestrator
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.data.conversations.ChatMessage
import com.aicodemax.data.conversations.Conversation
import com.aicodemax.data.conversations.ConversationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** CP-132: chat phase states (spec §33). CP-135: LISTENING for voice input. */
enum class ChatPhase { READY, THINKING, RUNNING_TOOL, RETRY, LISTENING }

data class ChatUiState(
    val conversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val lastTaskId: String? = null,
    val phase: ChatPhase = ChatPhase.READY,
    val runningTool: String? = null,
)

class ChatViewModel(
    private val orchestrator: Orchestrator,
    private val conversations: ConversationStore,
    private val tasks: com.aicodemax.ai.tasks.TaskEngine? = null,
    private val workingSet: com.aicodemax.core.state.WorkingSetStore? = null,
    private val monitor: com.aicodemax.core.resources.ResourceMonitor? = null,
    private val voice: com.aicodemax.tools.voice.VoicePort? = null,
    private val onStopAi: (() -> Unit)? = null,
    private val bus: com.aicodemax.core.state.EventBus? = null,
) : ViewModel() {
    private var sendJob: kotlinx.coroutines.Job? = null
    private var lastUserText: String? = null

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        // CP-132: tool activity on the bus → RUNNING_TOOL phase with the tool name.
        bus?.let { events ->
            viewModelScope.launch {
                events.events.collect { event ->
                    if (event is com.aicodemax.core.state.AppEvent.ToolOutput && _state.value.sending) {
                        _state.value = _state.value.copy(phase = ChatPhase.RUNNING_TOOL, runningTool = event.toolId)
                    }
                }
            }
        }
    }

    private val _conversationsList = MutableStateFlow<List<Conversation>>(emptyList())
    val conversationsList: StateFlow<List<Conversation>> = _conversationsList.asStateFlow()

    /** Opens the active conversation, creating one only when none exists. */
    fun ensureOpen() {
        if (_state.value.conversationId == null) openConversation(null)
    }

    fun refreshConversations() {
        viewModelScope.launch {
            _conversationsList.value = conversations.list().fold(
                onSuccess = { it },
                onFailure = { emptyList() },
            )
        }
    }

    fun openConversation(conversationId: String?) {
        viewModelScope.launch {
            val id = conversationId ?: conversations.createConversation("New chat").fold(
                onSuccess = { it.id },
                onFailure = { null },
            )
            if (id == null) {
                _state.value = _state.value.copy(error = "cannot create conversation")
                return@launch
            }
            refresh(id)
            refreshConversations()
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversations.delete(conversationId)
            refreshConversations()
            if (_state.value.conversationId == conversationId) openConversation(null)
        }
    }

    /** CP-135: rename a conversation (drawer long-press). */
    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            conversations.rename(conversationId, trimmed)
            refreshConversations()
        }
    }

    /** CP-135: send with optional attachment paths (stored on the USER message). */
    fun send(text: String, attachments: List<String> = emptyList()) {
        val names = attachments.map { it.substringAfterLast('/').substringAfterLast('\\') }
        val annotated = if (names.isEmpty()) text.trim()
        else (text.trim() + "\n[แนบไฟล์: " + names.joinToString(", ") + "]").trim()
        sendInternal(annotated, ChatPhase.THINKING, attachments)
    }

    /** CP-132: retry the last user message with the RETRY phase (spec §33). */
    fun retryLast() {
        val last = lastUserText
        if (last.isNullOrBlank() || _state.value.sending) return
        sendInternal(last, ChatPhase.RETRY)
    }

    private fun sendInternal(trimmed: String, phase: ChatPhase, attachments: List<String> = emptyList()) {
        if (trimmed.isEmpty()) return
        val id = _state.value.conversationId ?: return
        // CP-57: chat-handled intents (real, local — no task pipeline needed).
        when (com.aicodemax.ai.core.IntentParser.parse(trimmed).type) {
            com.aicodemax.ai.core.IntentType.STOP_TASK -> {
                stop()
                postStatus(id, "หยุดงานแล้วครับ")
                return
            }
            com.aicodemax.ai.core.IntentType.SYSTEM_STATUS -> {
                reportStatus(id)
                return
            }
            else -> Unit
        }
        lastUserText = trimmed
        _state.value = _state.value.copy(sending = true, error = null, phase = phase, runningTool = null)
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            when (val result = orchestrator.handleUserMessage(id, trimmed, attachments)) {
                is Outcome.Failure ->
                    _state.value = _state.value.copy(
                        sending = false, error = result.error.message,
                        phase = ChatPhase.READY, runningTool = null,
                    )
                is Outcome.Success -> {
                    val taskId = result.value.taskId
                    refresh(id, sending = false)
                    _state.value = _state.value.copy(
                        lastTaskId = taskId ?: _state.value.lastTaskId,
                        phase = ChatPhase.READY, runningTool = null,
                    )
                }
            }
        }
    }

    /** CP-34 pause-stop: halts the in-flight send and cancels its task. */
    fun stop() {
        sendJob?.cancel()
        sendJob = null
        stopVoice()
        try {
            onStopAi?.invoke()
        } catch (_: Exception) {
        }
        val taskId = _state.value.lastTaskId
        if (taskId != null) {
            tasks?.cancel(taskId, "stopped from chat")
        }
        _state.value = _state.value.copy(sending = false, phase = ChatPhase.READY, runningTool = null)
    }

    private fun postStatus(id: String, text: String) {
        viewModelScope.launch {
            conversations.appendMessage(
                id, com.aicodemax.data.conversations.MessageRole.STATUS, text,
            )
            refresh(id)
        }
    }

    private fun reportStatus(id: String) {
        val mon = monitor
        if (mon == null) {
            postStatus(id, "เครื่องนี้ยังไม่ต่อระบบวัดทรัพยากรครับ")
            return
        }
        _state.value = _state.value.copy(sending = true, error = null, phase = ChatPhase.THINKING)
        viewModelScope.launch {
            val text = mon.snapshot().fold(
                onSuccess = { snap ->
                    val mode = com.aicodemax.core.resources.ResourceModes.derive(snap)
                    val label = when {
                        mode.offline -> "ออฟไลน์"
                        mode.lowResource -> "ประหยัดทรัพยากร (${mode.reasons.joinToString(", ")})"
                        else -> "เต็มกำลัง"
                    }
                    "📊 สถานะระบบ: $label\n" +
                        "• RAM ว่าง ${snap.ramAvailableBytes / 1048576}MB / " +
                        "${snap.ramTotalBytes / 1048576}MB\n" +
                        "• พื้นที่ว่าง ${snap.storageAvailableBytes / 1073741824}GB\n" +
                        "• แบต ${snap.batteryPercent}% " +
                        (if (snap.batteryCharging) "(ชาร์จ)" else "") + "\n" +
                        "• เน็ต: " + if (snap.networkAvailable) "ต่ออยู่" else "ออฟไลน์"
                },
                onFailure = { "อ่านสถานะไม่ได้: ${it.message}" },
            )
            conversations.appendMessage(
                id, com.aicodemax.data.conversations.MessageRole.STATUS, text,
            )
            refresh(id, sending = false)
        }
    }

    fun newChat() = openConversation(null)

    /** CP-60: listens once on the mic and delivers the transcript to [onText]. */
    fun voiceInput(onText: (String) -> Unit, onError: (String) -> Unit = {}) {
        val port = voice
        if (port == null) {
            onError("เครื่องนี้ยังไม่ต่อระบบเสียง")
            return
        }
        _state.value = _state.value.copy(sending = true, phase = ChatPhase.LISTENING)
        viewModelScope.launch {
            when (val result = port.listen()) {
                is Outcome.Success -> onText(result.value.text)
                is Outcome.Failure -> onError(result.error.message)
            }
            val stillSending = _state.value.sending && sendJob?.isActive == true
            _state.value = _state.value.copy(
                sending = stillSending,
                phase = if (stillSending) _state.value.phase else ChatPhase.READY,
            )
        }
    }

    /** CP-60: speaks [text] aloud; errors surface in the chat error line. */
    fun speak(text: String) {
        val port = voice ?: return
        viewModelScope.launch {
            when (val result = port.speak(text)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> _state.value = _state.value.copy(error = result.error.message)
            }
        }
    }

    fun stopVoice() {
        val port = voice ?: return
        viewModelScope.launch { port.stop() }
    }

    private suspend fun refresh(id: String, sending: Boolean = false) {
        val messages = conversations.getMessages(id).fold(
            onSuccess = { it },
            onFailure = { emptyList() },
        )
        _state.value = _state.value.copy(
            conversationId = id, messages = messages, sending = sending,
            phase = if (sending) _state.value.phase else ChatPhase.READY,
            runningTool = if (sending) _state.value.runningTool else null,
        )
        workingSet?.setConversation(id)
    }
}
