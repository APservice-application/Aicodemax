package com.aicodemax.ai.tasks

data class AiTask(
    val id: String,
    val title: String,
    val description: String = "",
    val state: TaskState = TaskState.CREATED,
    val createdAt: Long,
    val updatedAt: Long,
    val attempts: Int = 0,
    val maxAttempts: Int = 3,
    /** Opaque JSON payload (plan steps, tool calls, observations). */
    val payloadJson: String = "{}",
    val resultSummary: String = "",
    val lastError: String = "",
)
