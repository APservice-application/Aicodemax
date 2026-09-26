package com.aicodemax.ai.agents

import com.aicodemax.ai.core.ChatBrain
import com.aicodemax.ai.core.LlmTurn
import com.aicodemax.ai.models.FallbackModelRouter
import com.aicodemax.ai.models.InMemoryModelRegistry
import com.aicodemax.ai.models.ModelDescriptor
import com.aicodemax.ai.models.ModelKind
import com.aicodemax.ai.models.ModelStatus
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutedChatBrainTest {
    private fun descriptor(id: String, kind: ModelKind) = ModelDescriptor(
        id = id, name = id, kind = kind, status = ModelStatus.READY, capabilities = listOf("chat"),
    )

    private fun brain(result: Outcome<String>): ChatBrain = object : ChatBrain {
        override suspend fun reply(text: String, history: List<LlmTurn>): Outcome<String> = result
    }

    private fun registryOf(vararg ds: ModelDescriptor): InMemoryModelRegistry =
        InMemoryModelRegistry().also { ds.forEach { d -> it.register(d) } }

    @Test
    fun localFirstAndFailover() = runBlocking {
        val router = FallbackModelRouter(
            registryOf(descriptor("ext", ModelKind.EXTERNAL), descriptor("loc", ModelKind.LOCAL_FULL)),
        )
        val order = mutableListOf<String>()
        val routed = RoutedChatBrain(
            router,
            resolve = { d ->
                order.add(d.id)
                if (d.id == "loc") brain(Outcome.Failure(AppError("DOWN", "local ดับ"))) else brain(Outcome.Success("from-ext"))
            },
            manual = { null },
        )
        val out = routed.reply("hi", emptyList())
        assertEquals(listOf("loc", "ext"), order)
        assertTrue(out is Outcome.Success && (out as Outcome.Success).value == "from-ext")
    }

    @Test
    fun allFailedNamesModels() = runBlocking {
        val router = FallbackModelRouter(registryOf(descriptor("loc", ModelKind.LOCAL_FULL)))
        val routed = RoutedChatBrain(
            router,
            resolve = { brain(Outcome.Failure(AppError("DOWN", "ดับ"))) },
            manual = { null },
        )
        val out = routed.reply("hi", emptyList())
        assertTrue(out is Outcome.Failure)
        val err = (out as Outcome.Failure).error
        assertEquals("BRAIN_ALL_FAILED", err.code)
        assertTrue(err.message.contains("loc"))
    }

    @Test
    fun emptyFallsBackToManualThenOff() = runBlocking {
        val router = FallbackModelRouter(registryOf())
        val withManual = RoutedChatBrain(router, { null }, { brain(Outcome.Success("manual-ok")) })
        assertEquals("manual-ok", ((withManual.reply("hi", emptyList())) as Outcome.Success).value)
        val bare = RoutedChatBrain(router, { null }, { null })
        val out = bare.reply("hi", emptyList())
        assertTrue(out is Outcome.Failure && (out as Outcome.Failure).error.code == "BRAIN_OFF")
    }

    @Test
    fun connectedManualEndpointIsNotRetriedAfterPaidFailure() = runBlocking {
        val router = FallbackModelRouter(registryOf(descriptor("manual", ModelKind.EXTERNAL)))
        var attempts = 0
        val paid: ChatBrain = object : ChatBrain {
            override suspend fun reply(text: String, history: List<LlmTurn>): Outcome<String> {
                attempts++
                return Outcome.Failure(AppError("BILLED", "unreadable response"))
            }
        }
        val routed = RoutedChatBrain(router, { paid }, { paid })
        assertTrue(routed.reply("hi", emptyList()) is Outcome.Failure)
        assertEquals(1, attempts)
    }

    @Test
    fun routeLineExplains() {
        val router = FallbackModelRouter(
            registryOf(descriptor("ext", ModelKind.EXTERNAL), descriptor("loc", ModelKind.LOCAL_FULL)),
        )
        val routed = RoutedChatBrain(router, { null }, { null })
        assertEquals("สมอง: loc → ext", routed.routeLine())
        val bare = RoutedChatBrain(FallbackModelRouter(registryOf()), { null }, { null })
        assertEquals("สมอง: ยังไม่ต่อ LLM", bare.routeLine())
    }

    @Test
    fun scoringCandidatesBestFirst() {
        val registry = registryOf(
            descriptor("ext", ModelKind.EXTERNAL),
            descriptor("loc", ModelKind.LOCAL_FULL),
        )
        val scoring = com.aicodemax.ai.models.ScoringModelRouter(registry)
        assertEquals(listOf("loc", "ext"), scoring.candidates().map { it.id })
        val empty = com.aicodemax.ai.models.ScoringModelRouter(registryOf())
        assertTrue(empty.candidates().isEmpty())
    }
}
