package com.aicodemax.ai.core

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.capability.StandardCapabilities
import com.aicodemax.tools.media.InMemoryMediaProject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingPlannerTest {
    private fun seeded(): InMemoryMediaProject = InMemoryMediaProject().also { media ->
        runBlocking {
            val project = (media.createProject("demo") as Outcome.Success<com.aicodemax.data.media.Project>).value
            media.importAsset(project.id, "b.mp4")
            media.importAsset(project.id, "a.mp4")
            media.importAsset(project.id, "music.wav")
        }
    }

    @Test
    fun planTikTokAssembly(): Unit = runBlocking {
        val planner = EditingPlanner(seeded())
        val intent = UserIntent(IntentType.MEDIA_EDIT, "t", mapOf("platform" to "TikTok", "goal" to "ขายของ"))
        val plan = (planner.plan(intent) as Outcome.Success<Plan>).value
        // 2 video clips + music bed + version.save.
        assertEquals(4, plan.steps.size)
        assertTrue(plan.steps.all { it.toolId == "media" })
        assertEquals(listOf("timeline.addClip", "timeline.addClip", "timeline.addClip", "version.save"), plan.steps.map { it.action })
        // Name order: a.mp4 first at 0, b.mp4 after, music at 0.
        assertEquals("0", plan.steps[0].args["atMs"])
        assertEquals("30000", plan.steps[1].args["atMs"])
        assertEquals("0", plan.steps[2].args["atMs"])
        assertTrue(plan.note.contains("TikTok"))
        assertTrue(plan.note.contains("ขายของ"))
    }

    @Test
    fun defaultPresetWithoutPlatform(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        val project = (media.createProject("d") as Outcome.Success<com.aicodemax.data.media.Project>).value
        media.importAsset(project.id, "pic.png")
        val plan = (EditingPlanner(media).plan(UserIntent(IntentType.MEDIA_EDIT, "t")) as Outcome.Success<Plan>).value
        assertEquals(2, plan.steps.size) // still + version.save
        assertEquals("3000", plan.steps[0].args["endMs"])
    }

    @Test
    fun noProjectFailsHonestly(): Unit = runBlocking {
        val result = EditingPlanner(InMemoryMediaProject()).plan(UserIntent(IntentType.MEDIA_EDIT, "t"))
        assertEquals("PLAN_NO_PROJECT", (result as Outcome.Failure).error.code)
    }

    @Test
    fun noAssetsFailsHonestly(): Unit = runBlocking {
        val media = InMemoryMediaProject()
        runBlocking { media.createProject("empty") }
        val result = EditingPlanner(media).plan(UserIntent(IntentType.MEDIA_EDIT, "t"))
        assertEquals("PLAN_NO_ASSETS", (result as Outcome.Failure).error.code)
    }

    @Test
    fun ruleBasedPlannerDelegatesMediaEdit(): Unit = runBlocking {
        val hooked = RuleBasedPlanner(StandardCapabilities.defaultResolver(), EditingPlanner(seeded()))
        val plan = (hooked.plan(UserIntent(IntentType.MEDIA_EDIT, "t", mapOf("platform" to "YouTube"))) as Outcome.Success<Plan>).value
        assertTrue(plan.steps.isNotEmpty())

        val bare = RuleBasedPlanner()
        val pending = bare.plan(UserIntent(IntentType.MEDIA_EDIT, "t"))
        assertEquals("PLAN_MEDIA_PENDING", (pending as Outcome.Failure).error.code)
    }
}
