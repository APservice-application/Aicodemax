package com.aicodemax.tools.project

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun createListActiveDelete() {
        val manager = ProjectManager(tmp.root)
        val a = (manager.create("Alpha") as Outcome.Success<Project>).value
        val b = (manager.create("Beta") as Outcome.Success<Project>).value

        val listed = (manager.list() as Outcome.Success<List<Project>>).value
        assertEquals(listOf("Alpha", "Beta"), listed.map { it.name })

        assertTrue(manager.getActive() is Outcome.Failure)
        manager.setActive(a.id)
        assertEquals("Alpha", (manager.getActive() as Outcome.Success<Project>).value.name)

        assertTrue(manager.delete(b.id) is Outcome.Success)
        assertEquals(1, (manager.list() as Outcome.Success<List<Project>>).value.size)

        // Deleting the active project clears it.
        assertTrue(manager.delete(a.id) is Outcome.Success)
        assertTrue(manager.getActive() is Outcome.Failure)
    }

    @Test
    fun badNamesFailHonestly() {
        val manager = ProjectManager(tmp.root)
        assertTrue(manager.create("  ") is Outcome.Failure)
        assertTrue(manager.create("a/b") is Outcome.Failure)
        assertTrue(manager.get("ghost") is Outcome.Failure)
        assertTrue(manager.setActive("ghost") is Outcome.Failure)
    }
}
