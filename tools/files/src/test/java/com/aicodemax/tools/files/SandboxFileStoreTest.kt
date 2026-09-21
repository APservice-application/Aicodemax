package com.aicodemax.tools.files

import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxFileStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writeReadListDeleteRoundtrip() = runBlocking {
        val files: FilePort = SandboxFileStore(tmp.root)

        val bytes = (files.write("docs/notes.txt", "hello") as Outcome.Success<Long>).value
        assertTrue(bytes > 0)

        val content = (files.read("docs/notes.txt") as Outcome.Success<String>).value
        assertEquals("hello", content)

        val root = (files.list("") as Outcome.Success<List<FileEntry>>).value
        assertEquals(listOf("docs"), root.map { it.name })
        assertTrue(root[0].isDirectory)

        val docs = (files.list("docs") as Outcome.Success<List<FileEntry>>).value
        assertEquals(listOf("notes.txt"), docs.map { it.name })

        assertTrue((files.exists("docs/notes.txt") as Outcome.Success<Boolean>).value)
        assertTrue(files.delete("docs/notes.txt") is Outcome.Success)
        assertFalse((files.exists("docs/notes.txt") as Outcome.Success<Boolean>).value)
    }

    @Test
    fun pathEscapeIsBlocked() = runBlocking {
        val files: FilePort = SandboxFileStore(tmp.root)
        val result = files.write("../evil.txt", "x")
        assertTrue(result is Outcome.Failure)
        assertEquals("PATH_ESCAPE", (result as Outcome.Failure).error.code)
    }

    @Test
    fun missingFileFailsHonestly() = runBlocking {
        val files: FilePort = SandboxFileStore(tmp.root)
        assertTrue(files.read("nope.txt") is Outcome.Failure)
        assertTrue(files.list("nope") is Outcome.Failure)
    }
}
