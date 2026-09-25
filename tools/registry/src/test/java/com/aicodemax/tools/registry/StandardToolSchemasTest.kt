package com.aicodemax.tools.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-147: the AI's tool knowledge — schemas exist, bound flags are honest. */
class StandardToolSchemasTest {
    @Test
    fun browserCoversSpecSection29() {
        val ids = StandardToolSchemas.browserTools().map { it.toolId }.toSet()
        val required = setOf(
            "browser.new_tab", "browser.close_tab", "browser.switch_tab",
            "browser.get_tabs", "browser.get_current_tab",
            "browser.open_url", "browser.search",
            "browser.back", "browser.forward", "browser.reload",
            "browser.click", "browser.type", "browser.scroll", "browser.select",
            "browser.press_key",
            "browser.find", "browser.get_text", "browser.get_links",
            "browser.get_page_info", "browser.screenshot",
            "browser.download", "browser.upload",
        )
        assertTrue("missing tools: " + (required - ids), ids.containsAll(required))
    }

    @Test
    fun everyToolHasRealSchema() {
        for (tool in StandardToolSchemas.allTools()) {
            val schema = tool.schema ?: error("${tool.toolId} has no schema")
            assertTrue(tool.toolId, schema.description.isNotBlank())
            assertTrue(tool.toolId, schema.returns.isNotBlank())
            // Bound <=> runnable: the AI only calls what truly executes.
            assertEquals(tool.toolId, schema.isBound(), tool.isRunnable())
            for (param in schema.params) {
                assertTrue(tool.toolId, param.name.isNotBlank())
            }
        }
    }

    @Test
    fun boundSetMatchesRealExecutors() {
        val bound = StandardToolSchemas.allTools()
            .filter { it.isRunnable() }.map { it.toolId }.toSet()
        // BrowserToolExecutor actions (+ file/git/capability bindings).
        assertTrue("bound=" + bound + " ", "browser.open_url" in bound)
        assertTrue("bound=" + bound + " ", "browser.search" in bound)
        assertTrue("bound=" + bound + " ", "browser.get_current_tab" in bound)
        assertTrue("bound=" + bound + " ", "file.read" in bound)
        assertTrue("bound=" + bound + " ", "git.status" in bound)
        assertFalse("bound=" + bound + " ", "browser.screenshot" in bound)
        assertFalse("bound=" + bound + " ", "code.build" in bound)
    }

    @Test
    fun availableToolsNeverIncludesUnbound() {
        val registry = InMemoryToolRegistry()
        for (tool in StandardToolSchemas.allTools()) registry.register(tool)
        val ids = registry.availableTools().map { it.toolId }.toSet()
        assertFalse("ids=" + ids + " ", "browser.download" in ids)
        assertTrue("ids=" + ids + " ", "browser.new_tab" in ids)
        // Schemas render for prompts without crashing.
        for (tool in registry.availableTools()) {
            assertTrue(tool.schema!!.render().startsWith(tool.toolId))
        }
    }
}
