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
            IntentType.OPEN_URL -> Outcome.Failure(
                AppError("PLAN_UNAVAILABLE", "browser runtime wires in Phase 19"),
            )
            IntentType.BUILD_PROJECT -> Outcome.Failure(
                AppError("PLAN_UNAVAILABLE", "on-device build wires in Phase 17"),
            )
            IntentType.RUN_TESTS -> Outcome.Failure(
                AppError("PLAN_UNAVAILABLE", "on-device test wires in Phase 17"),
            )
            IntentType.GIT_ACTION -> Outcome.Failure(
                AppError("PLAN_UNAVAILABLE", "git runtime wires in Phase 18"),
            )
            IntentType.CHAT, IntentType.UNKNOWN -> Outcome.Failure(
                AppError("PLAN_NOT_ACTIONABLE", "nothing to plan for chat"),
            )
        }
    }
}
