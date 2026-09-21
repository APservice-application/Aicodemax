package com.aicodemax.ai.tasks

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.state.AppEvent
import com.aicodemax.core.state.EventBus
import java.util.concurrent.ConcurrentHashMap

interface TaskEngine {
    fun create(title: String, description: String = "", payloadJson: String = "{}"): Outcome<AiTask>
    fun get(taskId: String): Outcome<AiTask>
    fun list(): List<AiTask>
    fun transition(taskId: String, to: TaskState, note: String = ""): Outcome<AiTask>
    fun cancel(taskId: String, note: String = ""): Outcome<AiTask>
    fun pause(taskId: String): Outcome<AiTask>
    fun resume(taskId: String): Outcome<AiTask>
    fun fail(taskId: String, error: String): Outcome<AiTask>
    fun retry(taskId: String): Outcome<AiTask>
}

class DefaultTaskEngine(
    private val bus: EventBus,
    private val clock: Clock = SystemClock,
) : TaskEngine {
    private val tasks = ConcurrentHashMap<String, AiTask>()

    override fun create(title: String, description: String, payloadJson: String): Outcome<AiTask> {
        val now = clock.nowMillis()
        val task = AiTask(
            id = Ids.newId("task"),
            title = title,
            description = description,
            state = TaskState.CREATED,
            createdAt = now,
            updatedAt = now,
            payloadJson = payloadJson,
        )
        tasks[task.id] = task
        bus.tryPublish(AppEvent.TaskUpdated(task.id, task.state.name, "created"))
        return Outcome.Success(task)
    }

    override fun get(taskId: String): Outcome<AiTask> =
        tasks[taskId]?.let { Outcome.Success(it) }
            ?: Outcome.Failure(AppError("TASK_UNKNOWN", "task '$taskId' not found"))

    override fun list(): List<AiTask> = tasks.values.sortedBy { it.createdAt }

    override fun transition(taskId: String, to: TaskState, note: String): Outcome<AiTask> {
        val current = tasks[taskId]
            ?: return Outcome.Failure(AppError("TASK_UNKNOWN", "task '$taskId' not found"))
        if (!TaskState.canTransition(current.state, to)) {
            return Outcome.Failure(AppError("ILLEGAL_TRANSITION", "cannot move ${current.state} -> $to"))
        }
        val updated = current.copy(state = to, updatedAt = clock.nowMillis())
        tasks[taskId] = updated
        bus.tryPublish(AppEvent.TaskUpdated(taskId, to.name, note))
        return Outcome.Success(updated)
    }

    override fun cancel(taskId: String, note: String): Outcome<AiTask> {
        val current = tasks[taskId]
            ?: return Outcome.Failure(AppError("TASK_UNKNOWN", "task '$taskId' not found"))
        if (TaskState.isTerminal(current.state)) {
            return Outcome.Failure(AppError("TASK_TERMINAL", "task already ${current.state}"))
        }
        return transition(taskId, TaskState.CANCELLED, note.ifBlank { "cancelled" })
    }

    override fun pause(taskId: String): Outcome<AiTask> {
        val current = tasks[taskId]
            ?: return Outcome.Failure(AppError("TASK_UNKNOWN", "task '$taskId' not found"))
        if (current.state != TaskState.RUNNING) {
            return Outcome.Failure(AppError("PAUSE_INVALID", "only RUNNING tasks can pause"))
        }
        return transition(taskId, TaskState.PAUSED, "paused")
    }

    override fun resume(taskId: String): Outcome<AiTask> {
        val current = tasks[taskId]
            ?: return Outcome.Failure(AppError("TASK_UNKNOWN", "task '$taskId' not found"))
        if (current.state != TaskState.PAUSED) {
            return Outcome.Failure(AppError("RESUME_INVALID", "only PAUSED tasks can resume"))
        }
        return transition(taskId, TaskState.RUNNING, "resumed")
    }

    override fun fail(taskId: String, error: String): Outcome<AiTask> {
        val current = tasks[taskId]
            ?: return Outcome.Failure(AppError("TASK_UNKNOWN", "task '$taskId' not found"))
        if (!TaskState.canTransition(current.state, TaskState.FAILED)) {
            return Outcome.Failure(
                AppError("ILLEGAL_TRANSITION", "cannot fail task in state ${current.state}"),
            )
        }
        val updated = current.copy(
            state = TaskState.FAILED,
            lastError = error,
            updatedAt = clock.nowMillis(),
        )
        tasks[taskId] = updated
        bus.tryPublish(AppEvent.TaskUpdated(taskId, TaskState.FAILED.name, error))
        return Outcome.Success(updated)
    }

    override fun retry(taskId: String): Outcome<AiTask> {
        val current = tasks[taskId]
            ?: return Outcome.Failure(AppError("TASK_UNKNOWN", "task '$taskId' not found"))
        if (current.state != TaskState.FAILED) {
            return Outcome.Failure(AppError("RETRY_INVALID", "only FAILED tasks can retry"))
        }
        if (current.attempts >= current.maxAttempts) {
            return Outcome.Failure(
                AppError("RETRY_EXHAUSTED", "attempts exhausted (${current.attempts}/${current.maxAttempts})"),
            )
        }
        val updated = current.copy(
            state = TaskState.RETRYING,
            attempts = current.attempts + 1,
            updatedAt = clock.nowMillis(),
        )
        tasks[taskId] = updated
        bus.tryPublish(AppEvent.TaskUpdated(taskId, TaskState.RETRYING.name, "attempt ${updated.attempts}"))
        return Outcome.Success(updated)
    }
}
