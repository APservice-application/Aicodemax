package com.aicodemax.tools.terminal

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/**
 * Honest capability snapshot of the terminal tool *today* (100% contract).
 * Per MASTER_ARCHITECTURE §5/§29/§71: terminal is the Compatibility Engine /
 * CLI Adapter (CP-32) — a fallback, never the core. Native engines first.
 *
 * CP-113: runtime is REAL (system shell via sh, in-app — no separate Termux
 * app install per AMENDMENT-001). Full Termux bootstrap (package manager,
 * linux userland) remains future work — see docs/TERMINAL_WIRING.md.
 * CP-143: shells are PERSISTENT per session (cd/export carry over);
 * stdout+stderr merged chronologically; streaming + cancel + timeout-respawn.
 */
fun terminalDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "terminal",
    displayName = "Terminal",
    version = "0.3.0",
    layers = listOf(
        LayerCapability(
            CapabilityLayer.UI, CapabilityStatus.AVAILABLE,
            "interactive console (live output + stop + history + cwd)",
        ),
        LayerCapability(
            CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE,
            "CompatEngine + CliToolAdapter wired (CP-32 + CP-113 + CP-143)",
        ),
        LayerCapability(
            CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE,
            "TerminalPort + AIControlAPI contracts",
        ),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "persistent system shell via sh in-app (CP-143) — full Termux bootstrap future",
        ),
        LayerCapability(
            CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE,
            "exec/runCommand via PersistentShellPort (streaming + cancel + timeout-respawn; merged streams)",
        ),
        LayerCapability(
            CapabilityLayer.VERIFICATION, CapabilityStatus.AVAILABLE,
            "real exit-code + merged output capture",
        ),
        LayerCapability(
            CapabilityLayer.RECOVERY, CapabilityStatus.PARTIAL,
            "timeout kill + fresh-shell respawn real; retry policy lives in TaskEngine",
        ),
    ),
    permissions = listOf("android.permission.INTERNET"),
)
