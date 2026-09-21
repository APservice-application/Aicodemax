package com.aicodemax.data.checkpoint

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.common.fold
import com.aicodemax.core.common.runOutcome
import java.io.File
import kotlinx.serialization.json.Json

interface CheckpointStore {
    fun save(
        taskId: String,
        label: String,
        stateJson: String,
        files: List<String> = emptyList(),
    ): Outcome<Checkpoint>

    fun load(id: String): Outcome<Checkpoint>
    fun loadLatest(taskId: String): Outcome<Checkpoint>
    fun list(taskId: String): Outcome<List<Checkpoint>>
    fun delete(id: String): Boolean
}

/** File-backed checkpoint store: one JSON file per checkpoint. */
class FileCheckpointStore(
    rootDir: File,
    private val clock: Clock = SystemClock,
) : CheckpointStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir: File = File(rootDir, "checkpoints").apply { mkdirs() }

    @Synchronized
    override fun save(
        taskId: String,
        label: String,
        stateJson: String,
        files: List<String>,
    ): Outcome<Checkpoint> {
        val checkpoint = Checkpoint(Ids.newId("cp"), taskId, label, clock.nowMillis(), stateJson, files)
        return runOutcome("CHECKPOINT_WRITE") {
            File(dir, "${checkpoint.id}.json")
                .writeText(json.encodeToString(Checkpoint.serializer(), checkpoint))
            checkpoint
        }
    }

    @Synchronized
    override fun load(id: String): Outcome<Checkpoint> = runOutcome("CHECKPOINT_READ") {
        val file = File(dir, "$id.json")
        if (!file.exists()) throw NoSuchElementException("checkpoint '$id' not found")
        json.decodeFromString(Checkpoint.serializer(), file.readText())
    }

    @Synchronized
    override fun loadLatest(taskId: String): Outcome<Checkpoint> =
        list(taskId).fold(
            onSuccess = { list ->
                list.maxByOrNull { it.createdAt }?.let { Outcome.Success(it) }
                    ?: Outcome.Failure(AppError("CHECKPOINT_EMPTY", "no checkpoints for task '$taskId'"))
            },
            onFailure = { Outcome.Failure(it) },
        )

    @Synchronized
    override fun list(taskId: String): Outcome<List<Checkpoint>> = runOutcome("CHECKPOINT_READ") {
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .map { json.decodeFromString(Checkpoint.serializer(), it.readText()) }
            .filter { it.taskId == taskId }
            .sortedBy { it.createdAt }
    }

    @Synchronized
    override fun delete(id: String): Boolean = File(dir, "$id.json").delete()
}
