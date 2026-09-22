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
    private val sensitive = setOf("files.delete", "skill.remove")

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
            IntentType.SEARCH_FILES -> {
                val query = intent.parameters["query"]?.trim().orEmpty()
                if (query.isBlank()) {
                    return Outcome.Failure(AppError("PLAN_NO_QUERY", "ค้นหาอะไรครับ? เช่น ค้นหา TODO"))
                }
                listOf("files.search" to mapOf("query" to query))
            }
            IntentType.BROWSER_OPEN -> {
                val url = intent.parameters["url"]?.trim().orEmpty()
                if (url.isBlank()) {
                    return Outcome.Failure(AppError("PLAN_NO_URL", "เปิดเว็บไหนครับ? เช่น เปิดดู example.com"))
                }
                listOf("browser.open" to mapOf("url" to url))
            }
            IntentType.BROWSER_CLOSE -> {
                val tabId = intent.parameters["tabId"]?.trim().orEmpty()
                if (tabId.isBlank()) {
                    return Outcome.Failure(AppError("PLAN_NO_TAB", "ปิดแท็บไหนครับ? เช่น ปิดแท็บ 1"))
                }
                listOf("browser.close" to mapOf("tabId" to tabId))
            }
            IntentType.BROWSER_LIST -> listOf("browser.list" to emptyMap())
            IntentType.DEBUG_CODE -> {
                val error = intent.parameters["error"]?.trim().orEmpty()
                if (error.isBlank()) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_ERROR", "วาง error มาเลยครับ เช่น แก้บั๊ก: NullPointerException ..."),
                    )
                }
                listOf("debug.analyze" to mapOf("error" to error))
            }
            IntentType.MEMORY_SAVE -> {
                val key = intent.parameters["key"]?.trim().orEmpty()
                val value = intent.parameters["value"]?.trim().orEmpty()
                if (key.isBlank() || value.isBlank()) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_MEMORY", "บันทึกอะไรครับ? เช่น บันทึก wifi: รหัส 1234"),
                    )
                }
                listOf("memory.save" to mapOf("key" to key, "value" to value))
            }
            IntentType.MEMORY_RECALL -> {
                val key = intent.parameters["key"]?.trim().orEmpty()
                if (key.isBlank()) {
                    return Outcome.Failure(AppError("PLAN_NO_MEMORY", "ถามเรื่องอะไรครับ? เช่น ความจำ wifi"))
                }
                listOf("memory.recall" to mapOf("key" to key))
            }
            IntentType.SKILL_LIST -> listOf("skill.list" to emptyMap())
            IntentType.SKILL_GET -> {
                val id = intent.parameters["id"]?.trim().orEmpty()
                if (id.isBlank()) {
                    return Outcome.Failure(AppError("PLAN_NO_SKILL", "อ่านสกิลไหนครับ? เช่น สกิล aicode-tools"))
                }
                listOf("skill.get" to mapOf("id" to id))
            }
            IntentType.SKILL_REMOVE -> {
                val id = intent.parameters["id"]?.trim().orEmpty()
                if (id.isBlank()) {
                    return Outcome.Failure(AppError("PLAN_NO_SKILL", "ลบสกิลไหนครับ? เช่น ลบสกิล my-note"))
                }
                listOf("skill.remove" to mapOf("id" to id))
            }
            // Chat-handled or pending-engine intents: honest guidance, no fake steps.
            IntentType.STOP_TASK -> return Outcome.Failure(
                AppError("PLAN_STOP", "กดปุ่มหยุดในแชท/Task Center ได้เลยครับ งานจะหยุดทันที"),
            )
            IntentType.SYSTEM_STATUS -> return Outcome.Failure(
                AppError("PLAN_STATUS", "ดูสถานะเครื่องได้ที่หน้า Home ครับ"),
            )
            IntentType.OPEN_SETTINGS -> return Outcome.Failure(
                AppError("PLAN_SETTINGS", "เปิดหน้า Settings ที่แถบล่าง (ไอคอนฟันเฟือง) ได้เลยครับ"),
            )
            IntentType.MEDIA_EDIT -> return Outcome.Failure(
                AppError(
                    "PLAN_MEDIA_PENDING",
                    "ระบบตัดต่อวิดีโอ/รูป/เสียงกำลังมาใน CP-61..63 ครับ — ตอนนี้ยังตัดให้จริงไม่ได้ เลยไม่แกล้งทำ",
                ),
            )
            IntentType.SHARE_MEDIA -> return Outcome.Failure(
                AppError(
                    "PLAN_SHARE_PENDING",
                    "ระบบแชร์/โพสต์มาพร้อมหน้า Export ใน CP-67 ครับ",
                ),
            )
            IntentType.LLM_CONNECT -> return Outcome.Failure(
                AppError(
                    "PLAN_LLM_GUIDE",
                    "ใส่ base URL + API key + model ที่หน้า Models แล้วกดเชื่อมต่อได้เลยครับ (key อยู่ในหน่วยความจำเท่านั้น ไม่ต้องพิมพ์ในแชท)",
                ),
            )
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
