package com.aicodemax.tools.terminal_runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.registry.ToolDescriptor
import com.aicodemax.tools.terminal.ExecChunk
import com.aicodemax.tools.terminal.ExecListener
import com.aicodemax.tools.terminal.ExecRequest
import com.aicodemax.tools.terminal.RunningHandle
import com.aicodemax.tools.terminal.SessionState
import com.aicodemax.tools.terminal.TerminalPort
import com.aicodemax.tools.terminal.TerminalSession
import com.aicodemax.tools.terminal.terminalDescriptorToday
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CP-113: real system-shell backend for [TerminalPort] (Phase 16, step 1).
 *
 * Ported approach from the owner's previous app (ConsoleRunner, owner-approved
 * 19-09-2026): run real commands via the system `sh` with ProcessBuilder —
 * no fake shell, no separate Termux app install (AMENDMENT-001).
 *
 * Honest scope: whatever the OS shell provides (Android: toybox sh/ls/cat/…;
 * JVM: full shell). Full Termux bootstrap (package manager, linux userland)
 * remains future work — see docs/TERMINAL_WIRING.md.
 */
class SystemShellPort(
    private val maxOutputChars: Int = 200_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : TerminalPort {

    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val ids = AtomicLong(0)

    /** Real shell binary used on this machine (probe order, like the old app). */
    val shellPath: String = sequenceOf("/system/bin/sh", "/bin/sh", "/system/xbin/sh")
        .firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
        ?: "/system/bin/sh"

    override fun descriptor(): ToolDescriptor = terminalDescriptorToday()

    override fun createSession(title: String): Outcome<TerminalSession> {
        val id = "sh_" + ids.incrementAndGet()
        val session = TerminalSession(id, title.ifBlank { "shell" }, SessionState.RUNNING, clock())
        sessions[id] = session
        return Outcome.Success(session)
    }

    override fun closeSession(sessionId: String): Outcome<Unit> {
        val removed = sessions.remove(sessionId) != null
        return if (removed) Outcome.Success(Unit) else Outcome.Failure(
            AppError("TERMINAL_NO_SESSION", "unknown session '$sessionId' — use terminal.open first"),
        )
    }

    override fun listSessions(): Outcome<List<TerminalSession>> =
        Outcome.Success(sessions.values.sortedBy { it.createdAt })

    override fun exec(request: ExecRequest, listener: ExecListener): Outcome<RunningHandle> {
        if (!sessions.containsKey(request.sessionId)) {
            return Outcome.Failure(
                AppError("TERMINAL_NO_SESSION", "unknown session '${request.sessionId}' — use terminal.open first"),
            )
        }
        if (request.command.isBlank()) {
            return Outcome.Failure(AppError("TERMINAL_NO_COMMAND", "command is blank"))
        }
        val handle = ShellHandle()
        val thread = Thread({
            runProcess(request, listener, handle)
        }, "system-shell-${request.sessionId}")
        thread.isDaemon = true
        handle.thread = thread
        thread.start()
        return Outcome.Success(handle)
    }

    private fun runProcess(request: ExecRequest, listener: ExecListener, handle: ShellHandle) {
        val cmdline = mutableListOf(shellPath, "-c", request.command) + request.args
        val builder = ProcessBuilder(cmdline).apply {
            request.cwd?.let { directory(File(it)) }
        }
        val process = try {
            builder.start()
        } catch (e: Exception) {
            listener.onChunk(ExecChunk(request.sessionId, "", finished = true, exitCode = -1))
            handle.finish()
            return
        }
        handle.process = process
        val outLen = AtomicLong(0)
        fun capped(text: String): String {
            val used = outLen.get()
            if (used >= maxOutputChars) return ""
            val room = maxOutputChars - used
            val take = if (text.length > room) text.substring(0, room.toInt()) else text
            outLen.addAndGet(take.length.toLong())
            return take
        }
        val outReader = Thread({
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val part = capped(line + "\n")
                    if (part.isNotEmpty()) listener.onChunk(ExecChunk(request.sessionId, part))
                }
            }
        }, "shell-stdout").apply { isDaemon = true }
        val errReader = Thread({
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    val part = capped(line + "\n")
                    if (part.isNotEmpty()) listener.onChunk(ExecChunk(request.sessionId, part, isStderr = true))
                }
            }
        }, "shell-stderr").apply { isDaemon = true }
        outReader.start()
        errReader.start()
        val timeoutMs = request.timeoutMs.coerceAtLeast(1_000)
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished || handle.cancelled.get()) {
            runCatching { process.destroyForcibly() }
            outReader.join(2_000)
            errReader.join(2_000)
            val note = if (handle.cancelled.get()) "cancelled" else "timed out after ${timeoutMs}ms — killed"
            listener.onChunk(ExecChunk(request.sessionId, "\n⏱ $note\n", finished = true, exitCode = 124))
            handle.finish()
            return
        }
        // Race fix (from the old app): the process exited but pipe data may still
        // be unread — join the readers BEFORE emitting the final chunk.
        outReader.join(3_000)
        errReader.join(3_000)
        runCatching { process.destroyForcibly() } // no-op for dead process (anti-zombie)
        val code = runCatching { process.exitValue() }.getOrDefault(-1)
        val tail = if (outLen.get() >= maxOutputChars) "\n…(output capped at $maxOutputChars chars)\n" else ""
        if (tail.isNotEmpty()) listener.onChunk(ExecChunk(request.sessionId, tail))
        listener.onChunk(ExecChunk(request.sessionId, "", finished = true, exitCode = code))
        handle.finish()
    }

    private class ShellHandle : RunningHandle {
        @Volatile var process: Process? = null
        @Volatile var thread: Thread? = null
        val cancelled = AtomicBoolean(false)
        private val running = AtomicBoolean(true)

        fun finish() = running.set(false)

        override fun cancel() {
            cancelled.set(true)
            runCatching { process?.destroyForcibly() }
            runCatching { thread?.interrupt() }
        }

        override fun isRunning(): Boolean = running.get()
    }
}
