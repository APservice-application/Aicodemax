package com.aicodemax.app

import android.content.Context
import android.webkit.WebView
import com.aicodemax.ai.agents.LlmBrain
import com.aicodemax.ai.agents.RoutedChatBrain
import com.aicodemax.ai.agents.LocalAgentRunner
import com.aicodemax.ai.core.BootstrapOrchestrator
import com.aicodemax.ai.core.EditingPlanner
import com.aicodemax.ai.core.InMemoryQuestionnaireStore
import com.aicodemax.ai.core.LearningEngine
import com.aicodemax.ai.core.Orchestrator
import com.aicodemax.ai.core.RuleBasedPlanner
import com.aicodemax.ai.core.RuleVerifier
import com.aicodemax.ai.models.FallbackModelRouter
import com.aicodemax.ai.models.InMemoryModelRegistry
import com.aicodemax.ai.models.JavaNetModelDownloader
import com.aicodemax.ai.models.LlmMessage
import com.aicodemax.ai.models.LlmProvider
import com.aicodemax.ai.models.ModelDescriptor
import com.aicodemax.ai.models.ModelKind
import com.aicodemax.ai.models.ModelStatus
import com.aicodemax.ai.models.ModelInstaller
import com.aicodemax.ai.models.ModelRegistry
import com.aicodemax.ai.models.ModelRouter
import com.aicodemax.ai.tasks.DefaultTaskEngine
import com.aicodemax.ai.tasks.RecoveryLadderPolicy
import com.aicodemax.ai.tasks.TaskEngine
import com.aicodemax.core.resources.AndroidResourceMonitor
import com.aicodemax.core.resources.ResourceMonitor
import com.aicodemax.core.state.AutonomyLevel
import com.aicodemax.core.state.EventBus
import com.aicodemax.core.state.InMemoryAppStateStore
import com.aicodemax.core.state.AppStateStore
import com.aicodemax.core.state.InMemoryWorkingSetStore
import com.aicodemax.core.state.SharedFlowEventBus
import com.aicodemax.core.state.WorkingSetStore
import com.aicodemax.data.audit.AuditLog
import com.aicodemax.data.audit.FileAuditLog
import com.aicodemax.data.checkpoint.CheckpointStore
import com.aicodemax.data.checkpoint.FileCheckpointStore
import com.aicodemax.data.conversations.ConversationStore
import com.aicodemax.data.conversations.FileConversationStore
import com.aicodemax.data.memory.FileMemoryStore
import com.aicodemax.data.memory.MemoryEngine
import com.aicodemax.data.memory.memoryDescriptorToday
import com.aicodemax.data.skills.BuiltinSkills
import com.aicodemax.data.skills.FileSkillStore
import com.aicodemax.data.skills.skillDescriptorToday
import com.aicodemax.data.memory.MemoryStore
import com.aicodemax.data.settings.DataStoreSettingsRepository
import com.aicodemax.data.settings.SettingsRepository
import com.aicodemax.tools.browser.BrowserLibrary
import com.aicodemax.tools.browser.BrowserPort
import com.aicodemax.tools.browser.InMemoryBrowserPort
import com.aicodemax.tools.browser.browserDescriptorToday
import com.aicodemax.tools.capability.CapabilityResolver
import com.aicodemax.tools.capability.StandardCapabilities
import com.aicodemax.tools.browser_runtime.BrowserToolExecutor
import com.aicodemax.tools.builder.ArtifactStore
import com.aicodemax.tools.builder.PipelineBuildEngine
import com.aicodemax.tools.builder.buildDescriptorToday
import com.aicodemax.tools.debug.debugDescriptorToday
import com.aicodemax.tools.debug_runtime.DebugToolExecutor
import com.aicodemax.tools.editor.EditorPort
import com.aicodemax.tools.editor.EditorToolExecutor
import com.aicodemax.tools.editor.FileBackedEditor
import com.aicodemax.tools.editor.editorDescriptorToday
import com.aicodemax.tools.files.FilePort
import com.aicodemax.tools.files.FilesToolExecutor
import com.aicodemax.tools.files.SandboxFileStore
import com.aicodemax.tools.files.filesDescriptorToday
import com.aicodemax.tools.gateway.ApprovalCenter
import com.aicodemax.tools.gateway.AutonomyPermissionGate
import com.aicodemax.tools.gateway.DefaultToolGateway
import com.aicodemax.tools.gateway.InMemoryPermissionManager
import com.aicodemax.tools.gateway.PermissionManager
import com.aicodemax.tools.gateway.ToolGateway
import com.aicodemax.tools.git.GitPort
import com.aicodemax.tools.git.JGitGitPort
import com.aicodemax.tools.git.gitDescriptorToday
import com.aicodemax.tools.git_runtime.GitToolExecutor
import com.aicodemax.tools.memory_runtime.MemoryToolExecutor
import com.aicodemax.tools.skill_runtime.SkillToolExecutor
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.media.CloudGenRegistry
import com.aicodemax.tools.media.FileMediaProject
import com.aicodemax.tools.media.MediaProjectPort
import com.aicodemax.tools.media.mediaDescriptorToday
import com.aicodemax.tools.media_runtime.MediaToolExecutor
import com.aicodemax.tools.subtitle.SubtitlePort
import com.aicodemax.tools.subtitle.subtitleDescriptorToday
import com.aicodemax.tools.subtitle_runtime.SubtitleToolExecutor
import com.aicodemax.tools.video.VideoPort
import com.aicodemax.tools.render.RenderPort
import com.aicodemax.tools.render.renderDescriptorToday
import com.aicodemax.tools.render_runtime.RenderToolExecutor
import com.aicodemax.tools.video.videoDescriptorToday
import com.aicodemax.tools.video_runtime.VideoToolExecutor
import com.aicodemax.tools.audio.AudioPort
import com.aicodemax.tools.audio.audioDescriptorToday
import com.aicodemax.tools.audio_runtime.AudioToolExecutor
import com.aicodemax.tools.image.ImagePort
import com.aicodemax.tools.image.imageDescriptorToday
import com.aicodemax.tools.image_runtime.ImageToolExecutor
import com.aicodemax.tools.voice.VoicePort
import com.aicodemax.tools.voice.voiceDescriptorToday
import com.aicodemax.tools.voice_runtime.VoiceToolExecutor
import com.aicodemax.tools.project.ProjectManager
import com.aicodemax.tools.registry.InMemoryToolRegistry
import com.aicodemax.tools.registry.ToolRegistry
import com.aicodemax.tools.terminal.terminalDescriptorToday
import com.aicodemax.tools.terminal_runtime.CliToolAdapter
import com.aicodemax.tools.terminal_runtime.CompatEngine
import com.aicodemax.tools.terminal_runtime.SystemShellPort
import com.aicodemax.tools.terminal_runtime.TerminalToolExecutor
import com.aicodemax.tools.terminal_runtime.UnwiredTerminalPort
import java.io.File

/** Manual DI root (Phase 1). A Hilt migration later must not change any interface. */
class ServiceLocator(context: Context) {
    private val appContext = context.applicationContext
    private var autonomyLevel = AutonomyLevel.ASK_ALWAYS

    val bus: EventBus = SharedFlowEventBus()
    val appState: AppStateStore = InMemoryAppStateStore()
    val workingSet: WorkingSetStore = InMemoryWorkingSetStore()

    val audit: AuditLog = FileAuditLog(File(appContext.filesDir, "audit"))
    val checkpoints: CheckpointStore = FileCheckpointStore(File(appContext.filesDir, "state"))
    val conversations: ConversationStore = FileConversationStore(File(appContext.filesDir, "state"))
    val memory: MemoryStore = FileMemoryStore(File(appContext.filesDir, "state"))
    val skills: FileSkillStore = FileSkillStore(File(appContext.filesDir, "skills"))
    val voice: VoicePort = AndroidVoicePort(appContext)
    val images: ImagePort = AndroidImagePort()
    private val androidAudio = AndroidAudioPort()
    val audio: AudioPort = androidAudio
    val video: VideoPort = AndroidVideoPort()
    val subtitles: SubtitlePort = AndroidSubtitlePort(audio, video)
    val media: MediaProjectPort =
        FileMediaProject(File(appContext.filesDir, "media"), images, audio, video)
    val render: RenderPort = AndroidRenderPort(
        media, File(appContext.filesDir, "media"), androidAudio::decodeToPcm, video, appContext,
    )
    val settings: SettingsRepository = DataStoreSettingsRepository(appContext)

    val resources: ResourceMonitor = AndroidResourceMonitor(appContext)

    val toolRegistry: ToolRegistry = InMemoryToolRegistry()
    val permissionGrants: PermissionManager = InMemoryPermissionManager()
    val approvals: ApprovalCenter = ApprovalCenter(permissionGrants)
    val gateway: ToolGateway
    val capabilities: CapabilityResolver

    val workspaceDir: File = File(appContext.filesDir, "workspace")
    val files: FilePort = SandboxFileStore(workspaceDir)
    val editor: EditorPort = FileBackedEditor(files)
    val git: GitPort = JGitGitPort()
    val browser: InMemoryBrowserPort = InMemoryBrowserPort()
    /** Live WebView registered by BrowserScreen (null when the screen is closed). */
    var activeWebView: WebView? = null
    /** CP-115: tab state + page automation on the visible WebView. */
    val browserPage: BrowserPort = AndroidBrowserPort(browser, view = { activeWebView })

    val tasks: TaskEngine = DefaultTaskEngine(bus)
    val models: ModelRegistry = InMemoryModelRegistry()

    /** CP-59 LLM brain config — memory-only (keys never persisted). */
    @Volatile var llmProvider: LlmProvider? = null
    @Volatile var llmModel: String = "gpt-4o-mini"

    /** CP-107: connected endpoints by registry model id (memory-only). */
    private val connectedProviders = mutableMapOf<String, LlmProvider>()

    /**
     * CP-107: connecting also registers a router-visible model (local-first
     * kind comes from the endpoint address; disconnect marks it UNKNOWN).
     */
    fun setLlm(provider: LlmProvider?, model: String, baseUrl: String = "") {
        llmProvider = provider
        llmModel = model.ifBlank { "default" }
        val id = "manual"
        if (provider == null) {
            connectedProviders.remove(id)
            models.get(id)?.let { models.update(it.copy(status = ModelStatus.UNKNOWN)) }
            return
        }
        connectedProviders[id] = provider
        val host = baseUrl.lowercase()
        val local = host.contains("localhost") || host.contains("127.0.0.1") ||
            host.contains("192.168.") || host.contains(".local") ||
            Regex("""://10\.""").containsMatchIn(host) ||
            Regex("""://172\.(1[6-9]|2[0-9]|3[01])\.""").containsMatchIn(host)
        val descriptor = ModelDescriptor(
            id = id,
            name = llmModel,
            kind = if (local) ModelKind.LOCAL_FULL else ModelKind.EXTERNAL,
            provider = provider.id,
            status = ModelStatus.READY,
            capabilities = listOf("chat"),
        )
        if (models.get(id) == null) models.register(descriptor) else models.update(descriptor)
    }

    /** CP-107: current brain route for the Models screen. */
    fun brainRoute(): String = routedBrain.routeLine()

    /** CP-108: cloud generation slots (§29) + status for the Models screen. */
    val cloudGen: CloudGenRegistry = CloudGenRegistry()

    fun cloudStatus(): String = cloudGen.statusLine()

    /** CP-108: subtitle LLM engine — uses the connected provider or fails honestly. */
    val subtitleLlm: suspend (String) -> Outcome<String> = { prompt ->
        val provider = llmProvider
        if (provider == null) {
            Outcome.Failure(AppError("BRAIN_OFF", "ยังไม่ต่อ LLM (ตั้งค่าที่หน้า Models)"))
        } else {
            when (val reply = provider.chat(llmModel, listOf(LlmMessage("user", prompt)))) {
                is Outcome.Success -> Outcome.Success(reply.value.content)
                is Outcome.Failure -> reply
            }
        }
    }

    private fun llmSystemPrompt(): String {
        val tools = BuiltinSkills.all.firstOrNull { it.meta.id == "aicode-tools" }?.content.orEmpty()
        return "You are Aicodemax, a Thai-speaking AI that DOES work with tools. " +
            "Reply in Thai unless the user writes English.\n\n" + tools + "\n" +
            "Tool-call format: emit lines `ACTION toolId.action {\"arg\":\"value\"}` " +
            "(one JSON object per line), then STOP and wait for TOOL_RESULT. " +
            "Example: ACTION files.read {\"path\":\"notes.txt\"}. " +
            "Never invent tool results. If no tool fits, answer directly."
    }
    val router: ModelRouter = FallbackModelRouter(models)
    val installer: ModelInstaller =
        ModelInstaller(models, JavaNetModelDownloader(), File(appContext.filesDir, "models"))

    // CP-113: one shared real shell backend — console UI (compat) + AI gateway use the same port.
    val shell: SystemShellPort = SystemShellPort()
    // CP-114: learning loop over real task outcomes (persisted in memory store).
    val learn: LearningEngine = LearningEngine(memory)
    val agent: LocalAgentRunner
    val compat: CompatEngine = CompatEngine(CliToolAdapter(shell))
    val projects: ProjectManager = ProjectManager(File(appContext.filesDir, "projects"))
    val builds: PipelineBuildEngine = PipelineBuildEngine(emptyList())
    val artifacts: ArtifactStore = ArtifactStore(File(appContext.filesDir, "artifacts"))
    val library: BrowserLibrary = BrowserLibrary(File(appContext.filesDir, "browser"))

    private lateinit var routedBrain: RoutedChatBrain

    val orchestrator: Orchestrator

    init {
        // Honest capability snapshots for every tool (100% contract).
        toolRegistry.register(filesDescriptorToday())
        toolRegistry.register(editorDescriptorToday())
        toolRegistry.register(terminalDescriptorToday())
        toolRegistry.register(browserDescriptorToday())
        toolRegistry.register(buildDescriptorToday())
        toolRegistry.register(gitDescriptorToday())
        toolRegistry.register(debugDescriptorToday())
        toolRegistry.register(memoryDescriptorToday())
        toolRegistry.register(skillDescriptorToday())
        toolRegistry.register(voiceDescriptorToday())
        toolRegistry.register(imageDescriptorToday())
        toolRegistry.register(audioDescriptorToday())
        toolRegistry.register(videoDescriptorToday())
        toolRegistry.register(subtitleDescriptorToday())
        toolRegistry.register(mediaDescriptorToday())
        toolRegistry.register(renderDescriptorToday())

        gateway = DefaultToolGateway(
            toolRegistry,
            AutonomyPermissionGate({ autonomyLevel }, permissionGrants, { call ->
                approvals.requestApproval(call)
            }),
            audit,
            bus,
        )
        gateway.registerExecutor(FilesToolExecutor(files))
        gateway.registerExecutor(EditorToolExecutor(editor))
        gateway.registerExecutor(TerminalToolExecutor(shell))
        gateway.registerExecutor(GitToolExecutor(git, workspaceDir.path))
        gateway.registerExecutor(BrowserToolExecutor(browserPage))
        // CP-118: native toolchain detection (ffmpeg/ffprobe/llama-server in nativeLibraryDir).
        val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
        gateway.registerExecutor(
            DebugToolExecutor(
                nativeLibDir = nativeLibDir,
                nativeRunner = { exe, args ->
                    com.aicodemax.tools.runtime.ProcessRunner.run(exe, args, timeoutMs = 15_000).fold(
                        onSuccess = { (it.stdout.ifBlank { it.stderr }).lineSequence().firstOrNull().orEmpty() },
                        onFailure = { "" },
                    )
                },
            ),
        )
        gateway.registerExecutor(MemoryToolExecutor(MemoryEngine(memory)))
        gateway.registerExecutor(SkillToolExecutor(skills, files))
        gateway.registerExecutor(VoiceToolExecutor(voice))
        gateway.registerExecutor(ImageToolExecutor(images))
        gateway.registerExecutor(AudioToolExecutor(audio))
        gateway.registerExecutor(VideoToolExecutor(video))
        gateway.registerExecutor(SubtitleToolExecutor(subtitles, subtitleLlm))
        gateway.registerExecutor(MediaToolExecutor(media, AndroidTrackingPort(), AndroidColorPort(), AndroidGenPort(appContext.filesDir, voice, cloudGen), androidAudio))
        gateway.registerExecutor(RenderToolExecutor(render, media))

        capabilities = StandardCapabilities.overRegistry(toolRegistry) { toolId, action ->
            learn.preference("$toolId.$action")
        }

        agent = LocalAgentRunner(gateway)
        val manualBrain = LlmBrain({ llmProvider }, { llmModel }, gateway, llmSystemPrompt())
        routedBrain = RoutedChatBrain(
            router,
            resolve = { descriptor ->
                connectedProviders[descriptor.id]?.let { provider ->
                    LlmBrain({ provider }, { descriptor.name }, gateway, llmSystemPrompt())
                }
            },
            manual = { if (llmProvider == null) null else manualBrain },
        )
        orchestrator = BootstrapOrchestrator(
            tasks, RuleBasedPlanner(capabilities, EditingPlanner(media, capabilities)), agent, RuleVerifier(), checkpoints, conversations,
            recovery = RecoveryLadderPolicy(),
            questionnaires = InMemoryQuestionnaireStore(),
            brain = routedBrain,
            learner = learn,
        )
    }

    fun refreshAutonomy(level: AutonomyLevel) {
        autonomyLevel = level
    }
}
