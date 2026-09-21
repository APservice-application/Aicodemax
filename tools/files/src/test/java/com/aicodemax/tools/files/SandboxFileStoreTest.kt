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
    fun copyMoveSearchMetadata() = runBlocking {
        val files: FilePort = SandboxFileStore(tmp.root)
        files.write("a.txt", "hello world\nsecond line")
        files.write("sub/b.txt", "say hello again")

        files.copy("a.txt", "a-copy.txt")
        assertEquals("hello world\nsecond line", (files.read("a-copy.txt") as Outcome.Success<String>).value)

        files.move("a-copy.txt", "sub/moved.txt")
        assertFalse((files.exists("a-copy.txt") as Outcome.Success<Boolean>).value)
        assertTrue((files.exists("sub/moved.txt") as Outcome.Success<Boolean>).value)

        val matches = (files.search("", "hello") as Outcome.Success<List<ContentMatch>>).value
        assertEquals(3, matches.size)
        assertTrue(matches.all { it.line.contains("hello") })

        val meta = (files.metadata("sub/b.txt") as Outcome.Success<FileMetadata>).value
        assertEquals(false, meta.isDirectory)
        assertTrue(meta.sizeBytes > 0)
    }

    @Test
    fun archiveUnarchiveRoundtrip() = runBlocking {
        val files: FilePort = SandboxFileStore(tmp.root)
        files.write("proj/a.txt", "aaa")
        files.write("proj/sub/b.txt", "bbb")

        val bytes = (files.archive("proj", "proj.zip") as Outcome.Success<Long>).value
        assertTrue(bytes > 0)

        files.delete("proj")
        val count = (files.unarchive("proj.zip", "restored") as Outcome.Success<Int>).value
        assertEquals(2, count)
        assertEquals("aaa", (files.read("restored/a.txt") as Outcome.Success<String>).value)
        assertEquals("bbb", (files.read("restored/sub/b.txt") as Outcome.Success<String>).value)
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
