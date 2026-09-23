package com.aicodemax.tools.capability

import org.junit.Assert.assertTrue
import org.junit.Test

class GbnfExportTest {
    @Test
    fun grammarCoversEveryCapability() {
        val bindings = StandardCapabilities.bindings()
        val grammar = GbnfExport.toolCallGrammar(bindings)
        assertTrue(grammar.contains("root"))
        assertTrue(grammar.contains("tool-name"))
        for (binding in bindings) {
            assertTrue("missing ${binding.capabilityId}", grammar.contains("\"${binding.capabilityId}\""))
        }
        // Quotation balance: every id contributes a matched pair.
        assertTrue(grammar.count { it == '"' } % 2 == 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyBindingsRejected() {
        GbnfExport.toolCallGrammar(emptyList())
    }
}
