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
    val goal: String = "",
    val currentStep: String = "",
    val agentId: String = "",
    val modelId: String = "",
    val capabilityId: String = "",
    val verificationNote: String = "",
    val checkpointId: String = "",
    val resultSummary: String = "",
    val lastError: String = "",
)
