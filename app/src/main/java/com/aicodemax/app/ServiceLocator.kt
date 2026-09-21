package com.aicodemax.app

import android.content.Context
import com.aicodemax.ai.agents.LocalAgentRunner
import com.aicodemax.ai.core.BootstrapOrchestrator
import com.aicodemax.ai.core.Orchestrator
import com.aicodemax.ai.core.RuleBasedPlanner
import com.aicodemax.ai.core.RuleVerifier
import com.aicodemax.ai.models.FallbackModelRouter
import com.aicodemax.ai.models.InMemoryModelRegistry
import com.aicodemax.ai.models.ModelRegistry
import com.aicodemax.ai.models.ModelRouter
import com.aicodemax.ai.tasks.DefaultTaskEngine
import com.aicodemax.ai.tasks.TaskEngine
import com.aicodemax.core.resources.AndroidResourceMonitor
import com.aicodemax.core.resources.ResourceMonitor
import com.aicodemax.core.state.AutonomyLevel
import com.aicodemax.core.state.EventBus
import com.aicodemax.core.state.InMemoryAppStateStore
import com.aicodemax.core.state.AppStateStore
import com.aicodemax.core.state.SharedFlowEventBus
import com.aicodemax.data.audit.AuditLog
import com.aicodemax.data.audit.FileAuditLog
import com.aicodemax.data.checkpoint.CheckpointStore
import com.aicodemax.data.checkpoint.FileCheckpointStore
import com.aicodemax.data.conversations.ConversationStore
import com.aicodemax.data.conversations.FileConversationStore
import com.aicodemax.data.memory.FileMemoryStore
import com.aicodemax.data.memory.MemoryStore
import com.aicodemax.data.settings.DataStoreSettingsRepository
import com.aicodemax.data.settings.SettingsRepository
import com.aicodemax.tools.browser.InMemoryBrowserPort
import com.aicodemax.tools.browser.browserDescriptorToday
import com.aicodemax.tools.browser_runtime.BrowserToolExecutor
import com.aicodemax.tools.builder.buildDescriptorToday
import com.aicodemax.tools.editor.EditorPort
import com.aicodemax.tools.editor.EditorToolExecutor
import com.aicodemax.tools.editor.FileBackedEditor
import com.aicodemax.tools.editor.editorDescriptorToday
import com.aicodemax.tools.files.FilePort
import com.aicodemax.tools.files.FilesToolExecutor
import com.aicodemax.tools.files.SandboxFileStore
import com.aicodemax.tools.files.filesDescriptorToday
import com.aicodemax.tools.gateway.AutonomyPermissionGate
import com.aicodemax.tools.gateway.DefaultToolGateway
import com.aicodemax.tools.gateway.ToolGateway
import com.aicodemax.tools.git.GitPort
import com.aicodemax.tools.git.JGitGitPort
import com.aicodemax.tools.git.gitDescriptorToday
import com.aicodemax.tools.git_runtime.GitToolExecutor
import com.aicodemax.tools.registry.InMemoryToolRegistry
import com.aicodemax.tools.registry.ToolRegistry
import com.aicodemax.tools.terminal.terminalDescriptorToday
import java.io.File

/** Manual DI root (Phase 1). A Hilt migration later must not change any interface. */
class ServiceLocator(context: Context) {
    private val appContext = context.applicationContext
    private var autonomyLevel = AutonomyLevel.ASK_ALWAYS

    val bus: EventBus = SharedFlowEventBus()
    val appState: AppStateStore = InMemoryAppStateStore()

    val audit: AuditLog = FileAuditLog(File(appContext.filesDir, "audit"))
    val checkpoints: CheckpointStore = FileCheckpointStore(File(appContext.filesDir, "state"))
    val conversations: ConversationStore = FileConversationStore(File(appContext.filesDir, "state"))
    val memory: MemoryStore = FileMemoryStore(File(appContext.filesDir, "state"))
    val settings: SettingsRepository = DataStoreSettingsRepository(appContext)

    val resources: ResourceMonitor = AndroidResourceMonitor(appContext)

    val toolRegistry: ToolRegistry = InMemoryToolRegistry()
    val gateway: ToolGateway

    val workspaceDir: File = File(appContext.filesDir, "workspace")
    val files: FilePort = SandboxFileStore(workspaceDir)
    val editor: EditorPort = FileBackedEditor(files)
    val git: GitPort = JGitGitPort()
    val browser: InMemoryBrowserPort = InMemoryBrowserPort()

    val tasks: TaskEngine = DefaultTaskEngine(bus)
    val models: ModelRegistry = InMemoryModelRegistry()
    val router: ModelRouter = FallbackModelRouter(models)

    val orchestrator: Orchestrator

    init {
        // Honest capability snapshots for every tool (100% contract).
        toolRegistry.register(filesDescriptorToday())
        toolRegistry.register(editorDescriptorToday())
        toolRegistry.register(terminalDescriptorToday())
        toolRegistry.register(browserDescriptorToday())
        toolRegistry.register(buildDescriptorToday())
        toolRegistry.register(gitDescriptorToday())

        gateway = DefaultToolGateway(toolRegistry, AutonomyPermissionGate { autonomyLevel }, audit, bus)
        gateway.registerExecutor(FilesToolExecutor(files))
        gateway.registerExecutor(EditorToolExecutor(editor))
        gateway.registerExecutor(GitToolExecutor(git))
        gateway.registerExecutor(BrowserToolExecutor(browser))

        val agent = LocalAgentRunner(gateway)
        orchestrator = BootstrapOrchestrator(tasks, RuleBasedPlanner(), agent, RuleVerifier(), checkpoints, conversations)
    }

    fun refreshAutonomy(level: AutonomyLevel) {
        autonomyLevel = level
    }
}
