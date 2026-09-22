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
    /** CP-57 pending questionnaires (dynamic slot-filling dialog). */
    private val questionnaires: QuestionnaireStore = InMemoryQuestionnaireStore(),
    /** CP-59 LLM brain for open chat (null = rule-based help text). */
    private val brain: ChatBrain? = null,
) : Orchestrator {

    override suspend fun handleUserMessage(conversationId: String, text: String): Outcome<OrchestratorReply> {
        conversations.appendMessage(conversationId, MessageRole.USER, text)

        // Pending questionnaire? Treat this message as the answer.
        questionnaires.pending(conversationId)?.let { state ->
            val advanced = state.answer(text)
            if (!advanced.done) {
                questionnaires.save(conversationId, advanced)
                return statusReply(conversationId, advanced.questionText())
            }
            questionnaires.save(conversationId, null)
            return runIntent(conversationId, advanced.completedIntent(), text)
        }

        val intent = IntentParser.parse(text)

        // Missing slots? Start the questionnaire instead of planning.
        val slots = QuestionnaireSlots.forIntent(intent)
        if (slots.isNotEmpty()) {
            val state = QuestionnaireState(intent, slots)
            questionnaires.save(conversationId, state)
            return statusReply(conversationId, state.questionText())
        }
        return runIntent(conversationId, intent, text)
    }

    private suspend fun statusReply(conversationId: String, text: String): Outcome<OrchestratorReply> {
        conversations.appendMessage(conversationId, MessageRole.STATUS, text)
        return Outcome.Success(OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, text))))
    }

    private suspend fun brainReply(
        conversationId: String,
        text: String,
        activeBrain: ChatBrain,
    ): Outcome<OrchestratorReply> {
        val history = when (val got = conversations.getMessages(conversationId)) {
            is Outcome.Failure -> emptyList<LlmTurn>()
            is Outcome.Success -> got.value.takeLast(11).dropLast(1).mapNotNull { message ->
                when (message.role) {
                    MessageRole.USER -> LlmTurn("user", message.text)
                    MessageRole.AI -> LlmTurn("assistant", message.text)
                    MessageRole.SYSTEM, MessageRole.STATUS -> null
                }
            }
        }
        return when (val result = activeBrain.reply(text, history)) {
            is Outcome.Failure -> statusReply(conversationId, "LLM ตอบไม่ได้: ${result.error.message}")
            is Outcome.Success -> {
                conversations.appendMessage(conversationId, MessageRole.AI, result.value)
                Outcome.Success(OrchestratorReply(listOf(ReplyMessage(MessageRole.AI, result.value))))
            }
        }
    }

    private suspend fun runIntent(
        conversationId: String,
        intent: UserIntent,
        text: String,
    ): Outcome<OrchestratorReply> {

        if (intent.type == IntentType.CHAT || intent.type == IntentType.UNKNOWN) {
            val activeBrain = brain
            if (activeBrain != null) {
                return brainReply(conversationId, text, activeBrain)
            }
            val status = "รับทราบครับ — เชื่อมต่อ AI bootstrap แล้ว (v0).\n" +
                "ตอนนี้สั่งได้จริง เช่น:\n" +
                "• สร้างไฟล์ notes.txt: สวัสดี / อ่านไฟล์ / ดูไฟล์ / ค้นหา TODO\n" +
                "• เปิดเว็บ example.com / เปิดดู ... / ปิดแท็บ 1 / แท็บ\n" +
                "• บันทึก wifi: รหัส 1234 / ความจำ wifi\n" +
                "• แก้บั๊ก: <วาง error> / สถานะระบบ / หยุดงาน\n" +
                "• git status / git commit -m \"done\"\n" +
                "• สกิล / ดูสกิล\n" +
                "• อ่านให้ฟัง: ... / ฟังเสียง (ใช้เสียง)\n" +
                "• ข้อมูลรูป/ย่อรูป/ครอปรูป/หมุนรูป/รูปขาวดำ\n" +
                "• ข้อมูลเสียง/ตัดเสียง/เร่งเสียง/เฟดเสียง/ต่อเสียง\n" +
                "• ข้อมูลวิดีโอ/ตัดวิดีโอ/ภาพปก/ดึงเสียง\n" +
                "• โปรเจกต์ใหม่/เพิ่มไฟล์/บันทึกเวอร์ชัน/ย้อนเวอร์ชัน\n" +
                "• ทำซับ:/เลื่อนซับ/ฝังซับ\n" +
                "• เรนเดอร์/สถานะเรนเดอร์/อนุมัติ/เอ็กซ์พอร์ต\n" +
                "อยากคุยอิสระกับ LLM จริง: ต่อ provider ที่หน้า Models ครับ\n" +
                "ส่วน terminal / build / ตัดต่อวิดีโอ จะตามมาใน CP ถัดไปครับ"
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
