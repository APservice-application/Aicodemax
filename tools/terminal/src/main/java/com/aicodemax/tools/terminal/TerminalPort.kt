package com.aicodemax.tools.terminal

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.registry.ToolDescriptor

data class TerminalSize(val cols: Int = 80, val rows: Int = 24)

enum class SessionState { CREATED, STARTING, RUNNING, PAUSED, FINISHED, FAILED }

data class TerminalSession(
    val id: String,
    val title: String,
    val state: SessionState,
    val createdAt: Long,
)

data class ExecRequest(
    val sessionId: String,
    val command: String,
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val timeoutMs: Long = 60_000,
    val size: TerminalSize = TerminalSize(),
)

data class ExecChunk(
    val sessionId: String,
    val text: String,
    val isStderr: Boolean = false,
    val finished: Boolean = false,
    val exitCode: Int? = null,
)

fun interface ExecListener {
    fun onChunk(chunk: ExecChunk)
}

interface RunningHandle {
    fun cancel()
    fun isRunning(): Boolean
}

/** Port to the embedded terminal runtime (managed shell; interactive PTY is [PtyPort], CP-32). */
interface TerminalPort {
    fun descriptor(): ToolDescriptor
    fun createSession(title: String): Outcome<TerminalSession>
    fun closeSession(sessionId: String): Outcome<Unit>
    fun listSessions(): Outcome<List<TerminalSession>>
    fun exec(request: ExecRequest, listener: ExecListener): Outcome<RunningHandle>
}

/** Simplified blocking API for the AI control loop (AMENDMENT-001). */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean = false,
)

interface AIControlAPI {
    fun runCommand(sessionId: String, command: String, timeoutMs: Long = 60_000): Outcome<CommandResult>
}
