package com.aicodemax.tools.editor

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.files.FilePort
import com.aicodemax.tools.files.SandboxFileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBackedEditorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun openSetSaveRoundtrip() = runBlocking {
        val files: FilePort = SandboxFileStore(tmp.root)
        val editor: EditorPort = FileBackedEditor(files)

        val opened = (editor.open("a.txt") as Outcome.Success<EditorBuffer>).value
        assertTrue(opened.dirty)
        assertEquals("", opened.content)

        val updated = (editor.setContent("a.txt", "hello") as Outcome.Success<EditorBuffer>).value
        assertTrue(updated.dirty)

        val bytes = (editor.save("a.txt") as Outcome.Success<Long>).value
        assertTrue(bytes > 0)

        val stored = (files.read("a.txt") as Outcome.Success<String>).value
        assertEquals("hello", stored)

        editor.close("a.txt")
        val reopened = (editor.open("a.txt") as Outcome.Success<EditorBuffer>).value
        assertFalse(reopened.dirty)
        assertEquals("hello", reopened.content)
    }

    @Test
    fun saveUnopenedFailsHonestly() = runBlocking {
        val editor: EditorPort = FileBackedEditor(SandboxFileStore(tmp.root))
        val result = editor.save("ghost.txt")
        assertTrue(result is Outcome.Failure)
        assertEquals("EDITOR_NOT_OPEN", (result as Outcome.Failure).error.code)
    }
}
