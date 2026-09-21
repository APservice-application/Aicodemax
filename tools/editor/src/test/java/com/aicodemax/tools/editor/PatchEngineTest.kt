package com.aicodemax.tools.editor

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.files.FilePort
import com.aicodemax.tools.files.SandboxFileStore
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PatchEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun applyAndInvertRoundtrip() {
        val content = "l1\nl2\nl3\nl4"
        val ops = listOf(EditOp(2, 4, "NEW"))
        val applied = (PatchEngine.apply(content, ops) as Outcome.Success<String>).value
        assertEquals("l1\nNEW\nl4", applied)

        val inverse = (PatchEngine.invert(content, ops) as Outcome.Success<List<EditOp>>).value
        val restored = (PatchEngine.apply(applied, inverse) as Outcome.Success<String>).value
        assertEquals(content, restored)
    }

    @Test
    fun badRangesFailHonestly() {
        val empty = PatchEngine.apply("a", emptyList())
        assertEquals("PATCH_EMPTY", ((empty as Outcome.Failure).error as com.aicodemax.core.common.AppError).code)

        val bad = PatchEngine.apply("a\nb", listOf(EditOp(5, 6, "x")))
        assertEquals("PATCH_RANGE", ((bad as Outcome.Failure).error as com.aicodemax.core.common.AppError).code)
    }

    @Test
    fun diffPreviewShowsChange() {
        val diff = PatchEngine.previewDiff("a\nb\nc", "a\nB\nc")
        assertTrue(diff.contains("- b"))
        assertTrue(diff.contains("+ B"))
        assertEquals("(no changes)", PatchEngine.previewDiff("same", "same"))
    }

    @Test
    fun executorPreviewAndPatch() = runBlocking {
        val files: FilePort = SandboxFileStore(tmp.root)
        files.write("a.txt", "l1\nl2\nl3")
        val editor: EditorPort = FileBackedEditor(files)
        val executor = EditorToolExecutor(editor)
        val args = mapOf("path" to "a.txt", "startLine" to "2", "endLine" to "3", "replacement" to "NEW")

        val preview = run {
            val r = executor.execute(ToolCall("c1", "editor", "preview", args)) as Outcome.Success<ToolResult>
            r.value
        }
        assertTrue(preview.ok)
        assertTrue(preview.output.contains("- l2"))
        assertTrue(preview.output.contains("+ NEW"))
        // Preview writes nothing.
        assertEquals("l1\nl2\nl3", (files.read("a.txt") as Outcome.Success<String>).value)

        val patched = run {
            val r = executor.execute(ToolCall("c2", "editor", "patch", args)) as Outcome.Success<ToolResult>
            r.value
        }
        assertTrue(patched.ok)
        editor.save("a.txt")
        assertEquals("l1\nNEW\nl3", (files.read("a.txt") as Outcome.Success<String>).value)
    }
}
