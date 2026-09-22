package com.aicodemax.data.skills

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.ZipArchive
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = FileSkillStore(tmp.newFolder("skills"))

    @Test
    fun builtinsAlwaysListed() {
        val store = store()
        val metas = (store.list() as Outcome.Success<List<SkillMeta>>).value
        assertTrue(metas.any { it.id == "aicode-tools" && it.builtin })
        assertTrue(metas.any { it.id == "git-flow" && it.builtin })
        val skill = (store.get("aicode-tools") as Outcome.Success<Skill>).value
        assertTrue(skill.content.contains("capabilities"))
    }

    @Test
    fun installListGetRemoveRoundtrip() {
        val store = store()
        val source = File(tmp.root, "my-note.md").apply { writeText("# hello\nrules here") }
        val installed = (store.install(source) as Outcome.Success<List<SkillMeta>>).value
        assertEquals("my-note", installed.single().id)
        assertTrue((store.list() as Outcome.Success<List<SkillMeta>>).value.any { it.id == "my-note" })
        val got = (store.get("my-note") as Outcome.Success<Skill>).value
        assertTrue(got.content.contains("rules here"))
        assertTrue(store.remove("my-note") is Outcome.Success)
        assertTrue(store.get("my-note") is Outcome.Failure)
    }

    @Test
    fun binaryAndBadTypeRejected() {
        val store = store()
        val bin = File(tmp.root, "evil.md").apply { writeBytes(byteArrayOf(1, 2, 0, 3)) }
        val rejected = store.install(bin)
        assertTrue(rejected is Outcome.Failure)
        assertEquals("SKILL_BINARY", (rejected as Outcome.Failure).error.code)
        val exe = File(tmp.root, "run.exe").apply { writeText("x") }
        assertTrue(store.install(exe) is Outcome.Failure)
    }

    @Test
    fun builtinsCannotBeRemoved() {
        val result = store().remove("aicode-tools")
        assertTrue(result is Outcome.Failure)
        assertEquals("SKILL_BUILTIN", (result as Outcome.Failure).error.code)
    }

    @Test
    fun zipBundleInstallsTextOnly() {
        val store = store()
        val dir = tmp.newFolder("bundle")
        File(dir, "one.md").writeText("# one")
        File(dir, "two.txt").writeText("two")
        File(dir, "skip.exe").writeText("x")
        val zip = File(tmp.root, "bundle.zip")
        assertTrue(ZipArchive.zipDir(dir, zip) is Outcome.Success)
        val installed = (store.install(zip) as Outcome.Success<List<SkillMeta>>).value
        assertEquals(setOf("one", "two"), installed.map { it.id }.toSet())
    }

    @Test
    fun injectFormatsBlocksAndSkipsMissing() {
        val store = store()
        File(tmp.root, "a.md").apply { writeText("AAA") }
            .let { store.install(it) }
        val out = (SkillInjector.injectIds(listOf("a", "ghost"), store) as Outcome.Success<String>).value
        assertTrue(out.contains("=== SKILL: a ==="))
        assertTrue(out.contains("AAA"))
        assertTrue(out.contains("ghost"))
    }
}
