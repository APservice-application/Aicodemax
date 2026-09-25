package com.aicodemax.ai.agents

import com.aicodemax.ai.core.Diagnosis
import com.aicodemax.ai.core.ExecutedStep
import com.aicodemax.ai.core.FailureAction
import com.aicodemax.ai.core.IntentType
import com.aicodemax.ai.core.Plan
import com.aicodemax.ai.core.PlanStep
import com.aicodemax.ai.core.ReplanRequest
import com.aicodemax.ai.core.StepResult
import com.aicodemax.ai.core.UserIntent
import com.aicodemax.ai.models.LlmMessage
import com.aicodemax.ai.models.LlmProvider
import com.aicodemax.ai.models.LlmReply
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.capability.AdapterKind
import com.aicodemax.tools.capability.CapabilityBinding
import com.aicodemax.tools.capability.CapabilityMetadata
import com.aicodemax.tools.capability.CapabilityResolver
import com.aicodemax.tools.capability.CapabilityState
import com.aicodemax.tools.capability.ResolvedCapability
import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-148: LLM plan / re-plan / verify over scripted brains. */
class LlmPlannerTest {
    private class ScriptProvider(private val replies: List<String>) : LlmProvider {
        override val id: String = "script"
        val seen = mutableListOf<String>()
        var calls = 0

        override suspend fun chat(model: String, messages: List<LlmMessage>, maxTokens: Int): Outcome<LlmReply> {
            seen.add(messages.joinToString("\n") { it.content })
            val reply = replies.getOrElse(calls) { replies.last() }
            calls += 1
            return Outcome.Success(LlmReply(reply))
        }

        override suspend fun health(): Outcome<String> = Outcome.Success("ok")
    }

    private fun binding(id: String, purpose: String, vararg inputs: String) = CapabilityBinding(
        capabilityId = id,
        toolId = id.substringBefore("."),
        action = id.substringAfter("."),
        adapterKind = AdapterKind.NATIVE,
        metadata = CapabilityMetadata(purpose = purpose, inputs = inputs.toList()),
    )

    private val catalog = listOf(
        binding("files.read", "read file", "path"),
        binding("files.list", "list dir", "path"),
        binding("editor.set", "write file", "path", "content"),
        binding("browser.open", "open url", "url"),
        binding("terminal.exec", "run shell", "command"),
        binding("memory.save", "remember", "key", "value"),
    )

    private val resolver = object : CapabilityResolver {
        override fun register(binding: CapabilityBinding) = Unit
        override fun bindingsFor(capabilityId: String): List<CapabilityBinding> =
            catalog.filter { it.capabilityId == capabilityId }

        override fun resolve(capabilityId: String, args: Map<String, String>): Outcome<ResolvedCapability> {
            val known = catalog.firstOrNull { it.capabilityId == capabilityId }
                ?: return Outcome.Failure(com.aicodemax.core.common.AppError("NO_CAP", "unknown $capabilityId"))
            return Outcome.Success(
                ResolvedCapability(
                    capabilityId, known.toolId, known.action, AdapterKind.NATIVE, args,
                    ToolDescriptor(
                        known.toolId, known.toolId, "1",
                        listOf(LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE)),
                    ),
                    state = CapabilityState.READY,
                ),
            )
        }
    }

    @Test
    fun planJsonParsesSteps() {
        val parsed = PlanJson.parse(
            "Sure:\n{\"steps\":[{\"capability\":\"files.read\",\"args\":{\"path\":\"a\"},\"why\":\"read it\"}],\"note\":\"n\"}",
        )!!
        assertEquals(1, parsed.steps.size)
        assertEquals("files.read", parsed.steps[0].capability)
        assertEquals("a", parsed.steps[0].args["path"])
        assertEquals("n", parsed.note)
        assertTrue(PlanJson.parse("no json here") == null)
        assertTrue(PlanJson.parse("{\"steps\":[{\"args\":{}}]}") == null)
    }

    @Test
    fun catalogFilterScopesAndCaps() {
        val intent = UserIntent(IntentType.BROWSER_OPEN, "เปิดเว็บ x", mapOf("url" to "x"))
        val picked = CatalogFilter.forIntent(intent, catalog)
        assertTrue(picked.isNotEmpty())
        assertTrue(picked.all { it.capabilityId.startsWith("browser.") || it.capabilityId.startsWith("files.") || it.capabilityId.startsWith("editor.") || it.capabilityId.startsWith("terminal.") || it.capabilityId.startsWith("memory.") })
        assertTrue(picked.size <= CatalogFilter.CAP)
        val chat = UserIntent(IntentType.CHAT, "hi")
        assertTrue(CatalogFilter.forIntent(chat, catalog).isEmpty())
    }

    @Test
    fun plannerResolvesValidJson() = runBlocking {
        val brain = ScriptProvider(
            listOf("{\"steps\":[{\"capability\":\"files.read\",\"args\":{\"path\":\"a.txt\"},\"why\":\"read a\"}]}"),
        )
        val planner = LlmPlanner({ brain }, { "m" }, resolver, catalog)
        val plan = (planner.plan(UserIntent(IntentType.READ_FILE, "อ่านไฟล์ a.txt", mapOf("path" to "a.txt")))
            as Outcome.Success<Plan>).value
        assertEquals(1, plan.steps.size)
        assertEquals("files", plan.steps[0].toolId)
        assertEquals("read", plan.steps[0].action)
        assertEquals("a.txt", plan.steps[0].args["path"])
        assertTrue(brain.seen[0].contains("files.read(path)"))
    }

    @Test
    fun plannerRetriesBadJsonOnce() = runBlocking {
        val brain = ScriptProvider(
            listOf(
                "I will read the file now",
                "{\"steps\":[{\"capability\":\"files.list\",\"args\":{\"path\":\"\"},\"why\":\"list\"}]}",
            ),
        )
        val planner = LlmPlanner({ brain }, { "m" }, resolver, catalog)
        val plan = (planner.plan(UserIntent(IntentType.LIST_FILES, "ดูไฟล์"))
            as Outcome.Success<Plan>).value
        assertEquals("list", plan.steps[0].action)
        assertEquals(2, brain.calls)
    }

    @Test
    fun plannerRejectsUnknownTool() = runBlocking {
        val brain = ScriptProvider(
            listOf("{\"steps\":[{\"capability\":\"teleport.now\",\"args\":{},\"why\":\"magic\"}]}"),
        )
        val planner = LlmPlanner({ brain }, { "m" }, resolver, catalog)
        val err = (planner.plan(UserIntent(IntentType.READ_FILE, "x", mapOf("path" to "x")))
            as Outcome.Failure).error
        assertEquals("PLAN_UNKNOWN_TOOL", err.code)
    }

    @Test
    fun plannerNeedsBrain() = runBlocking {
        val planner = LlmPlanner({ null }, { "m" }, resolver, catalog)
        val err = (planner.plan(UserIntent(IntentType.READ_FILE, "x", mapOf("path" to "x")))
            as Outcome.Failure).error
        assertEquals("BRAIN_OFF", err.code)
    }

    @Test
    fun replannerIncludesDiagnosisAndDone() = runBlocking {
        val brain = ScriptProvider(
            listOf("{\"steps\":[{\"capability\":\"browser.open\",\"args\":{\"url\":\"https://x\"},\"why\":\"fixed url\"}]}"),
        )
        val replanner = LlmRePlanner({ brain }, { "m" }, resolver, catalog)
        val failed = PlanStep("s1", "browser", "open", mapOf("url" to "x"))
        val done = ExecutedStep(PlanStep("s0", "files", "read"), StepResult(true, "r", ""))
        val req = ReplanRequest(
            "open x", listOf(done), failed,
            Diagnosis(FailureAction.FIX_AND_RETRY, "แก้ URL แล้วลองใหม่"), 1,
        )
        val plan = (replanner.replan(req) as Outcome.Success<Plan>).value
        assertEquals("https://x", plan.steps[0].args["url"])
        assertTrue(brain.seen[0].contains("แก้ URL"))
        assertTrue(brain.seen[0].contains("do NOT repeat"))
    }

    @Test
    fun verifierJudgesGoal() = runBlocking {
        val okBrain = ScriptProvider(listOf("{\"ok\":true,\"reason\":\"done\"}"))
        val ok = LlmVerifier({ okBrain }, { "m" }, "read a")
        assertTrue(ok.verify(listOf(StepResult(true, "content", ""))) is Outcome.Success)

        val noBrain = ScriptProvider(listOf("{\"ok\":false,\"reason\":\"wrong file\"}"))
        val no = LlmVerifier({ noBrain }, { "m" }, "read a")
        val err = (no.verify(listOf(StepResult(true, "content", "")))
            as Outcome.Failure).error
        assertEquals("VERIFY_FAILED", err.code)
        assertTrue(err.message.contains("wrong file"))

        // Garbage -> rule fallback (all ok passes).
        val garbage = ScriptProvider(listOf("maybe"))
        val fallback = LlmVerifier({ garbage }, { "m" }, "read a")
        assertTrue(fallback.verify(listOf(StepResult(true, "x", ""))) is Outcome.Success)
        assertTrue(fallback.verify(listOf(StepResult(false, "", "bad"))) is Outcome.Failure)
    }
}
