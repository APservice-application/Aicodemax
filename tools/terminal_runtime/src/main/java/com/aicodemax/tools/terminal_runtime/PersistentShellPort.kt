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
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CP-143: persistent-shell backend for [TerminalPort] — the "full terminal".
 *
 * Each session owns ONE long-lived `sh` process (stdin/stdout piped).
 * `cd`/`export`/shell functions persist across execs in the same session.
 * Completion is detected with random START/END marker lines (never faked:
 * the shell itself echoes them, including the real `$?` exit code).
 *
 * Honest limits (documented, not hidden):
 * - stdout+stderr are MERGED (chronological order, like a real console);
 *   [ExecChunk.isStderr] is always false.
 * - One exec at a time per session (concurrent exec → TERMINAL_BUSY).
 * - Commands that replace the shell (`exec …`) or never return (servers
 *   without `&`) time out; timeout/cancel kills the shell and the next
 *   exec transparently respawns a FRESH shell (cd/export reset).
 * - `args` form runs via a `sh -c` subshell (isolated, like before).
 */
class PersistentShellPort(
    private val maxOutputChars: Int = 200_000,
    private val maxSessions: Int = 16,
    private val clock: () -> Long = System::currentTimeMillis,
    shellCandidates: List<String> = listOf("/system/bin/sh", "/bin/sh", "/system/xbin/sh"),
) : TerminalPort {

    /** Real shell binary used on this machine (probe order). */
    val shellPath: String = shellCandidates
        .firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
        ?: "/system/bin/sh"

    /** One stdout line tagged with the process incarnation that produced it (`null` = EOF). */
    private data class TaggedLine(val proc: Process, val line: String?)

    private class Shell(val meta: TerminalSession) {
        @Volatile var process: Process? = null
        @Volatile var writer: BufferedWriter? = null
        val lines = LinkedBlockingQueue<TaggedLine>()
        val busy = AtomicBoolean(false)
        val lock = Any()
    }

    private val shells = ConcurrentHashMap<String, Shell>()
    private val ids = AtomicLong(0)

    override fun descriptor(): ToolDescriptor = terminalDescriptorToday()

    override fun createSession(title: String): Outcome<TerminalSession> {
        if (shells.size >= maxSessions.coerceAtLeast(1)) {
            return Outcome.Failure(AppError("TERMINAL_SESSION_CAP", "too many sessions (max $maxSessions)"))
        }
        val id = "psh_" + ids.incrementAndGet()
        val meta = TerminalSession(id, title.ifBlank { "shell" }, SessionState.RUNNING, clock())
        val shell = Shell(meta)
        shells[id] = shell
        val err = ensureAlive(shell)
        if (err != null) {
            shells.remove(id)
            return Outcome.Failure(AppError("TERMINAL_SPAWN", err))
        }
        return Outcome.Success(meta)
    }

    override fun closeSession(sessionId: String): Outcome<Unit> {
        val shell = shells.remove(sessionId)
            ?: return Outcome.Failure(
                AppError("TERMINAL_NO_SESSION", "unknown session '$sessionId' — use terminal.open first"),
            )
        runCatching { shell.process?.destroyForcibly() }
        runCatching { shell.writer?.close() }
        return Outcome.Success(Unit)
    }

    override fun listSessions(): Outcome<List<TerminalSession>> =
        Outcome.Success(shells.values.map { it.meta }.sortedBy { it.createdAt })

    override fun exec(request: ExecRequest, listener: ExecListener): Outcome<RunningHandle> {
        val shell = shells[request.sessionId]
            ?: return Outcome.Failure(
                AppError("TERMINAL_NO_SESSION", "unknown session '${request.sessionId}' — use terminal.open first"),
            )
        if (request.command.isBlank()) {
            return Outcome.Failure(AppError("TERMINAL_NO_COMMAND", "command is blank"))
        }
        if (!shell.busy.compareAndSet(false, true)) {
            return Outcome.Failure(
                AppError("TERMINAL_BUSY", "session '${request.sessionId}' is already running a command"),
            )
        }
        val handle = LiveHandle(shell)
        val thread = Thread({ runExec(shell, request, listener, handle) }, "psh-${request.sessionId}")
        thread.isDaemon = true
        thread.start()
        return Outcome.Success(handle)
    }

    /** Spawn (or respawn) the shell process. Returns an error message or null. */
    private fun ensureAlive(shell: Shell): String? = synchronized(shell.lock) {
        val current = shell.process
        if (current != null && current.isAlive) return null
        return try {
            val proc = ProcessBuilder(shellPath).redirectErrorStream(true).start()
            shell.process = proc
            shell.writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
            val reader = Thread({
                runCatching {
                    BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                        shell.lines.offer(TaggedLine(proc, line))
                    }
                }
                shell.lines.offer(TaggedLine(proc, null))
            }, "psh-reader").apply { isDaemon = true }
            reader.start()
            null
        } catch (e: Exception) {
            "spawn shell ไม่ได้ (${e.message})"
        }
    }

    private fun runExec(
        shell: Shell,
        request: ExecRequest,
        listener: ExecListener,
        handle: LiveHandle,
    ) {
        try {
            ensureAlive(shell)?.let { err ->
                finish(shell, handle, request, listener, "❌ $err\n", -1)
                return
            }
            // Explicit cwd: its own cd round-trip first (fails honestly when bad).
            val cwd = request.cwd
            if (cwd != null) {
                val cdCode = roundTrip(shell, request, listener, handle, "cd -- ${shQuote(cwd)}")
                if (cdCode == null) return // timeout/cancel/death already reported
                if (cdCode != 0) {
                    finish(shell, handle, request, listener, "❌ cd ไม่ได้: ${cwd}\n", 1)
                    return
                }
            }
            val effective = if (request.args.isEmpty()) {
                request.command
            } else {
                // args form: isolated subshell (same $0/$1… semantics as `sh -c`).
                "sh -c ${shQuote(request.command)}" +
                    request.args.joinToString(separator = "", prefix = " ") { shQuote(it) }
            }
            val code = roundTrip(shell, request, listener, handle, effective) ?: return
            finish(shell, handle, request, listener, "", code)
        } finally {
            // Safety net (normal paths release via finish() before emitting).
            shell.busy.set(false)
            handle.finish()
        }
    }

    /**
     * Emit the terminal chunk. [Shell.busy] is released BEFORE the listener
     * fires so a fast caller can immediately start the next exec (no BUSY
     * race between the finished chunk and the next call).
     */
    private fun finish(
        shell: Shell,
        handle: LiveHandle,
        request: ExecRequest,
        listener: ExecListener,
        text: String,
        exitCode: Int,
    ) {
        shell.busy.set(false)
        handle.finish()
        listener.onChunk(ExecChunk(request.sessionId, text, finished = true, exitCode = exitCode))
    }

    /**
     * Send one command + markers, stream output until the END marker.
     * Returns the exit code, or null when timeout/cancel/shell-death was
     * already reported to [listener].
     */
    private fun roundTrip(
        shell: Shell,
        request: ExecRequest,
        listener: ExecListener,
        handle: LiveHandle,
        command: String,
    ): Int? {
        val proc = shell.process ?: return reportDead(shell, handle, request, listener, -1, "shell หาย")
        val id = UUID.randomUUID().toString().take(8)
        val startTag = "__AICODEMUX_S_$id"
        val endPrefix = "__AICODEMUX_E_${id}_"
        val writer = shell.writer ?: return reportDead(shell, handle, request, listener, -1, "shell หาย")
        try {
            writer.write("echo $startTag\n")
            writer.write(command + "\n")
            writer.write("echo ${endPrefix}\$?\n")
            writer.flush()
        } catch (e: Exception) {
            return reportDead(shell, handle, request, listener, -1, "ส่งคำสั่งไม่ได้: ${e.message}")
        }
        val timeoutMs = request.timeoutMs.coerceAtLeast(1_000)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var started = false
        var used = 0L
        fun emit(text: String) {
            if (used >= maxOutputChars) return
            val room = maxOutputChars - used
            val take = if (text.length > room) text.substring(0, room.toInt()) else text
            used += take.length
            if (take.isNotEmpty()) listener.onChunk(ExecChunk(request.sessionId, take))
        }
        while (true) {
            if (handle.cancelled.get()) {
                runCatching { proc.destroyForcibly() }
                finish(shell, handle, request, listener, "\n⏹ cancelled\n", 124)
                return null
            }
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) {
                runCatching { proc.destroyForcibly() }
                finish(
                    shell, handle, request, listener,
                    "\n⏱ timed out after ${timeoutMs}ms — shell killed (rerun respawns fresh)\n", 124,
                )
                return null
            }
            val tagged = shell.lines.poll(minOf(remainingMs, 250), TimeUnit.MILLISECONDS) ?: continue
            if (tagged.proc !== proc) continue // stale line from a previous incarnation
            val line = tagged.line
            if (line == null) {
                val code = runCatching { proc.exitValue() }.getOrDefault(-1)
                return reportDead(shell, handle, request, listener, code, "shell exited ($code)")
            }
            if (!started) {
                if (line == startTag) started = true
                continue // discard stale/late lines until our START
            }
            if (line.startsWith(endPrefix)) {
                if (used >= maxOutputChars) {
                    listener.onChunk(
                        ExecChunk(request.sessionId, "\n…(output capped at $maxOutputChars chars)\n"),
                    )
                }
                return line.removePrefix(endPrefix).toIntOrNull() ?: -1
            }
            emit(line + "\n")
        }
    }

    /** Shell died mid-exec: respawn eagerly + report honestly. Always returns null. */
    private fun reportDead(
        shell: Shell,
        handle: LiveHandle,
        request: ExecRequest,
        listener: ExecListener,
        code: Int,
        note: String,
    ): Int? {
        ensureAlive(shell)
        finish(
            shell, handle, request, listener,
            "⚠ $note — เปิดเชลล์ใหม่ให้แล้ว (cd/export รีเซ็ต) รันคำสั่งอีกครั้ง\n", code,
        )
        return null
    }

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private class LiveHandle(val shell: Shell) : RunningHandle {
        val cancelled = AtomicBoolean(false)
        private val running = AtomicBoolean(true)

        fun finish() = running.set(false)

        override fun cancel() {
            cancelled.set(true)
            runCatching { shell.process?.destroyForcibly() }
        }

        override fun isRunning(): Boolean = running.get()
    }
}
