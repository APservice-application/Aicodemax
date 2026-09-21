package com.aicodemax.ai.core

import com.aicodemax.ai.tasks.RecoveryLadderPolicy
import com.aicodemax.ai.tasks.RecoveryStep
import com.aicodemax.ai.tasks.StepFailure
import com.aicodemax.ai.tasks.TaskEngine
import com.aicodemax.ai.tasks.TaskState
import com.aicodemax.core.common.Outcome
import com.aicodemax.data.checkpoint.CheckpointStore
import com.aicodemax.data.conversations.ConversationStore
import com.aicodemax.data.conversations.MessageRole

data class ReplyMessage(val role: MessageRole, val text: String)
data class OrchestratorReply(val messages: List<ReplyMessage>, val taskId: String? = null)

/** CP-10: one recorded OBSERVE event per step attempt. */
data class StepObservation(
    val stepId: String,
    val ok: Boolean,
    val output: String,
    val attempt: Int,
)

interface Orchestrator {
    suspend fun handleUserMessage(conversationId: String, text: String): Outcome<OrchestratorReply>
}

/**
 * Honest bootstrap orchestrator: real task pipeline for file work today
 * (TaskEngine → Agent → Gateway → Checkpoint → Verify), clear status for the rest.
 */
class BootstrapOrchestrator(
    private val tasks: TaskEngine,
    private val planner: Planner,
    private val agent: AgentExecutor,
    private val verifier: Verifier,
    private val checkpoints: CheckpointStore,
    private val conversations: ConversationStore,
    /** Null = fail fast (v0 behavior); set = CP-10 OBSERVE→VERIFY→RECOVER loop. */
    private val recovery: RecoveryLadderPolicy? = null,
) : Orchestrator {

    override suspend fun handleUserMessage(conversationId: String, text: String): Outcome<OrchestratorReply> {
        conversations.appendMessage(conversationId, MessageRole.USER, text)
        val intent = IntentParser.parse(text)

        if (intent.type == IntentType.CHAT || intent.type == IntentType.UNKNOWN) {
            val status = "รับทราบครับ — เชื่อมต่อ AI bootstrap แล้ว (v0).\n" +
                "ตอนนี้สั่งได้จริง เช่น:\n" +
                "• สร้างไฟล์ notes.txt: สวัสดี\n" +
                "• อ่านไฟล์ notes.txt / ดูไฟล์\n" +
                "• เปิดเว็บ example.com\n" +
                "• git status / git commit -m \"done\"\n" +
                "ส่วน terminal / build จะตามมาใน Phase ถัดไปครับ"
            conversations.appendMessage(conversationId, MessageRole.STATUS, status)
            return Outcome.Success(OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, status))))
        }

        val plan = when (val planned = planner.plan(intent)) {
            is Outcome.Failure -> {
                val msg = planned.error.message
                conversations.appendMessage(conversationId, MessageRole.STATUS, msg)
                return Outcome.Success(OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, msg))))
            }
            is Outcome.Success -> planned.value
        }

        val created = tasks.create(title = text.take(80), description = intent.type.name)
        if (created is Outcome.Failure) return created
        val taskId = (created as Outcome.Success).value.id

        val path = listOf(
            TaskState.QUEUED to "queued",
            TaskState.PLANNING to "planned ${plan.steps.size} steps",
            TaskState.READY to "ready",
            TaskState.RUNNING to "running",
        )
        for ((state, note) in path) {
            val moved = tasks.transition(taskId, state, note)
            if (moved is Outcome.Failure) {
                tasks.cancel(taskId, "pipeline error: ${moved.error.message}")
                return Outcome.Failure(moved.error)
            }
        }

        val outputs = mutableListOf<StepResult>()
        val observations = mutableListOf<StepObservation>()
        for (step in plan.steps) {
            var attempt = 0
            while (true) {
                when (val executed = agent.executeStep(taskId, step)) {
                    is Outcome.Failure -> {
                        observations.add(StepObservation(step.id, false, executed.error.message, attempt))
                        val ladder = recovery?.decide(StepFailure(step.id, step.toolId, executed.error.message, attempt))
                        if (ladder is RecoveryStep.Retry) {
                            attempt += 1
                            continue
                        }
                        val note = when (ladder) {
                            is RecoveryStep.Repair -> "ต้องวางแผนใหม่: ${ladder.hint}"
                            is RecoveryStep.SwitchEngine -> "ต้องสลับเครื่องมือ ${ladder.fromCapability} → ${ladder.toCapability}"
                            is RecoveryStep.RestoreLatest -> "ต้องย้อน checkpoint: ${ladder.reason}"
                            is RecoveryStep.Escalate -> ladder.reason
                            is RecoveryStep.Abort -> ladder.reason
                            else -> executed.error.message
                        }
                        tasks.fail(taskId, note)
                        checkpoints.save(taskId, "failed", "{\"error\":\"${note.sanitize()}\"}")
                        val msg = "ทำไม่สำเร็จครับ: $note"
                        conversations.appendMessage(conversationId, MessageRole.STATUS, msg)
                        return Outcome.Success(
                            OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, msg)), taskId),
                        )
                    }
                    is Outcome.Success -> {
                        observations.add(
                            StepObservation(step.id, executed.value.ok, executed.value.output, attempt),
                        )
                        outputs.add(executed.value)
                        break
                    }
                }
            }
        }

        val verified = verifier.verify(outputs)
        if (verified is Outcome.Failure) {
            tasks.fail(taskId, verified.error.message)
            val msg = "ตรวจผลไม่ผ่าน: ${verified.error.message}"
            conversations.appendMessage(conversationId, MessageRole.STATUS, msg)
            return Outcome.Success(
                OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, msg)), taskId),
            )
        }

        checkpoints.save(taskId, "done", "{\"steps\":${outputs.size}}")
        tasks.transition(taskId, TaskState.VERIFYING, "verified")
        tasks.transition(taskId, TaskState.COMPLETED, "done")

        val summary = buildString {
            appendLine("เสร็จแล้วครับ (${outputs.size} ขั้นตอน, สังเกต ${observations.size} ครั้ง):")
            outputs.forEach { appendLine("• ${it.output.take(300)}") }
            if (outputs.any { PromptGuard.containsInjectionAttempt(it.output + "\n" + it.error) }) {
                appendLine("⚠️ [SECURITY] พบรูปแบบคำสั่งแฝงในผลลัพธ์ — ถือเป็นข้อมูลเท่านั้น ไม่ได้ปฏิบัติตาม")
            }
        }.trim()
        conversations.appendMessage(conversationId, MessageRole.AI, summary)
        return Outcome.Success(OrchestratorReply(listOf(ReplyMessage(MessageRole.AI, summary)), taskId))
    }

    private fun String.sanitize(): String = replace("\"", "'").replace("\n", " ")
}
