package com.aicodemax.ai.core

import com.aicodemax.ai.tasks.DefaultTaskEngine
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.data.checkpoint.CheckpointStore
import com.aicodemax.data.checkpoint.FileCheckpointStore
import com.aicodemax.data.conversations.ConversationStore
import com.aicodemax.data.conversations.FileConversationStore
import com.aicodemax.data.conversations.MessageRole
import com.aicodemax.data.memory.MemoryRecord
import com.aicodemax.data.memory.MemoryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CP-148: orchestrator self-correction + login handoff (spec §31/§33/§34). */
class OrchestratorReplanTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class SeqAgent(private val results: List<Outcome<StepResult>>) : AgentExecutor {
        val seen = mutableListOf<PlanStep>()
        override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> {
            seen.add(step)
            return results.getOrElse(seen.size - 1) { results.last() }
        }
    }

    private class ScriptPlanner(private val plan: Plan) : Planner {
        override suspend fun plan(intent: UserIntent): Outcome<Plan> = Outcome.Success(plan)
    }

    private class ScriptRePlanner(private val plans: List<Outcome<Plan>>) : RePlanner {
        val seen = mutableListOf<ReplanRequest>()
        override suspend fun replan(req: ReplanRequest): Outcome<Plan> {
            seen.add(req)
            return plans.getOrElse(seen.size - 1) { plans.last() }
        }
    }

    private class FakeMemStore : MemoryStore {
        val records = mutableListOf<MemoryRecord>()
        override fun save(scope: String, key: String, value: String): Outcome<MemoryRecord> {
            val rec = MemoryRecord("m${records.size}", scope, key, value, 1L, 1L)
            records.add(rec)
            return Outcome.Success(rec)
        }

        override fun recall(scope: String, key: String): Outcome<MemoryRecord> =
            Outcome.Failure(AppError("NO", "missing"))

        override fun search(query: String, scopePrefix: String, limit: Int): Outcome<List<MemoryRecord>> =
            Outcome.Success(records.filter { it.scope.startsWith(scopePrefix) }.take(limit))

        override fun delete(scope: String, key: String): Boolean = false
    }

    private fun stores(): Triple<CheckpointStore, ConversationStore, RecordingBus> =
        Triple(
            FileCheckpointStore(tmp.root, FakeClock()),
            FileConversationStore(tmp.root, FakeClock()),
            RecordingBus(),
        )

    private fun ok(text: String = "did it"): Outcome<StepResult> =
        Outcome.Success(StepResult(true, text, ""))

    private fun fail(code: String, msg: String): Outcome<StepResult> =
        Outcome.Failure(AppError(code, msg))

    @Test
    fun replanRepairsFailedStep() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val agent = SeqAgent(listOf(fail("ELEMENT_NOT_FOUND", "ELEMENT_NOT_FOUND #q"), ok("opened")))
        val replan = ScriptRePlanner(listOf(Outcome.Success(Plan(listOf(PlanStep("s2", "browser", "read"))))))
        val orch = BootstrapOrchestrator(
            DefaultTaskEngine(bus, FakeClock()),
            ScriptPlanner(Plan(listOf(PlanStep("s1", "browser", "open", mapOf("url" to "x"))))),
            agent, RuleVerifier(), checkpoints, conversations,
            rePlanner = replan,
        )
        val conv = (conversations.createConversation("c") as Outcome.Success).value
        val reply = (orch.handleUserMessage(conv.id, "สร้างไฟล์ n.txt: hi") as Outcome.Success).value
        assertTrue(reply.messages.any { it.role == MessageRole.AI && it.text.contains("เสร็จแล้ว") })
        assertEquals(1, replan.seen.size)
        assertEquals("s1", replan.seen[0].failedStep?.id)
        assertEquals(2, agent.seen.size)
        assertEquals("s2", agent.seen[1].id)
    }

    @Test
    fun invalidUrlFixedWithoutReplan() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val agent = SeqAgent(listOf(fail("INVALID_URL", "INVALID_URL bad"), ok("opened")))
        val replan = ScriptRePlanner(listOf(Outcome.Success(Plan(emptyList()))))
        val orch = BootstrapOrchestrator(
            DefaultTaskEngine(bus, FakeClock()),
            ScriptPlanner(Plan(listOf(PlanStep("s1", "browser", "open", mapOf("url" to "example.com"))))),
            agent, RuleVerifier(), checkpoints, conversations,
            rePlanner = replan,
        )
        val conv = (conversations.createConversation("c") as Outcome.Success).value
        val reply = (orch.handleUserMessage(conv.id, "สร้างไฟล์ n.txt: hi") as Outcome.Success).value
        assertTrue(reply.messages.any { it.role == MessageRole.AI && it.text.contains("เสร็จแล้ว") })
        assertTrue(replan.seen.isEmpty())
        assertEquals("https://example.com", agent.seen[1].args["url"])
    }

    @Test
    fun handoffParksAndResumes() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val agent = SeqAgent(listOf(fail("AUTH_REQUIRED", "AUTH_REQUIRED login wall"), ok("opened")))
        val orch = BootstrapOrchestrator(
            DefaultTaskEngine(bus, FakeClock()),
            ScriptPlanner(Plan(listOf(PlanStep("s1", "browser", "open", mapOf("url" to "x"))))),
            agent, RuleVerifier(), checkpoints, conversations,
            rePlanner = ScriptRePlanner(listOf(Outcome.Success(Plan(emptyList())))),
        )
        val conv = (conversations.createConversation("c") as Outcome.Success).value
        val parked = (orch.handleUserMessage(conv.id, "สร้างไฟล์ n.txt: hi") as Outcome.Success).value
        assertTrue(parked.taskId != null)
        assertTrue(parked.messages.any { it.text.contains("ล็อกอิน") && it.text.contains("ทำต่อ") })

        val resumed = (orch.handleUserMessage(conv.id, "ทำต่อ") as Outcome.Success).value
        assertTrue(resumed.messages.any { it.role == MessageRole.AI && it.text.contains("เสร็จแล้ว") })
        assertEquals(2, agent.seen.size)
    }

    @Test
    fun verifyFailureReplans() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val agent = SeqAgent(listOf(ok("ran")))
        var verifyCalls = 0
        val verifier = object : Verifier {
            override suspend fun verify(outputs: List<StepResult>): Outcome<Unit> {
                verifyCalls++
                return if (verifyCalls == 1) Outcome.Failure(AppError("VERIFY_FAILED", "cart is empty"))
                else Outcome.Success(Unit)
            }
        }
        val replan = ScriptRePlanner(listOf(Outcome.Success(Plan(emptyList()))))
        val orch = BootstrapOrchestrator(
            DefaultTaskEngine(bus, FakeClock()),
            ScriptPlanner(Plan(listOf(PlanStep("s1", "files", "read")))),
            agent, verifier, checkpoints, conversations,
            rePlanner = replan,
        )
        val conv = (conversations.createConversation("c") as Outcome.Success).value
        val reply = (orch.handleUserMessage(conv.id, "สร้างไฟล์ n.txt: hi") as Outcome.Success).value
        assertTrue(reply.messages.any { it.role == MessageRole.AI && it.text.contains("เสร็จแล้ว") })
        assertEquals(1, replan.seen.size)
        assertEquals(2, verifyCalls)
    }

    @Test
    fun replanBudgetExhaustsHonestly() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val agent = SeqAgent(listOf(fail("ELEMENT_NOT_FOUND", "ELEMENT_NOT_FOUND #q")))
        val replan = ScriptRePlanner(listOf(Outcome.Success(Plan(listOf(PlanStep("n1", "browser", "open"))))))
        val orch = BootstrapOrchestrator(
            DefaultTaskEngine(bus, FakeClock()),
            ScriptPlanner(Plan(listOf(PlanStep("s1", "browser", "open")))),
            agent, RuleVerifier(), checkpoints, conversations,
            rePlanner = replan, maxReplans = 1,
        )
        val conv = (conversations.createConversation("c") as Outcome.Success).value
        val reply = (orch.handleUserMessage(conv.id, "สร้างไฟล์ n.txt: hi") as Outcome.Success).value
        assertTrue(reply.messages.any { it.text.contains("ครบ 1 ครั้ง") })
        assertEquals(1, replan.seen.size)
    }

    @Test
    fun taskNotesWrittenToMemory() = runBlocking {
        val (checkpoints, conversations, bus) = stores()
        val store = FakeMemStore()
        val orch = BootstrapOrchestrator(
            DefaultTaskEngine(bus, FakeClock()),
            ScriptPlanner(Plan(listOf(PlanStep("s1", "files", "read")))),
            SeqAgent(listOf(ok("content"))),
            RuleVerifier(), checkpoints, conversations,
            agentMemory = AgentMemory(store),
        )
        val conv = (conversations.createConversation("c") as Outcome.Success).value
        orch.handleUserMessage(conv.id, "สร้างไฟล์ n.txt: hi")
        assertTrue(store.records.any { it.scope.startsWith("TASK:") && it.value.contains("✅") })
    }
}
