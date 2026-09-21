package com.aicodemax.core.state

/** Single event vocabulary for the whole app (Layer 4 → all layers). */
sealed interface AppEvent {
    data class TaskUpdated(val taskId: String, val state: String, val note: String = "") : AppEvent
    data class ToolOutput(
        val toolId: String,
        val sessionId: String,
        val chunk: String,
        val isStderr: Boolean = false,
    ) : AppEvent

    data class ModelChanged(val modelId: String, val status: String) : AppEvent
    data class CapabilityChanged(val toolId: String) : AppEvent
    data class ChatAppended(val conversationId: String, val messageId: String) : AppEvent
    data class Notice(val topic: String, val message: String) : AppEvent
}
