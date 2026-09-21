package com.aicodemax.tools.terminal_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.terminal.SessionRegistry
import com.aicodemax.tools.terminal.SessionState
import com.aicodemax.tools.terminal.TerminalSession

/**
 * CP-31: compatibility engine — the ONLY sanctioned path from tools to a
 * shell. Wraps [CliToolAdapter] with session bookkeeping so every command
 * is attributable to a session. Native capability bindings stay preferred.
 */
class CompatEngine(
    private val adapter: CliToolAdapter,
    private val sessions: SessionRegistry = SessionRegistry(),
) {
    fun sessions(): List<TerminalSession> = sessions.list()

    fun openSession(title: String): Outcome<TerminalSession> = sessions.create(title)

    fun closeSession(sessionId: String): Outcome<Unit> = sessions.close(sessionId)

    /** Runs [command] in [sessionId] (created on demand when null). */
    fun exec(command: String, sessionId: String? = null, timeoutMs: Long = 60_000): Outcome<CompatResult> {
        val session = if (sessionId == null) {
            sessions.create("compat").fold(
                onSuccess = { it },
                onFailure = { return Outcome.Failure(it) },
            )
        } else {
            sessions.get(sessionId).fold(
                onSuccess = { it },
                onFailure = { return Outcome.Failure(it) },
            )
        }
        sessions.mark(session.id, SessionState.RUNNING)
        return adapter.run(session.id, command, timeoutMs).fold(
            onSuccess = {
                sessions.mark(session.id, SessionState.RUNNING)
                Outcome.Success(CompatResult(session.id, it.exitCode, it.stdout, it.stderr, it.truncated))
            },
            onFailure = {
                sessions.mark(session.id, SessionState.FAILED)
                Outcome.Failure(it)
            },
        )
    }
}

data class CompatResult(
    val sessionId: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
)
