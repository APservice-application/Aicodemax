package com.aicodemax.ai.core

import com.aicodemax.ai.tasks.AiTask
import com.aicodemax.ai.tasks.DefaultTaskEngine
import com.aicodemax.ai.tasks.RecoveryLadderPolicy
import com.aicodemax.ai.tasks.TaskEngine
import com.aicodemax.ai.tasks.TaskState
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.isSuccess
import com.aicodemax.core.state.AppEvent
import com.aicodemax.core.state.EventBus
import com.aicodemax.data.checkpoint.CheckpointStore
import com.aicodemax.data.checkpoint.FileCheckpointStore
import com.aicodemax.data.conversations.Conversation
import com.aicodemax.data.conversations.ConversationStore
import com.aicodemax.data.conversations.FileConversationStore
import com.aicodemax.data.conversations.MessageRole
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeClock(var tick: Long = 100L) : Clock {
    override fun nowMillis(): Long = tick++
}

class RecordingBus : EventBus {
    private val flow = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AppEvent> = flow
    val published = mutableListOf<AppEvent>()
    override suspend fun publish(event: AppEvent) {
        published.add(event)
        flow.emit(event)
    }
    override fun tryPublish(event: AppEvent): Boolean {
        published.add(event)
        return flow.tryEmit(event)
    }
}

class FakeAgent(private val failWith: String? = null) : AgentExecutor {
    val steps = mutableListOf<PlanStep>()
    override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> {
        steps.add(step)
        return if (failWith == null) {
            Outcome.Success(StepResult(ok = true, output = "did:${step.toolId}.${step.action}"))
        } else {
            Outcome.Failure(com.aicodemax.core.common.AppError("AGENT_FAIL", failWith))
        }
    }
}

class BootstrapOrchestratorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun stores(): Triple<CheckpointStore, ConversationStore, RecordingBus> =
        Triple(
            FileCheckpointStore(tmp.root, FakeClock()),
            FileConversationStore(tmp.root, FakeClock()),
            RecordingBus(),
        )

    private fun orchestrator(
        agent: AgentExecutor,
        tasks: TaskEngine,
        checkpoints: CheckpointStore,
        conversations: ConversationStore,
    ): Orchestrator = BootstrapOrchestrator(tasks, RuleBasedPlanner(), agent, RuleVerifier(), checkpoints, conversations)

    private fun Outcome<AiTask>.task(): AiTask = (this as Outcome.Success<AiTask>).value

    @Test
    fun recoveryRetriesFlakyStepToCompletion() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val tasks = DefaultTaskEngine(bus, FakeClock())
        var calls = 0
        val flaky = object : AgentExecutor {
            override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> {
                calls += 1
                return if (calls == 1) {
                    Outcome.Failure(com.aicodemax.core.common.AppError("E", "transient"))
                } else {
                    Outcome.Success(StepResult(ok = true, output = "did:${step.action}"))
                }
            }
        }
        val conv = (conversations.createConversation("c") as Outcome.Success<Conversation>).value
        val orchestrator = BootstrapOrchestrator(
            tasks, RuleBasedPlanner(), flaky, RuleVerifier(), checkpoints, conversations,
            recovery = RecoveryLadderPolicy(),
        )
        val reply = (orchestrator.handleUserMessage(conv.id, "สร้างไฟล์ n.txt: hi")
            as Outcome.Success<OrchestratorReply>).value
        assertEquals(MessageRole.AI, reply.messages[0].role)
        assertTrue(reply.messages[0].text.contains("สังเกต 3 ครั้ง"))
        assertEquals(3, calls)
    }

    @Test
    fun recoveryAbortsWhenLadderExhausted() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val tasks = DefaultTaskEngine(bus, FakeClock())
        val conv = (conversations.createConversation("c") as Outcome.Success<Conversation>).value
        val orchestrator = BootstrapOrchestrator(
            tasks, RuleBasedPlanner(), FakeAgent(failWith = "doomed"), RuleVerifier(),
            checkpoints, conversations,
            recovery = RecoveryLadderPolicy(maxAttempts = 1),
        )
        val reply = (orchestrator.handleUserMessage(conv.id, "สร้างไฟล์ n.txt: hi")
            as Outcome.Success<OrchestratorReply>).value
        assertEquals(MessageRole.STATUS, reply.messages[0].role)
        assertTrue(reply.messages[0].text.contains("attempts exhausted"))
    }

    @Test
    fun chatGetsHonestStatusReply() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val tasks = DefaultTaskEngine(bus, FakeClock())
        val orchestrator = orchestrator(FakeAgent(), tasks, checkpoints, conversations)
        val conv = (conversations.createConversation("t") as Outcome.Success<com.aicodemax.data.conversations.Conversation>).value

        val reply = (orchestrator.handleUserMessage(conv.id, "สวัสดี") as Outcome.Success<OrchestratorReply>).value
        assertEquals(1, reply.messages.size)
        assertEquals(MessageRole.STATUS, reply.messages[0].role)
        assertEquals(null, reply.taskId)

        val stored = (conversations.getMessages(conv.id) as Outcome.Success<List<com.aicodemax.data.conversations.ChatMessage>>).value
        assertEquals(2, stored.size) // USER + STATUS
        assertEquals(MessageRole.USER, stored[0].role)
    }

    @Test
    fun fileCommandRunsFullPipeline() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val tasks = DefaultTaskEngine(bus, FakeClock())
        val agent = FakeAgent()
        val orchestrator = orchestrator(agent, tasks, checkpoints, conversations)
        val conv = (conversations.createConversation("t") as Outcome.Success<com.aicodemax.data.conversations.Conversation>).value

        val reply = (orchestrator.handleUserMessage(conv.id, "สร้างไฟล์ notes.txt: hi") as Outcome.Success<OrchestratorReply>).value
        assertEquals(MessageRole.AI, reply.messages[0].role)
        assertTrue(reply.messages[0].text.startsWith("เสร็จแล้วครับ"))

        val taskId = reply.taskId!!
        assertEquals(TaskState.COMPLETED, tasks.get(taskId).task().state)
        assertEquals(listOf("set", "save"), agent.steps.map { it.action })
        assertTrue(checkpoints.loadLatest(taskId).isSuccess())
        // created + 4 drives + verify + completed = 7 task events
        assertEquals(7, bus.published.filterIsInstance<AppEvent.TaskUpdated>().size)
    }

    @Test
    fun agentFailureFailsTaskHonestly() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val tasks = DefaultTaskEngine(bus, FakeClock())
        val orchestrator = orchestrator(FakeAgent(failWith = "disk on fire"), tasks, checkpoints, conversations)
        val conv = (conversations.createConversation("t") as Outcome.Success<com.aicodemax.data.conversations.Conversation>).value

        val reply = (orchestrator.handleUserMessage(conv.id, "ดูไฟล์") as Outcome.Success<OrchestratorReply>).value
        assertEquals(MessageRole.STATUS, reply.messages[0].role)
        assertTrue(reply.messages[0].text.contains("disk on fire"))

        val taskId = reply.taskId!!
        assertEquals(TaskState.FAILED, tasks.get(taskId).task().state)
    }

    @Test
    fun unsupportedRuntimeRepliesWithoutTask() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val tasks = DefaultTaskEngine(bus, FakeClock())
        val orchestrator = orchestrator(FakeAgent(), tasks, checkpoints, conversations)
        val conv = (conversations.createConversation("t") as Outcome.Success<com.aicodemax.data.conversations.Conversation>).value

        val reply = (orchestrator.handleUserMessage(conv.id, "run ls") as Outcome.Success<OrchestratorReply>).value
        assertEquals(MessageRole.STATUS, reply.messages[0].role)
        assertEquals(null, reply.taskId)
        assertTrue(tasks.list().isEmpty())
    }

    @Test
    fun chatWithBrainUsesBrainReply() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val tasks = DefaultTaskEngine(bus, FakeClock())
        val brain = object : ChatBrain {
            override suspend fun reply(text: String, history: List<LlmTurn>): Outcome<String> =
                Outcome.Success("brain-says:$text")
        }
        val orchestrator = BootstrapOrchestrator(
            tasks, RuleBasedPlanner(), FakeAgent(), RuleVerifier(), checkpoints, conversations,
            brain = brain,
        )
        val conv = (conversations.createConversation("t") as Outcome.Success<Conversation>).value
        val reply = (orchestrator.handleUserMessage(conv.id, "เล่าเรื่องตลกหน่อย")
            as Outcome.Success<OrchestratorReply>).value
        assertEquals(MessageRole.AI, reply.messages[0].role)
        assertTrue(reply.messages[0].text.contains("brain-says:"))
    }

    @Test
    fun chatWithoutBrainKeepsHelpText() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val tasks = DefaultTaskEngine(bus, FakeClock())
        val orchestrator = orchestrator(FakeAgent(), tasks, checkpoints, conversations)
        val conv = (conversations.createConversation("t") as Outcome.Success<Conversation>).value
        val reply = (orchestrator.handleUserMessage(conv.id, "เล่าเรื่องตลกหน่อย")
            as Outcome.Success<OrchestratorReply>).value
        assertEquals(MessageRole.STATUS, reply.messages[0].role)
        assertTrue(reply.messages[0].text.contains("หน้า Models"))
    }
}
