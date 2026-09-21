package com.aicodemax.ai.core

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.capability.CapabilityResolver
import com.aicodemax.tools.capability.StandardCapabilities

data class PlanStep(
    val id: String,
    val toolId: String,
    val action: String,
    val args: Map<String, String> = emptyMap(),
    val needsPermission: Boolean = false,
    val description: String = "",
)

data class Plan(
    val steps: List<PlanStep>,
    val note: String = "",
)

interface Planner {
    suspend fun plan(intent: UserIntent): Outcome<Plan>
}

/**
 * Rule-based v0 planner — thinks in CAPABILITIES, never in shell commands
 * (MASTER_ARCHITECTURE §3/§61/§63). Every step goes through the
 * [CapabilityResolver], which picks native engines first and the terminal
 * CLI adapter only as a last resort (or honest BLOCKED when nothing runs).
 */
class RuleBasedPlanner(
    private val resolver: CapabilityResolver = StandardCapabilities.defaultResolver(),
) : Planner {
    /** Capabilities that always need explicit user permission. */
    private val sensitive = setOf("files.delete")

    override suspend fun plan(intent: UserIntent): Outcome<Plan> {
        val requests = when (intent.type) {
            IntentType.CREATE_FILE -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "please include a file name, e.g. สร้างไฟล์ notes.txt: hello"),
                    )
                val content = intent.parameters["content"] ?: ""
                listOf(
                    "editor.set" to mapOf("path" to path, "content" to content),
                    "editor.save" to mapOf("path" to path),
                )
            }
            IntentType.READ_FILE -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "please include a file name, e.g. อ่านไฟล์ notes.txt"),
                    )
                listOf("files.read" to mapOf("path" to path))
            }
            IntentType.LIST_FILES -> listOf(
                "files.list" to mapOf("path" to (intent.parameters["path"] ?: "")),
            )
            IntentType.MAKE_DIR -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "please include a folder name, e.g. mkdir folder docs"),
                    )
                listOf("files.mkdir" to mapOf("path" to path))
            }
            IntentType.DELETE_PATH -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "please include a path to delete"),
                    )
                listOf("files.delete" to mapOf("path" to path))
            }
            IntentType.RUN_COMMAND -> listOf(
                "terminal.exec" to mapOf(
                    "command" to (intent.parameters["command"] ?: ""),
                    "sessionId" to (intent.parameters["sessionId"] ?: ""),
                ),
            )
            IntentType.OPEN_URL -> {
                val url = intent.parameters["url"]?.trim().orEmpty()
                if (url.isBlank()) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_URL", "please include a URL, e.g. เปิดเว็บ example.com"),
                    )
                }
                listOf("browser.open" to mapOf("url" to url))
            }
            IntentType.BUILD_PROJECT -> listOf("build.project" to emptyMap())
            IntentType.RUN_TESTS -> listOf("test.run" to emptyMap())
            IntentType.GIT_ACTION -> gitRequests(intent) ?: return gitFailure(intent)
            IntentType.CHAT, IntentType.UNKNOWN -> return Outcome.Failure(
                AppError("PLAN_NOT_ACTIONABLE", "nothing to plan for chat"),
            )
        }

        val steps = mutableListOf<PlanStep>()
        for ((capabilityId, args) in requests) {
            when (val resolved = resolver.resolve(capabilityId, args)) {
                is Outcome.Failure -> return resolved
                is Outcome.Success -> {
                    val cap = resolved.value
                    steps.add(
                        PlanStep(
                            Ids.newId("step"),
                            cap.toolId,
                            cap.action,
                            cap.args,
                            needsPermission = capabilityId in sensitive,
                            description = capabilityId,
                        ),
                    )
                }
            }
        }
        return Outcome.Success(Plan(steps))
    }

    private fun gitRequests(intent: UserIntent): List<Pair<String, Map<String, String>>>? {
        val action = intent.parameters["action"] ?: "status"
        val repo = intent.parameters["repo"] ?: ""
        if (action == "commit") {
            val message = intent.parameters["message"]?.trim().orEmpty()
            if (message.isBlank()) return null
            return listOf(
                "git.stage" to mapOf("repo" to repo),
                "git.commit" to mapOf("repo" to repo, "message" to message),
            )
        }
        if (action !in setOf("status", "log", "ensure", "stage")) return null
        return listOf("git.$action" to mapOf("repo" to repo))
    }

    private fun gitFailure(intent: UserIntent): Outcome<Plan> {
        val action = intent.parameters["action"] ?: "status"
        return if (action == "commit") {
            Outcome.Failure(
                AppError("PLAN_NO_MESSAGE", "please include a message, e.g. git commit -m \"done\""),
            )
        } else {
            Outcome.Failure(
                AppError("PLAN_UNSUPPORTED", "git $action ยังไม่รองรับ (รองรับ: status / log / commit)"),
            )
        }
    }
}
