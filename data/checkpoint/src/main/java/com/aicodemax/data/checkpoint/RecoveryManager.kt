package com.aicodemax.data.checkpoint

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold

/** Recovery policy (CP-30 Checkpoint/Rollback backfill; MASTER §28). Restores files via [FileRestorer], hands state JSON back so the task layer rehydrates. */
data class RestorePlan(
    val checkpointId: String,
    val taskId: String,
    val label: String,
    val fileCount: Int,
    val stateBytes: Int,
)

data class RestoreResult(
    val checkpointId: String,
    val filesRestored: Int,
    val stateJson: String,
)

fun interface FileRestorer {
    fun restore(files: List<String>): Outcome<Unit>
}

class RecoveryManager(private val store: CheckpointStore) {
    fun plan(checkpointId: String): Outcome<RestorePlan> {
        return store.load(checkpointId).fold(
            onSuccess = {
                Outcome.Success(RestorePlan(it.id, it.taskId, it.label, it.files.size, it.stateJson.length))
            },
            onFailure = { Outcome.Failure(it) },
        )
    }

    fun restore(checkpointId: String, restorer: FileRestorer): Outcome<RestoreResult> {
        return store.load(checkpointId).fold(
            onSuccess = { checkpoint ->
                restorer.restore(checkpoint.files).fold(
                    onSuccess = {
                        Outcome.Success(RestoreResult(checkpoint.id, checkpoint.files.size, checkpoint.stateJson))
                    },
                    onFailure = { Outcome.Failure(it) },
                )
            },
            onFailure = { Outcome.Failure(it) },
        )
    }

    fun restoreLatest(taskId: String, restorer: FileRestorer): Outcome<RestoreResult> {
        return store.loadLatest(taskId).fold(
            onSuccess = { restore(it.id, restorer) },
            onFailure = { Outcome.Failure(it) },
        )
    }

    /** Deletes older checkpoints, keeping the [keepLatest] newest. Returns deleted count. */
    fun prune(taskId: String, keepLatest: Int = 5): Outcome<Int> {
        return store.list(taskId).fold(
            onSuccess = { checkpoints ->
                val stale = checkpoints.sortedByDescending { it.createdAt }.drop(keepLatest.coerceAtLeast(0))
                var deleted = 0
                for (checkpoint in stale) {
                    if (store.delete(checkpoint.id)) deleted += 1
                }
                Outcome.Success(deleted)
            },
            onFailure = { Outcome.Failure(it) },
        )
    }
}
