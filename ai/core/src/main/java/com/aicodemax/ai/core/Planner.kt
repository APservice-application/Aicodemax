package com.aicodemax.ai.core

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome

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

/** Rule-based v0 planner: files/editor work today, everything else reports honestly. */
class RuleBasedPlanner : Planner {
    override suspend fun plan(intent: UserIntent): Outcome<Plan> {
        return when (intent.type) {
            IntentType.CREATE_FILE -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "please include a file name, e.g. สร้างไฟล์ notes.txt: hello"),
                    )
                val content = intent.parameters["content"] ?: ""
                Outcome.Success(
                    Plan(
                        listOf(
                            PlanStep(
                                Ids.newId("step"), "editor", "set",
                                mapOf("path" to path, "content" to content),
                                description = "write $path",
                            ),
                            PlanStep(
                                Ids.newId("step"), "editor", "save",
                                mapOf("path" to path),
                                description = "save $path",
                            ),
                        ),
                    ),
                )
            }
            IntentType.READ_FILE -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "please include a file name, e.g. อ่านไฟล์ notes.txt"),
                    )
                Outcome.Success(
                    Plan(
                        listOf(
                            PlanStep(
                                Ids.newId("step"), "files", "read",
                                mapOf("path" to path),
                                description = "read $path",
                            ),
                        ),
                    ),
                )
            }
            IntentType.LIST_FILES -> Outcome.Success(
                Plan(
                    listOf(
                        PlanStep(
                            Ids.newId("step"), "files", "list",
                            mapOf("path" to (intent.parameters["path"] ?: "")),
                            description = "list files",
                        ),
                    ),
                ),
            )
            IntentType.MAKE_DIR -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "please include a folder name, e.g. mkdir folder docs"),
                    )
                Outcome.Success(
                    Plan(
                        listOf(
                            PlanStep(
                                Ids.newId("step"), "files", "mkdir",
                                mapOf("path" to path),
                                description = "make dir $path",
                            ),
                        ),
                    ),
                )
            }
            IntentType.DELETE_PATH -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "please include a path to delete"),
                    )
                Outcome.Success(
                    Plan(
                        listOf(
                            PlanStep(
                                Ids.newId("step"), "files", "delete",
                                mapOf("path" to path),
                                needsPermission = true,
                                description = "delete $path",
                            ),
                        ),
                    ),
                )
            }
            IntentType.RUN_COMMAND -> Outcome.Failure(
                AppError("PLAN_UNAVAILABLE", "terminal runtime wires in Phase 16 — commands cannot run yet"),
            )
            IntentType.OPEN_URL -> {
                val url = intent.parameters["url"]?.trim().orEmpty()
                if (url.isBlank()) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_URL", "please include a URL, e.g. เปิดเว็บ example.com"),
                    )
                }
                Outcome.Success(
                    Plan(
                        listOf(
                            PlanStep(
                                Ids.newId("step"), "browser", "open",
                                mapOf("url" to url),
                                description = "open $url",
                            ),
                        ),
                    ),
                )
            }
            IntentType.BUILD_PROJECT -> Outcome.Failure(
                AppError("PLAN_UNAVAILABLE", "on-device build wires in Phase 17"),
            )
            IntentType.RUN_TESTS -> Outcome.Failure(
                AppError("PLAN_UNAVAILABLE", "on-device test wires in Phase 17"),
            )
            IntentType.GIT_ACTION -> {
                val action = intent.parameters["action"] ?: "status"
                val repo = intent.parameters["repo"] ?: ""
                fun step(toolAction: String, extra: Map<String, String> = emptyMap()) = PlanStep(
                    Ids.newId("step"), "git", toolAction,
                    mapOf("repo" to repo) + extra,
                    description = "git $toolAction",
                )
                when (action) {
                    "status", "log", "ensure", "stage" ->
                        Outcome.Success(Plan(listOf(step(action))))
                    "commit" -> {
                        val message = intent.parameters["message"]?.trim().orEmpty()
                        if (message.isBlank()) {
                            return Outcome.Failure(
                                AppError("PLAN_NO_MESSAGE", "please include a message, e.g. git commit -m \"done\""),
                            )
                        }
                        Outcome.Success(Plan(listOf(step("stage"), step("commit", mapOf("message" to message)))))
                    }
                    else -> Outcome.Failure(
                        AppError("PLAN_UNSUPPORTED", "git $action ยังไม่รองรับ (รองรับ: status / log / commit)"),
                    )
                }
            }
            IntentType.CHAT, IntentType.UNKNOWN -> Outcome.Failure(
                AppError("PLAN_NOT_ACTIONABLE", "nothing to plan for chat"),
            )
        }
    }
}
