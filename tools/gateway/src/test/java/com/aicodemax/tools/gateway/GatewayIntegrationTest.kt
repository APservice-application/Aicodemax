package com.aicodemax.tools.gateway

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.state.SharedFlowEventBus
import com.aicodemax.data.audit.FileAuditLog
import com.aicodemax.tools.browser.InMemoryBrowserPort
import com.aicodemax.tools.browser.browserDescriptorToday
import com.aicodemax.tools.browser_runtime.BrowserToolExecutor
import com.aicodemax.tools.editor.EditorToolExecutor
import com.aicodemax.tools.editor.FileBackedEditor
import com.aicodemax.tools.editor.editorDescriptorToday
import com.aicodemax.tools.files.FilePort
import com.aicodemax.tools.files.FilesToolExecutor
import com.aicodemax.tools.files.SandboxFileStore
import com.aicodemax.tools.files.filesDescriptorToday
import com.aicodemax.tools.git.JGitGitPort
import com.aicodemax.tools.git.gitDescriptorToday
import com.aicodemax.tools.git_runtime.GitToolExecutor
import com.aicodemax.tools.registry.InMemoryToolRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CP-52: cross-engine integration through the real gateway pipeline (no fakes). */
class GatewayIntegrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun gateway(workspace: File): ToolGateway {
        val registry = InMemoryToolRegistry()
        registry.register(filesDescriptorToday())
        registry.register(editorDescriptorToday())
        registry.register(gitDescriptorToday())
        registry.register(browserDescriptorToday())
        val gateway = DefaultToolGateway(
            registry,
            AutonomyPermissionGate({ com.aicodemax.core.state.AutonomyLevel.AUTO_ALL }),
            FileAuditLog(File(tmp.root, "audit")),
            SharedFlowEventBus(),
        )
        val files: FilePort = SandboxFileStore(workspace)
        gateway.registerExecutor(FilesToolExecutor(files))
        gateway.registerExecutor(EditorToolExecutor(FileBackedEditor(files)))
        gateway.registerExecutor(GitToolExecutor(JGitGitPort(), workspace.absolutePath))
        gateway.registerExecutor(BrowserToolExecutor(InMemoryBrowserPort()))
        return gateway
    }

    private suspend fun ToolGateway.exec(toolId: String, action: String, args: Map<String, String> = emptyMap()): ToolResult {
        val result = call(ToolCall("it-$toolId-$action", toolId, action, args, actor = "AI"))
        assertTrue("gateway call failed: $result", result is Outcome.Success)
        return (result as Outcome.Success<ToolResult>).value
    }

    @Test
    fun filesEditorGitBrowserFlow() = runBlocking {
        val workspace = tmp.newFolder("ws")
        val gateway = gateway(workspace)

        val written = gateway.exec("files", "write", mapOf("path" to "notes.txt", "content" to "v1"))
        assertTrue(written.ok)

        val previewed = gateway.exec(
            "editor", "preview",
            mapOf("path" to "notes.txt", "startLine" to "1", "endLine" to "2", "replacement" to "v2"),
        )
        assertTrue(previewed.ok)
        assertTrue(previewed.output.contains("v2"))

        // Git over the same workspace: init -> status -> stage -> commit -> log.
        assertTrue(gateway.exec("git", "ensure", mapOf("repo" to workspace.absolutePath)).ok)
        assertTrue(gateway.exec("git", "stage", mapOf("repo" to workspace.absolutePath)).ok)
        val committed = gateway.exec(
            "git", "commit",
            mapOf("repo" to workspace.absolutePath, "message" to "it-commit"),
        )
        assertTrue(committed.ok)
        val log = gateway.exec("git", "log", mapOf("repo" to workspace.absolutePath))
        assertTrue(log.output.contains("it-commit"))

        // Browser tab roundtrip.
        val opened = gateway.exec("browser", "open", mapOf("url" to "example.com"))
        assertTrue(opened.ok)
        val listed = gateway.exec("browser", "list")
        assertTrue(listed.output.contains("example.com"))

        // Ground truth on disk.
        assertEquals("v1", File(workspace, "notes.txt").readText())
        assertTrue(File(workspace, ".git").isDirectory)
    }
}
