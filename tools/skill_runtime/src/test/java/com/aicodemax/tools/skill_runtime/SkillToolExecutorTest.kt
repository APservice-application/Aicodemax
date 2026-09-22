package com.aicodemax.tools.skill_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.skills.FileSkillStore
import com.aicodemax.tools.files.SandboxFileStore
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillToolExecutorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun executor(workspace: File) = SkillToolExecutor(
        FileSkillStore(File(tmp.root, "skills")),
        SandboxFileStore(workspace),
    )

    @Test
    fun listShowsBuiltins() = runBlocking {
        val result = executor(tmp.newFolder("ws")).execute(ToolCall("1", "skill", "list"))
        val value = (result as Outcome.Success<ToolResult>).value
        assertTrue(value.ok)
        assertTrue(value.output.contains("aicode-tools"))
    }

    @Test
    fun installFromWorkspaceThenInject() = runBlocking {
        val workspace = tmp.newFolder("ws")
        File(workspace, "tip.md").writeText("# tip\ncontent")
        val executor = executor(workspace)
        val installed = executor.execute(
            ToolCall("2", "skill", "install", mapOf("path" to "tip.md")),
        )
        assertTrue((installed as Outcome.Success<ToolResult>).value.ok)
        val injected = executor.execute(
            ToolCall("3", "skill", "inject", mapOf("ids" to "tip")),
        )
        val value = (injected as Outcome.Success<ToolResult>).value
        assertTrue(value.ok)
        assertTrue(value.output.contains("=== SKILL: tip ==="))
    }

    @Test
    fun zipInstallIsHonestAboutUiPath() = runBlocking {
        val result = executor(tmp.newFolder("ws")).execute(
            ToolCall("4", "skill", "install", mapOf("path" to "bundle.zip")),
        )
        val value = (result as Outcome.Success<ToolResult>).value
        assertTrue(!value.ok)
        assertTrue(value.error.contains("หน้า Skills"))
    }
}
