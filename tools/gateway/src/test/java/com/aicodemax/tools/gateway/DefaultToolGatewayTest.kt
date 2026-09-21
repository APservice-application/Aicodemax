package com.aicodemax.tools.gateway

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.state.AppEvent
import com.aicodemax.core.state.AutonomyLevel
import com.aicodemax.core.state.EventBus
import com.aicodemax.data.audit.AuditLog
import com.aicodemax.data.audit.FileAuditLog
import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.InMemoryToolRegistry
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.capability.AdapterKind
import com.aicodemax.tools.capability.CapabilityBinding
import com.aicodemax.tools.capability.DefaultCapabilityResolver
import com.aicodemax.tools.registry.ToolDescriptor
import com.aicodemax.tools.registry.ToolRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

class FakeExecutor(
    override val toolId: String,
    private val output: String = "ok-output",
) : ToolExecutor {
    val calls = mutableListOf<ToolCall>()
    override suspend fun execute(call: ToolCall): Outcome<ToolResult> {
        calls.add(call)
        return Outcome.Success(ToolResult(ok = true, output = output))
    }
}

class DefaultToolGatewayTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun descriptor(id: String, runnable: Boolean): ToolDescriptor {
        val exec = if (runnable) CapabilityStatus.AVAILABLE else CapabilityStatus.MISSING
        return ToolDescriptor(
            toolId = id,
            displayName = id,
            version = "0.1",
            layers = CapabilityLayer.values().map {
                val status = if (it == CapabilityLayer.RUNTIME || it == CapabilityLayer.EXECUTION) {
                    exec
                } else {
                    CapabilityStatus.AVAILABLE
                }
                LayerCapability(it, status, if (status == CapabilityStatus.MISSING) "no runtime" else "")
            },
        )
    }

    private fun gateway(autonomy: AutonomyLevel = AutonomyLevel.ASK_ALWAYS): Triple<ToolGateway, AuditLog, RecordingBus> {
        val registry: ToolRegistry = InMemoryToolRegistry()
        registry.register(descriptor("demo", true))
        registry.register(descriptor("sleeping", false))
        val audit: AuditLog = FileAuditLog(tmp.root, FakeClock())
        val bus = RecordingBus()
        val gateway: ToolGateway = DefaultToolGateway(registry, AutonomyPermissionGate({ autonomy }), audit, bus)
        gateway.registerExecutor(FakeExecutor("demo"))
        return Triple(gateway, audit, bus)
    }

    @Test
    fun unknownToolFails() = runBlocking {
        val (gateway, _, _) = gateway()
        val result = gateway.call(ToolCall("c1", "nope", "run"))
        assertTrue(result is Outcome.Failure)
        assertEquals("TOOL_UNKNOWN", (result as Outcome.Failure).error.code)
    }

    @Test
    fun nonRunnableToolFailsWithReasons() = runBlocking {
        val (gateway, _, _) = gateway()
        val result = gateway.call(ToolCall("c1", "sleeping", "run"))
        assertTrue(result is Outcome.Failure)
        val error = (result as Outcome.Failure).error
        assertEquals("TOOL_NOT_RUNNABLE", error.code)
        assertTrue(error.message.contains("no runtime"))
    }

    @Test
    fun permissionDeniedIsAudited() = runBlocking {
        val (gateway, audit, _) = gateway(AutonomyLevel.ASK_ALWAYS)
        val result = gateway.call(ToolCall("c1", "demo", "delete", needsPermission = true))
        assertTrue(result is Outcome.Failure)
        assertEquals("PERMISSION_REQUIRED", (result as Outcome.Failure).error.code)

        val entries = (audit.query(5) as Outcome.Success<List<com.aicodemax.data.audit.AuditEntry>>).value
        assertEquals(1, entries.size)
        assertFalse(entries[0].allowed)
    }

    @Test
    fun successPathAuditsAndPublishes() = runBlocking {
        val (gateway, audit, bus) = gateway(AutonomyLevel.AUTO_ALL)
        val result = gateway.call(ToolCall("c1", "demo", "run", actor = "AI"))
        val toolResult = (result as Outcome.Success<ToolResult>).value
        assertTrue(toolResult.ok)
        assertEquals("ok-output", toolResult.output)
        assertTrue(toolResult.auditId.isNotBlank())

        val entries = (audit.query(5) as Outcome.Success<List<com.aicodemax.data.audit.AuditEntry>>).value
        assertEquals(1, entries.size)
        assertTrue(entries[0].allowed)

        val outputs = bus.published.filterIsInstance<AppEvent.ToolOutput>()
        assertEquals(1, outputs.size)
        assertEquals("ok-output", outputs[0].chunk)
    }

    @Test
    fun callCapabilityResolvesThenExecutes() = runBlocking {
        val registry: ToolRegistry = InMemoryToolRegistry()
        registry.register(descriptor("demo", true))
        val gateway: ToolGateway = DefaultToolGateway(
            registry,
            AutonomyPermissionGate({ AutonomyLevel.AUTO_ALL }),
            FileAuditLog(tmp.root, FakeClock()),
            RecordingBus(),
        )
        val fake = FakeExecutor("demo")
        gateway.registerExecutor(fake)
        val resolver = DefaultCapabilityResolver(registry)
        resolver.register(CapabilityBinding("demo.run", "demo", "run", AdapterKind.NATIVE))

        val result = gateway.callCapability(resolver, "demo.run", mapOf("a" to "b"))
        assertTrue(((result as Outcome.Success<ToolResult>).value as ToolResult).ok)
        assertEquals("run", fake.calls.single().action)
        assertEquals("b", fake.calls.single().args["a"])

        val blocked = gateway.callCapability(resolver, "demo.missing")
        assertTrue(blocked is Outcome.Failure)
        assertEquals("CAPABILITY_UNKNOWN", (blocked as Outcome.Failure).error.code)
        assertEquals(1, fake.calls.size) // blocked calls never reach executors
    }

    @Test
    fun grantsAllowAndDeniesBlock() = runBlocking {
        val grants = InMemoryPermissionManager()
        val registry: ToolRegistry = InMemoryToolRegistry()
        registry.register(descriptor("demo", true))
        val gateway: ToolGateway = DefaultToolGateway(
            registry,
            AutonomyPermissionGate({ AutonomyLevel.ASK_ALWAYS }, grants),
            FileAuditLog(tmp.root, FakeClock()),
            RecordingBus(),
        )
        gateway.registerExecutor(FakeExecutor("demo"))

        // No grant → autonomy ASK_ALWAYS denies.
        val denied = gateway.call(ToolCall("c0", "demo", "wipe", needsPermission = true))
        assertEquals("PERMISSION_REQUIRED", ((denied as Outcome.Failure).error as AppError).code)

        // ALLOW_ONCE works exactly once.
        grants.decide("demo", "wipe", "t1", PermissionDecision.ALLOW_ONCE)
        val once1 = gateway.call(ToolCall("c1", "demo", "wipe", mapOf("taskId" to "t1"), needsPermission = true))
        assertTrue((once1 as Outcome.Success<ToolResult>).value.ok)
        val once2 = gateway.call(ToolCall("c2", "demo", "wipe", mapOf("taskId" to "t1"), needsPermission = true))
        assertTrue(once2 is Outcome.Failure)

        // ALLOW_FOR_TASK persists until revoked.
        grants.decide("demo", "wipe", "t2", PermissionDecision.ALLOW_FOR_TASK)
        val task1 = gateway.call(ToolCall("c3", "demo", "wipe", mapOf("taskId" to "t2"), needsPermission = true))
        val task2 = gateway.call(ToolCall("c4", "demo", "wipe", mapOf("taskId" to "t2"), needsPermission = true))
        assertTrue((task1 as Outcome.Success<ToolResult>).value.ok)
        assertTrue((task2 as Outcome.Success<ToolResult>).value.ok)
        grants.revokeTask("t2")
        val revoked = gateway.call(ToolCall("c5", "demo", "wipe", mapOf("taskId" to "t2"), needsPermission = true))
        assertTrue(revoked is Outcome.Failure)

        // DENY wins over everything.
        grants.decide("demo", "nuke", "", PermissionDecision.DENY)
        val hard = gateway.call(ToolCall("c6", "demo", "nuke", needsPermission = true))
        assertEquals("PERMISSION_DENIED", ((hard as Outcome.Failure).error as AppError).code)
    }

    @Test
    fun missingExecutorFails() = runBlocking {
        val registry: ToolRegistry = InMemoryToolRegistry()
        registry.register(descriptor("lonely", true))
        val gateway: ToolGateway = DefaultToolGateway(
            registry,
            AutonomyPermissionGate({ AutonomyLevel.AUTO_ALL }),
            FileAuditLog(tmp.root, FakeClock()),
            RecordingBus(),
        )
        val result = gateway.call(ToolCall("c1", "lonely", "run"))
        assertEquals("TOOL_NO_EXECUTOR", ((result as Outcome.Failure).error as AppError).code)
    }
}
