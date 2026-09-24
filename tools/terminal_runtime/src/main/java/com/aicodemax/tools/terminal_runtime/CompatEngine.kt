package com.aicodemax.tools.terminal_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.terminal.ExecChunk
import com.aicodemax.tools.terminal.RunningHandle
import com.aicodemax.tools.terminal.SessionRegistry
import com.aicodemax.tools.terminal.SessionState
import com.aicodemax.tools.terminal.TerminalSession
import java.util.concurrent.ConcurrentHashMap

/**
 * CP-31: compatibility engine — the ONLY sanctioned path from tools to a
 * shell. Wraps [CliToolAdapter] with session bookkeeping so every command
 * is attributable to a session. Native capability bindings stay preferred.
 *
 * CP-143: bridges UI sessions to REAL port sessions (lazy — one port
 * session per UI session, closed together). New shells start in [defaultCwd]
 * (or the per-session cwd from [openSession]); afterwards `cd` persists.
 */
class CompatEngine(
    private val adapter: CliToolAdapter,
    private val sessions: SessionRegistry = SessionRegistry(),
    private val defaultCwd: String? = null,
) {
    /** UI session id → real port session id. */
    private val portIds = ConcurrentHashMap<String, String>()

    /** Session id → cwd for its FIRST exec only (later `cd` is respected). */
    private val pendingCwd = ConcurrentHashMap<String, String>()

    fun sessions(): List<TerminalSession> = sessions.list()

    fun openSession(title: String, cwd: String? = null): Outcome<TerminalSession> {
        val created = sessions.create(title)
        if (created is Outcome.Success) {
            val dir = cwd ?: defaultCwd
            if (dir != null) pendingCwd[created.value.id] = dir
        }
        return created
    }

    fun closeSession(sessionId: String): Outcome<Unit> {
        portIds.remove(sessionId)?.let { runCatching { adapter.closePortSession(it) } }
        pendingCwd.remove(sessionId)
        return sessions.close(sessionId)
    }

    /** Runs [command] in [sessionId] (created on demand when null). Blocking. */
    fun exec(command: String, sessionId: String? = null, timeoutMs: Long = 60_000): Outcome<CompatResult> {
        val session = resolve(sessionId).fold(
            onSuccess = { it },
            onFailure = { return Outcome.Failure(it) },
        )
        val portId = portSessionFor(session.id).fold(
            onSuccess = { it },
            onFailure = { return Outcome.Failure(it) },
        )
        sessions.mark(session.id, SessionState.RUNNING)
        val cwd = pendingCwd.remove(session.id)
        return adapter.run(portId, command, timeoutMs, cwd).fold(
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

    /**
     * Streaming exec for the interactive console: chunks flow to [onChunk]
     * live; the returned handle cancels (Stop button). Creates the UI
     * session on demand when [sessionId] is null.
     */
    fun execLive(
        command: String,
        sessionId: String? = null,
        timeoutMs: Long = 60_000,
        onChunk: (ExecChunk) -> Unit,
    ): Outcome<CompatLive> {
        val session = resolve(sessionId).fold(
            onSuccess = { it },
            onFailure = { return Outcome.Failure(it) },
        )
        val portId = portSessionFor(session.id).fold(
            onSuccess = { it },
            onFailure = { return Outcome.Failure(it) },
        )
        sessions.mark(session.id, SessionState.RUNNING)
        val cwd = pendingCwd.remove(session.id)
        return adapter.execStream(portId, command, timeoutMs, cwd) { chunk ->
            onChunk(chunk)
            if (chunk.finished) sessions.mark(session.id, SessionState.RUNNING)
        }.fold(
            onSuccess = { Outcome.Success(CompatLive(session.id, it)) },
            onFailure = {
                sessions.mark(session.id, SessionState.FAILED)
                Outcome.Failure(it)
            },
        )
    }

    private fun resolve(sessionId: String?): Outcome<TerminalSession> {
        if (sessionId != null) return sessions.get(sessionId)
        val created = sessions.create("compat")
        if (created is Outcome.Success && defaultCwd != null) {
            pendingCwd[created.value.id] = defaultCwd
        }
        return created
    }

    private fun portSessionFor(compatId: String): Outcome<String> {
        synchronized(portIds) {
            portIds[compatId]?.let { return Outcome.Success(it) }
            return adapter.openPortSession("compat").fold(
                onSuccess = {
                    portIds[compatId] = it
                    Outcome.Success(it)
                },
                onFailure = { Outcome.Failure(it) },
            )
        }
    }
}

data class CompatResult(
    val sessionId: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
)

data class CompatLive(
    val sessionId: String,
    val handle: RunningHandle,
)
