package com.aicodemax.tools.terminal

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Terminal session bookkeeping (CP-33 Dev Workspace backfill; MASTER §31).
 * Metadata only — the PTY itself lives in the Phase-16 runtime. The terminal
 * is a compatibility engine, never the core (TerminalDescriptor.kt).
 */
class SessionRegistry(
    private val maxSessions: Int = 10,
    private val clock: Clock = SystemClock,
) {
    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    fun create(title: String): Outcome<TerminalSession> {
        if (sessions.size >= maxSessions.coerceAtLeast(1)) {
            return Outcome.Failure(AppError("TERMINAL_SESSION_CAP", "too many sessions (max $maxSessions)"))
        }
        val clean = title.trim().ifBlank { "shell" }
        val session = TerminalSession(Ids.newId("term"), clean, SessionState.CREATED, clock.nowMillis())
        sessions[session.id] = session
        return Outcome.Success(session)
    }

    fun get(sessionId: String): Outcome<TerminalSession> =
        sessions[sessionId]?.let { Outcome.Success(it) }
            ?: Outcome.Failure(AppError("TERMINAL_NO_SESSION", "session '$sessionId' not found"))

    fun list(): List<TerminalSession> = sessions.values.sortedBy { it.createdAt }

    fun mark(sessionId: String, state: SessionState): Outcome<TerminalSession> {
        val current = sessions[sessionId]
            ?: return Outcome.Failure(AppError("TERMINAL_NO_SESSION", "session '$sessionId' not found"))
        val updated = current.copy(state = state)
        sessions[sessionId] = updated
        return Outcome.Success(updated)
    }

    fun close(sessionId: String): Outcome<Unit> {
        val removed = sessions.remove(sessionId) != null
        return if (removed) Outcome.Success(Unit) else {
            Outcome.Failure(AppError("TERMINAL_NO_SESSION", "session '$sessionId' not found"))
        }
    }
}
