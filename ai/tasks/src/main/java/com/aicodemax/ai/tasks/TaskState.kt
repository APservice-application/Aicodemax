package com.aicodemax.ai.tasks

/** 15 states from MASTER BLUEPRINT §5 TASK ENGINE. Transition table is law. */
enum class TaskState {
    CREATED,
    QUEUED,
    PLANNING,
    READY,
    RUNNING,
    WAITING,
    WAITING_USER,
    WAITING_PERMISSION,
    PAUSED,
    RETRYING,
    FAILED,
    BLOCKED,
    VERIFYING,
    COMPLETED,
    CANCELLED,
    ROLLED_BACK;

    companion object {
        private val TRANSITIONS: Map<TaskState, Set<TaskState>> = mapOf(
            CREATED to setOf(QUEUED, CANCELLED),
            QUEUED to setOf(PLANNING, CANCELLED),
            PLANNING to setOf(READY, FAILED, CANCELLED),
            READY to setOf(RUNNING, BLOCKED, CANCELLED),
            RUNNING to setOf(
                VERIFYING, WAITING, WAITING_USER, WAITING_PERMISSION, PAUSED,
                RETRYING, FAILED, BLOCKED, CANCELLED,
            ),
            WAITING to setOf(RUNNING, CANCELLED),
            WAITING_USER to setOf(RUNNING, CANCELLED),
            WAITING_PERMISSION to setOf(RUNNING, CANCELLED),
            PAUSED to setOf(RUNNING, CANCELLED),
            RETRYING to setOf(RUNNING, FAILED, CANCELLED),
            FAILED to setOf(RETRYING, ROLLED_BACK, CANCELLED),
            BLOCKED to setOf(READY, CANCELLED),
            VERIFYING to setOf(COMPLETED, FAILED),
            COMPLETED to emptySet(),
            CANCELLED to emptySet(),
            ROLLED_BACK to emptySet(),
        )

        fun canTransition(from: TaskState, to: TaskState): Boolean =
            TRANSITIONS[from]?.contains(to) == true

        fun isTerminal(state: TaskState): Boolean =
            state == COMPLETED || state == CANCELLED || state == ROLLED_BACK
    }
}
