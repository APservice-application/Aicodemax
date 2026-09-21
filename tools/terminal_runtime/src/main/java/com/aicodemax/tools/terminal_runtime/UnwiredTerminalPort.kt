package com.aicodemax.tools.terminal_runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.registry.ToolDescriptor
import com.aicodemax.tools.terminal.ExecListener
import com.aicodemax.tools.terminal.ExecRequest
import com.aicodemax.tools.terminal.RunningHandle
import com.aicodemax.tools.terminal.TerminalPort
import com.aicodemax.tools.terminal.TerminalSession
import com.aicodemax.tools.terminal.terminalDescriptorToday

/**
 * Honest stand-in until the Termux submodule is wired (Phase 16).
 * Reports TERMINAL_UNWIRED instead of faking a shell. See docs/TERMINAL_WIRING.md.
 */
class UnwiredTerminalPort : TerminalPort {
    private fun unwired(): Outcome<Nothing> =
        Outcome.Failure(AppError("TERMINAL_UNWIRED", "Termux runtime is not wired yet (Phase 16)"))

    override fun descriptor(): ToolDescriptor = terminalDescriptorToday()
    override fun createSession(title: String): Outcome<TerminalSession> = unwired()
    override fun closeSession(sessionId: String): Outcome<Unit> = unwired()
    override fun listSessions(): Outcome<List<TerminalSession>> = unwired()
    override fun exec(request: ExecRequest, listener: ExecListener): Outcome<RunningHandle> = unwired()
}
