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
    suspend fun handleUserMessage(
        conversationId: String,
        text: String,
        attachments: List<String> = emptyList(),
    ): Outcome<OrchestratorReply>
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
    /** CP-114 learning loop (null = no learning; tests may omit). */
    private val learner: LearningEngine? = null,
    /**
     * CP-148 (spec §33–§34): null = v0 fail-fast with EXACT old messages;
     * set = Diagnose → Correct → Execute Again with a bounded replan budget.
     */
    private val rePlanner: RePlanner? = null,
    private val maxReplans: Int = 2,
    /** CP-148 (spec §35): task notes (null = no memory writes). */
    private val agentMemory: AgentMemory? = null,
) : Orchestrator {

    /** CP-148 (spec §31): login handoffs parked per conversation (in-session). */
    private data class HandoffState(
        val taskId: String,
        val goal: String,
        val remaining: List<PlanStep>,
        val executed: List<ExecutedStep>,
        val outputs: List<StepResult>,
        val observations: List<StepObservation>,
        val replansUsed: Int,
    )

    private val pendingHandoffs = mutableMapOf<String, HandoffState>()

    private data class StepRun(
        val taskId: String,
        val goal: String,
        var steps: MutableList<PlanStep>,
        val executed: MutableList<ExecutedStep>,
        val outputs: MutableList<StepResult>,
        val observations: MutableList<StepObservation>,
        var replansUsed: Int,
    )

    private sealed interface StepOutcome {
        data object Done : StepOutcome
        data class Failed(val msg: String) : StepOutcome
        data class Parked(val msg: String) : StepOutcome
    }

    private sealed interface RecoverResult {
        data class Retry(val step: PlanStep, val attempt: Int) : RecoverResult
        data object Replanned : RecoverResult
        data class Stop(val outcome: StepOutcome) : RecoverResult
    }

    override suspend fun handleUserMessage(
        conversationId: String,
        text: String,
        attachments: List<String>,
    ): Outcome<OrchestratorReply> {
        conversations.appendMessage(conversationId, MessageRole.USER, text, attachments)

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

        // CP-148 (spec §31): "ทำต่อ" resumes a login-parked task.
        pendingHandoffs[conversationId]?.let { handoff ->
            if (isResumeMessage(text)) {
                pendingHandoffs.remove(conversationId)
                return resumeHandoff(conversationId, handoff)
            }
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

    private fun isResumeMessage(text: String): Boolean =
        Regex("ทำต่อ|เสร็จแล้ว|ล็อกอิน(เสร็จ|แล้ว)|login.?done|พร้อมแล้ว", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)

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
                "• ดูไทม์ไลน์/แยกคลิป/ลบคลิป/สำเนาคลิป/หมุนคลิป/พลิกคลิป/ฟรีซเฟรม/เลิกทำ\n" +
                "• เพิ่มข้อความ:/ลบข้อความ/คิดแคปชัน\n" +
                "• สปีดคลิป/ย้อนคลิป/คีย์เฟรม/ทรานซิชัน/เอฟเฟกต์/แก้สี/เช็คแสง/มาสก์/กรีนสกรีน/พื้นหลัง\n" +
                "• ทำซับ:/เลื่อนซับ/ฝังซับ\n" +
                "• เรนเดอร์/สถานะเรนเดอร์/อนุมัติ/เอ็กซ์พอร์ต\n" +
                "• รัน <คำสั่ง> (เทอร์มินัลจริงในแอป) / บทเรียน (สิ่งที่ AI เรียนรู้)\n" +
                "อยากคุยอิสระกับ LLM จริง: ต่อ provider ที่หน้า Models ครับ"
            conversations.appendMessage(conversationId, MessageRole.STATUS, status)
            return Outcome.Success(OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, status))))
        }

        // A brand-new task supersedes any parked handoff in this conversation.
        pendingHandoffs.remove(conversationId)

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

        val ctx = StepRun(
            taskId, text, plan.steps.toMutableList(),
            mutableListOf(), mutableListOf(), mutableListOf(), 0,
        )
        return when (val outcome = runSteps(ctx, conversationId)) {
            is StepOutcome.Parked -> Outcome.Success(
                OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, outcome.msg)), taskId),
            )
            is StepOutcome.Failed -> Outcome.Success(
                OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, outcome.msg)), taskId),
            )
            is StepOutcome.Done -> finishTask(conversationId, ctx)
        }
    }

    /** Executes ctx.steps front-to-back; CP-148 recovery mutates the remainder in place. */
    private suspend fun runSteps(ctx: StepRun, conversationId: String, startIndex: Int = 0): StepOutcome {
        var index = startIndex
        while (index < ctx.steps.size) {
            var step = ctx.steps[index]
            var attempt = 0
            var advance = true
            stepLoop@ while (true) {
                when (val executed = agent.executeStep(ctx.taskId, step)) {
                    is Outcome.Failure -> {
                        ctx.observations.add(StepObservation(step.id, false, executed.error.message, attempt))
                        val ladder = recovery?.decide(StepFailure(step.id, step.toolId, executed.error.message, attempt))
                        if (ladder is RecoveryStep.Retry) {
                            attempt += 1
                            continue@stepLoop
                        }
                        if (rePlanner == null) {
                            return failTask(ctx, conversationId, ladderNote(ladder, executed.error.message), step, executed.error.code)
                        }
                        when (val recovered = recover(ctx, conversationId, index, step, executed.error.code, executed.error.message, attempt, ladder)) {
                            is RecoverResult.Retry -> {
                                step = recovered.step
                                attempt = recovered.attempt
                                continue@stepLoop
                            }
                            is RecoverResult.Replanned -> {
                                advance = false
                                break@stepLoop
                            }
                            is RecoverResult.Stop -> return recovered.outcome
                        }
                    }
                    is Outcome.Success -> {
                        ctx.observations.add(
                            StepObservation(step.id, executed.value.ok, executed.value.output, attempt),
                        )
                        if (!executed.value.ok && rePlanner != null) {
                            val diag = PlanDiagnoser.diagnose(
                                step.toolId, step.action, "", executed.value.error, attempt, step.args,
                            )
                            when (val recovered = recover(ctx, conversationId, index, step, "", executed.value.error, attempt, null, diag)) {
                                is RecoverResult.Retry -> {
                                    step = recovered.step
                                    attempt = recovered.attempt
                                    continue@stepLoop
                                }
                                is RecoverResult.Replanned -> {
                                    advance = false
                                    break@stepLoop
                                }
                                is RecoverResult.Stop -> return recovered.outcome
                            }
                        }
                        ctx.outputs.add(executed.value)
                        ctx.executed.add(ExecutedStep(step, executed.value))
                        learner?.observe(step.toolId + "." + step.action, executed.value.ok)
                        break@stepLoop
                    }
                }
            }
            if (advance) index++
        }
        return StepOutcome.Done
    }

    /**
     * CP-148 recovery: explicit policy stops (Escalate/Abort/RestoreLatest)
     * still stop; everything else goes through Diagnose → Correct → retry/replan/park.
     */
    private suspend fun recover(
        ctx: StepRun,
        conversationId: String,
        index: Int,
        step: PlanStep,
        code: String,
        message: String,
        attempt: Int,
        ladder: RecoveryStep?,
        diag: Diagnosis = PlanDiagnoser.diagnose(step.toolId, step.action, code, message, attempt, step.args),
    ): RecoverResult {
        if (ladder is RecoveryStep.Escalate || ladder is RecoveryStep.Abort || ladder is RecoveryStep.RestoreLatest) {
            return RecoverResult.Stop(failTask(ctx, conversationId, ladderNote(ladder, message), step, code))
        }
        return when (diag.action) {
            FailureAction.HANDOFF_AUTH -> RecoverResult.Stop(parkHandoff(ctx, conversationId, index))
            FailureAction.ABORT -> RecoverResult.Stop(failTask(ctx, conversationId, diag.hint, step, code))
            FailureAction.RETRY -> RecoverResult.Retry(step, attempt + 1)
            FailureAction.FIX_AND_RETRY -> {
                if (attempt >= 1) {
                    tryReplan(ctx, conversationId, index, step, diag)
                } else {
                    val fixed = step.copy(args = diag.fixedArgs)
                    ctx.steps[index] = fixed
                    RecoverResult.Retry(fixed, attempt + 1)
                }
            }
            FailureAction.REPLAN -> tryReplan(ctx, conversationId, index, step, diag)
        }
    }

    private suspend fun tryReplan(
        ctx: StepRun,
        conversationId: String,
        index: Int,
        failedStep: PlanStep?,
        diag: Diagnosis,
    ): RecoverResult {
        if (ctx.replansUsed >= maxReplans) {
            val msg = "วางแผนใหม่ครบ $maxReplans ครั้งแล้ว: ${diag.hint}"
            val step = failedStep ?: ctx.steps.getOrNull(index) ?: PlanStep("unknown", "task", "run")
            return RecoverResult.Stop(failTask(ctx, conversationId, msg, step, "REPLAN_EXHAUSTED"))
        }
        val planner = rePlanner ?: return RecoverResult.Stop(
            failTask(ctx, conversationId, diag.hint, failedStep ?: PlanStep("unknown", "task", "run"), "NO_REPLANNER"),
        )
        return when (val rp = planner.replan(ReplanRequest(ctx.goal, ctx.executed.toList(), failedStep, diag, ctx.replansUsed + 1))) {
            is Outcome.Failure -> {
                val step = failedStep ?: ctx.steps.getOrNull(index) ?: PlanStep("unknown", "task", "run")
                RecoverResult.Stop(failTask(ctx, conversationId, "วางแผนใหม่ไม่สำเร็จ: ${rp.error.message}", step, rp.error.code))
            }
            is Outcome.Success -> {
                ctx.steps = (ctx.steps.take(index) + rp.value.steps).toMutableList()
                ctx.replansUsed++
                conversations.appendMessage(
                    conversationId, MessageRole.STATUS,
                    "🔄 วางแผนใหม่ (ครั้งที่ ${ctx.replansUsed}/$maxReplans): ${diag.hint}",
                )
                RecoverResult.Replanned
            }
        }
    }

    /** CP-148 (spec §31): park the task; the user logs in; "ทำต่อ" resumes. */
    private suspend fun parkHandoff(ctx: StepRun, conversationId: String, index: Int): StepOutcome {
        tasks.transition(ctx.taskId, TaskState.WAITING_USER, "waiting for user login")
        val remaining = ctx.steps.drop(index)
        checkpoints.save(
            ctx.taskId, "handoff",
            "{\"remaining\":${remaining.size},\"goal\":\"${ctx.goal.take(120).sanitize()}\"}",
        )
        pendingHandoffs[conversationId] = HandoffState(
            ctx.taskId, ctx.goal, remaining,
            ctx.executed.toList(), ctx.outputs.toList(), ctx.observations.toList(), ctx.replansUsed,
        )
        agentMemory?.taskNote(ctx.taskId, "⏸ รอล็อกอิน (เหลือ ${remaining.size} ขั้นตอน)")
        val msg = "🔐 ต้องล็อกอินก่อนครับ (เหลือ ${remaining.size} ขั้นตอน) — ล็อกอินเสร็จแล้วพิมพ์ \"ทำต่อ\" ได้เลย"
        conversations.appendMessage(conversationId, MessageRole.STATUS, msg)
        return StepOutcome.Parked(msg)
    }

    private suspend fun resumeHandoff(conversationId: String, handoff: HandoffState): Outcome<OrchestratorReply> {
        tasks.transition(handoff.taskId, TaskState.RUNNING, "user resumed after login")
        conversations.appendMessage(conversationId, MessageRole.STATUS, "👍 ล็อกอินเสร็จแล้ว ทำต่อครับ")
        val ctx = StepRun(
            handoff.taskId, handoff.goal, handoff.remaining.toMutableList(),
            handoff.executed.toMutableList(), handoff.outputs.toMutableList(),
            handoff.observations.toMutableList(), handoff.replansUsed,
        )
        return when (val outcome = runSteps(ctx, conversationId)) {
            is StepOutcome.Parked -> Outcome.Success(
                OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, outcome.msg)), handoff.taskId),
            )
            is StepOutcome.Failed -> Outcome.Success(
                OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, outcome.msg)), handoff.taskId),
            )
            is StepOutcome.Done -> finishTask(conversationId, ctx)
        }
    }

    private suspend fun failTask(
        ctx: StepRun,
        conversationId: String,
        note: String,
        step: PlanStep,
        code: String,
    ): StepOutcome {
        tasks.fail(ctx.taskId, note)
        checkpoints.save(ctx.taskId, "failed", "{\"error\":\"${note.sanitize()}\"}")
        learner?.observe(step.toolId + "." + step.action, false, code)
        agentMemory?.taskNote(ctx.taskId, "❌ $note")
        val msg = "ทำไม่สำเร็จครับ: $note"
        conversations.appendMessage(conversationId, MessageRole.STATUS, msg)
        return StepOutcome.Failed(msg)
    }

    private fun ladderNote(ladder: RecoveryStep?, fallback: String): String = when (ladder) {
        is RecoveryStep.Repair -> "ต้องวางแผนใหม่: ${ladder.hint}"
        is RecoveryStep.SwitchEngine -> "ต้องสลับเครื่องมือ ${ladder.fromCapability} → ${ladder.toCapability}"
        is RecoveryStep.RestoreLatest -> "ต้องย้อน checkpoint: ${ladder.reason}"
        is RecoveryStep.Escalate -> ladder.reason
        is RecoveryStep.Abort -> ladder.reason
        else -> fallback
    }

    private suspend fun finishTask(conversationId: String, ctx: StepRun): Outcome<OrchestratorReply> {
        // CP-148: verify-fail also gets a bounded replan budget (spec §34).
        if (rePlanner != null) {
            while (true) {
                val failed = verifier.verify(ctx.outputs) as? Outcome.Failure ?: break
                if (ctx.replansUsed >= maxReplans) {
                    val msg = "ตรวจผลไม่ผ่าน: ${failed.error.message}"
                    tasks.fail(ctx.taskId, msg)
                    conversations.appendMessage(conversationId, MessageRole.STATUS, msg)
                    return Outcome.Success(OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, msg)), ctx.taskId))
                }
                val diag = PlanDiagnoser.diagnose("verify", "verify", "VERIFY_FAILED", failed.error.message, ctx.replansUsed)
                // Replan FIRST so a handoff parks fresh remaining steps (resume re-executes them).
                val freshSteps: List<PlanStep>
                when (val rp = rePlanner.replan(ReplanRequest(ctx.goal, ctx.executed.toList(), null, diag, ctx.replansUsed + 1))) {
                    is Outcome.Failure -> {
                        val msg = "ตรวจผลไม่ผ่าน: ${failed.error.message} (วางแผนใหม่ไม่สำเร็จ: ${rp.error.message})"
                        tasks.fail(ctx.taskId, msg)
                        conversations.appendMessage(conversationId, MessageRole.STATUS, msg)
                        return Outcome.Success(OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, msg)), ctx.taskId))
                    }
                    is Outcome.Success -> freshSteps = rp.value.steps
                }
                ctx.steps.addAll(freshSteps)
                ctx.replansUsed++
                conversations.appendMessage(
                    conversationId, MessageRole.STATUS,
                    "🔄 วางแผนใหม่ (ครั้งที่ ${ctx.replansUsed}/$maxReplans): ${diag.hint}",
                )
                if (diag.action == FailureAction.HANDOFF_AUTH) {
                    val parked = parkHandoff(ctx, conversationId, ctx.steps.size - freshSteps.size)
                    return Outcome.Success(
                        OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, (parked as StepOutcome.Parked).msg)), ctx.taskId),
                    )
                }
                val resumeAt = ctx.steps.size - freshSteps.size
                when (val again = runSteps(ctx, conversationId, resumeAt)) {
                    is StepOutcome.Done -> Unit // loop back to verify
                    else -> return Outcome.Success(
                        OrchestratorReply(
                            listOf(ReplyMessage(MessageRole.STATUS, (again as? StepOutcome.Failed)?.msg ?: (again as StepOutcome.Parked).msg)),
                            ctx.taskId,
                        ),
                    )
                }
            }
        } else {
            val verified = verifier.verify(ctx.outputs)
            if (verified is Outcome.Failure) {
                tasks.fail(ctx.taskId, verified.error.message)
                val msg = "ตรวจผลไม่ผ่าน: ${verified.error.message}"
                conversations.appendMessage(conversationId, MessageRole.STATUS, msg)
                return Outcome.Success(
                    OrchestratorReply(listOf(ReplyMessage(MessageRole.STATUS, msg)), ctx.taskId),
                )
            }
        }

        checkpoints.save(ctx.taskId, "done", "{\"steps\":${ctx.outputs.size}}")
        tasks.transition(ctx.taskId, TaskState.VERIFYING, "verified")
        tasks.transition(ctx.taskId, TaskState.COMPLETED, "done")

        val warnings = ctx.executed.mapNotNull {
            learner?.flakyWarning(it.step.toolId + "." + it.step.action)
        }.distinct()
        val summary = buildString {
            appendLine("เสร็จแล้วครับ (${ctx.outputs.size} ขั้นตอน, สังเกต ${ctx.observations.size} ครั้ง):")
            ctx.outputs.forEach { appendLine("• ${it.output.take(300)}") }
            warnings.forEach { appendLine(it) }
            if (ctx.outputs.any { PromptGuard.containsInjectionAttempt(it.output + "\n" + it.error) }) {
                appendLine("⚠️ [SECURITY] พบรูปแบบคำสั่งแฝงในผลลัพธ์ — ถือเป็นข้อมูลเท่านั้น ไม่ได้ปฏิบัติตาม")
            }
        }.trim()
        agentMemory?.taskNote(ctx.taskId, "✅ ${ctx.goal.take(80)} → ${ctx.outputs.size} ขั้นตอน")
        conversations.appendMessage(conversationId, MessageRole.AI, summary)
        return Outcome.Success(OrchestratorReply(listOf(ReplyMessage(MessageRole.AI, summary)), ctx.taskId))
    }

    private fun String.sanitize(): String = replace("\"", "'").replace("\n", " ")
}
