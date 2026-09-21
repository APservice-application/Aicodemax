package com.aicodemax.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicodemax.ai.core.Orchestrator
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.data.conversations.ChatMessage
import com.aicodemax.data.conversations.ConversationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
)

class ChatViewModel(
    private val orchestrator: Orchestrator,
    private val conversations: ConversationStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Opens the active conversation, creating one only when none exists. */
    fun ensureOpen() {
        if (_state.value.conversationId == null) openConversation(null)
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
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val id = _state.value.conversationId ?: return
        _state.value = _state.value.copy(sending = true, error = null)
        viewModelScope.launch {
            when (val result = orchestrator.handleUserMessage(id, trimmed)) {
                is Outcome.Failure ->
                    _state.value = _state.value.copy(sending = false, error = result.error.message)
                is Outcome.Success -> refresh(id, sending = false)
            }
        }
    }

    fun newChat() = openConversation(null)

    private suspend fun refresh(id: String, sending: Boolean = false) {
        val messages = conversations.getMessages(id).fold(
            onSuccess = { it },
            onFailure = { emptyList() },
        )
        _state.value = ChatUiState(conversationId = id, messages = messages, sending = sending)
    }
}
