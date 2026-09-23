package com.aicodemax.tools.capability

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.InMemoryToolRegistry
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityResolverTest {
    private fun descriptor(toolId: String, runnable: Boolean): ToolDescriptor {
        val status = if (runnable) CapabilityStatus.AVAILABLE else CapabilityStatus.MISSING
        return ToolDescriptor(
            toolId = toolId,
            displayName = toolId,
            version = "t",
            layers = listOf(
                LayerCapability(CapabilityLayer.RUNTIME, status, "test"),
                LayerCapability(CapabilityLayer.EXECUTION, status, "test"),
            ),
        )
    }

    @Test
    fun nativeWinsOverCliAdapter() {
        val registry = InMemoryToolRegistry()
        registry.register(descriptor("files", true))
        registry.register(descriptor("terminal", true))
        val resolver = DefaultCapabilityResolver(registry)
        // CLI adapter registered FIRST on purpose — native must still win.
        resolver.register(CapabilityBinding("files.read", "terminal", "exec", AdapterKind.CLI_ADAPTER))
        resolver.register(CapabilityBinding("files.read", "files", "read", AdapterKind.NATIVE))

        val resolved = (resolver.resolve("files.read") as Outcome.Success<ResolvedCapability>).value
        assertEquals("files", resolved.toolId)
        assertEquals(AdapterKind.NATIVE, resolved.adapterKind)
    }

    @Test
    fun learnedPreferenceOrdersSameKindCandidates() {
        // CP-114: flaky tool demoted below a neutral peer of the same kind.
        val registry = InMemoryToolRegistry()
        registry.register(descriptor("toolA", true))
        registry.register(descriptor("toolB", true))
        val prefs = LearnedPreferences { toolId, _ -> if (toolId == "toolA") -1 else 0 }
        val resolver = DefaultCapabilityResolver(registry, prefs)
        resolver.register(CapabilityBinding("x.do", "toolA", "do", AdapterKind.NATIVE))
        resolver.register(CapabilityBinding("x.do", "toolB", "do", AdapterKind.NATIVE))

        val resolved = (resolver.resolve("x.do") as Outcome.Success<ResolvedCapability>).value
        assertEquals("toolB", resolved.toolId)
    }

    @Test
    fun cliAdapterIsUsedOnlyAsFallback() {
        val registry = InMemoryToolRegistry()
        registry.register(descriptor("files", false))
        registry.register(descriptor("terminal", true))
        val resolver = DefaultCapabilityResolver(registry)
        resolver.register(CapabilityBinding("files.read", "files", "read", AdapterKind.NATIVE))
        resolver.register(CapabilityBinding("files.read", "terminal", "exec", AdapterKind.CLI_ADAPTER))

        val resolved = (resolver.resolve("files.read") as Outcome.Success<ResolvedCapability>).value
        assertEquals("terminal", resolved.toolId)
        assertEquals(AdapterKind.CLI_ADAPTER, resolved.adapterKind)
    }

    @Test
    fun blockedAndUnknownAreHonest() {
        val registry = InMemoryToolRegistry()
        registry.register(descriptor("terminal", false))
        val resolver = DefaultCapabilityResolver(registry)
        resolver.register(CapabilityBinding("terminal.exec", "terminal", "exec", AdapterKind.CLI_ADAPTER))

        val blocked = resolver.resolve("terminal.exec")
        assertTrue(blocked is Outcome.Failure)
        assertEquals("CAPABILITY_BLOCKED", (blocked as Outcome.Failure).error.code)
        assertTrue(blocked.error.message.contains("terminal.exec"))

        val unknown = resolver.resolve("nope.nope")
        assertTrue(unknown is Outcome.Failure)
        assertEquals("CAPABILITY_UNKNOWN", (unknown as Outcome.Failure).error.code)
    }

    @Test
    fun standardCatalogCarriesSection30Metadata() {
        val bindings = StandardCapabilities.bindings()
        assertTrue(bindings.size >= 37)
        bindings.forEach { binding ->
            val meta = binding.metadata
            assertTrue("purpose missing: ${binding.capabilityId}", meta.purpose.isNotBlank())
            assertTrue("engine missing: ${binding.capabilityId}", meta.engine.isNotBlank())
            assertTrue("runtime missing: ${binding.capabilityId}", meta.runtime.isNotBlank())
            assertTrue("category missing: ${binding.capabilityId}", meta.category.isNotBlank())
        }
        val resolver = StandardCapabilities.defaultResolver()
        val read = (resolver.resolve("files.read") as Outcome.Success<ResolvedCapability>).value
        assertEquals(CapabilityState.READY, read.state)
        assertEquals("File Engine", read.metadata.engine)
        assertTrue(read.metadata.purpose.isNotBlank())
    }

    @Test
    fun standardCatalogResolvesTodayTools() {
        val resolver = StandardCapabilities.defaultResolver()
        val read = (resolver.resolve("files.read") as Outcome.Success<ResolvedCapability>).value
        assertEquals("files", read.toolId)

        // CP-113: terminal is runnable (real system shell) → resolves, never a guess.
        val exec = (resolver.resolve("terminal.exec") as Outcome.Success<ResolvedCapability>).value
        assertEquals("terminal", exec.toolId)
        assertEquals("exec", exec.action)
    }
}
