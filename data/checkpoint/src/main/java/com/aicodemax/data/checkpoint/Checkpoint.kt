package com.aicodemax.data.checkpoint

import kotlinx.serialization.Serializable

@Serializable
data class Checkpoint(
    val id: String,
    val taskId: String,
    val label: String,
    val createdAt: Long,
    val stateJson: String,
    val files: List<String> = emptyList(),
)
