package com.aicodemax.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ApprovalRequest
import com.aicodemax.tools.gateway.PermissionDecision
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.ui.chat.ChatModelOption
import com.aicodemax.ui.chat.ChatRoute
import com.aicodemax.ui.chat.ChatViewModel
import com.aicodemax.ui.designsystem.AicodeSize
import com.aicodemax.ui.designsystem.AicodeTopBar
import com.aicodemax.ui.settings.AboutScreen
import com.aicodemax.ui.settings.SettingsRoute
import com.aicodemax.ui.workspace.ToolsScreen
import kotlinx.coroutines.launch

object Routes {
    const val CHAT = "chat"
    const val SEARCH = "search"
    const val PROJECTS = "projects"
    const val TASKS = "tasks"
    const val MODELS = "models"
    const val TOOLS = "tools"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val AUDIT = "audit"
    const val BROWSER = "browser"
    const val TERMINAL = "terminal"
    const val AGENTS = "agents"
    const val GIT = "git"
    const val BUILD = "build"
    const val SKILLS = "skills"
    const val RENDER = "render"
    const val TIMELINE = "timeline"
    const val TEMPLATES = "templates"
    const val GEN = "gen"
    const val RECORD = "record"
    const val SUBTITLE = "subtitle"
    const val MEMORY = "memory"
    const val IMAGE = "image"
    const val AUDIO = "audio"
}

private fun NavHostController.navigateSingle(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(Routes.CHAT) { saveState = true }
    }
}

private fun titleFor(route: String): String = when (route) {
    Routes.CHAT -> "AI Chat"
    Routes.SEARCH -> "ค้นหา"
    Routes.PROJECTS -> "โปรเจกต์"
    Routes.TASKS -> "งาน"
    Routes.MODELS -> "AI"
    Routes.TOOLS -> "เครื่องมือ"
    Routes.SETTINGS -> "ตั้งค่า"
    Routes.ABOUT -> "เกี่ยวกับ"
    Routes.AUDIT -> "ตรวจสอบ"
    Routes.BROWSER -> "เบราว์เซอร์"
    Routes.TERMINAL -> "เทอร์มินัล"
    Routes.AGENTS -> "เอเจนต์"
    Routes.GIT -> "Git"
    Routes.BUILD -> "Build & Test"
    Routes.SKILLS -> "Skills"
    Routes.RENDER -> "เรนเดอร์"
    Routes.TIMELINE -> "วิดีโอ"
    Routes.TEMPLATES -> "เทมเพลต"
    Routes.GEN -> "สร้างมีเดีย"
    Routes.RECORD -> "อัดเสียง/ถ่าย"
    Routes.SUBTITLE -> "ซับไตเติล"
    Routes.MEMORY -> "ความจำ"
    Routes.IMAGE -> "แต่งรูป"
    Routes.AUDIO -> "ตัดเสียง"
    else -> "Aicodemax"
}

@Composable
fun AicodeNav(services: ServiceLocator, chatViewModel: ChatViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.CHAT
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun openDrawer() {
        scope.launch { drawerState.open() }
    }
    fun go(route: String) {
        scope.launch { drawerState.close() }
        nav.navigateSingle(route)
    }

    // Keep gateway autonomy in sync with persisted settings.
    val autonomy by services.settings.autonomy.collectAsState(initial = null)
    LaunchedEffect(autonomy) { autonomy?.let { services.refreshAutonomy(it) } }

    // Shell drawer reads chat history straight from the ChatViewModel.
    val conversations by chatViewModel.conversationsList.collectAsState()
    val chatState by chatViewModel.state.collectAsState()
    LaunchedEffect(Unit) { chatViewModel.refreshConversations() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ShellDrawerContent(
                    conversations = conversations,
                    activeConversationId = chatState.conversationId,
                    onNewChat = {
                        chatViewModel.newChat()
                        go(Routes.CHAT)
                    },
                    onSelectConversation = { id ->
                        chatViewModel.openConversation(id)
                        go(Routes.CHAT)
                    },
                    onDeleteConversation = chatViewModel::deleteConversation,
                    onRenameConversation = chatViewModel::renameConversation,
                    onOpen = ::go,
                    onOpenChat = { go(Routes.CHAT) },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                AicodeTopBar(
                    title = titleFor(route),
                    onMenu = ::openDrawer,
                    onSearch = if (route == Routes.SEARCH) null else ({ nav.navigateSingle(Routes.SEARCH) }),
                )
            },
        ) { padding ->
            ApprovalOverlay(services)
            NavHost(navController = nav, startDestination = Routes.CHAT, modifier = Modifier.padding(padding)) {
                composable(Routes.CHAT) {
                    ChatRoute(
                        chatViewModel,
                        onOpenTasks = { nav.navigateSingle(Routes.TASKS) },
                        workingSet = services.workingSet,
                        models = chatModelOptions(services),
                        onOpenModels = { nav.navigateSingle(Routes.MODELS) },
                    )
                }
                composable(Routes.SEARCH) {
                    SearchScreen(
                        services = services,
                        onOpen = { nav.navigateSingle(it) },
                        onOpenConversation = { id ->
                            chatViewModel.openConversation(id)
                            nav.navigateSingle(Routes.CHAT)
                        },
                    )
                }
                composable(Routes.PROJECTS) {
                    ProjectsScreen(
                        services,
                        onOpen = { nav.navigateSingle(it) },
                        onHandToChat = { prompt ->
                            services.workingSet.handPrompt(prompt)
                            nav.navigateSingle(Routes.CHAT)
                        },
                    )
                }
                composable(Routes.TASKS) { TasksScreen(services) }
                composable(Routes.MODELS) { ModelsScreen(services) }
                composable(Routes.TOOLS) {
                    ToolsScreen(
                        tools = services.toolRegistry.all(),
                        onOpenTool = { toolId ->
                            if (toolId == "files" || toolId == "editor") nav.navigateSingle(Routes.PROJECTS)
                            if (toolId == "browser") nav.navigateSingle(Routes.BROWSER)
                            if (toolId == "git") nav.navigateSingle(Routes.GIT)
                            if (toolId == "terminal") nav.navigateSingle(Routes.TERMINAL)
                            if (toolId == "build") nav.navigateSingle(Routes.BUILD)
                            if (toolId == "skill") nav.navigateSingle(Routes.SKILLS)
                            if (toolId == "render") nav.navigateSingle(Routes.RENDER)
                            if (toolId == "media") nav.navigateSingle(Routes.TIMELINE)
                            if (toolId == "video") nav.navigateSingle(Routes.TIMELINE)
                            if (toolId == "subtitle") nav.navigateSingle(Routes.SUBTITLE)
                            if (toolId == "audio") nav.navigateSingle(Routes.AUDIO)
                            if (toolId == "image") nav.navigateSingle(Routes.IMAGE)
                            if (toolId == "memory") nav.navigateSingle(Routes.MEMORY)
                            if (toolId == "debug") nav.navigateSingle(Routes.CHAT)
                        },
                        onSelfTest = { toolId -> selfTest(services, toolId) },
                    )
                }
                composable(Routes.SETTINGS) {
                    val grants = rememberGrantSnapshot(services)
                    SettingsRoute(
                        services.settings,
                        onOpenAbout = { nav.navigateSingle(Routes.ABOUT) },
                        permissionLine = grants.first,
                        denies = grants.second,
                        onRevokeAll = { services.permissionGrants.revokeAll() },
                        onOpenAudit = { nav.navigateSingle(Routes.AUDIT) },
                    )
                }
                composable(Routes.ABOUT) { AboutScreen() }
                composable(Routes.AUDIT) { AuditScreen(services) }
                composable(Routes.BROWSER) {
                    BrowserScreen(
                        services,
                        onHandToChat = { prompt ->
                            services.workingSet.handPrompt(prompt)
                            nav.navigateSingle(Routes.CHAT)
                        },
                    )
                }
                composable(Routes.TERMINAL) {
                    TerminalScreen(
                        services,
                        onHandToChat = { prompt ->
                            services.workingSet.handPrompt(prompt)
                            nav.navigateSingle(Routes.CHAT)
                        },
                    )
                }
                composable(Routes.AGENTS) { AgentsScreen(services) }
                composable(Routes.GIT) {
                    GitScreen(
                        services,
                        onHandToChat = { prompt ->
                            services.workingSet.handPrompt(prompt)
                            nav.navigateSingle(Routes.CHAT)
                        },
                    )
                }
                composable(Routes.BUILD) { BuildScreen(services) }
                composable(Routes.SKILLS) { SkillsScreen(services) }
                composable(Routes.RENDER) { RenderScreen(services) }
                composable(Routes.TIMELINE) {
                    VideoWorkspace(
                        services,
                        onOpen = { nav.navigateSingle(it) },
                        onHandToChat = { prompt ->
                            services.workingSet.handPrompt(prompt)
                            nav.navigateSingle(Routes.CHAT)
                        },
                    )
                }
                composable(Routes.TEMPLATES) { TemplatesScreen(services) }
                composable(Routes.GEN) { GenScreen(services) }
                composable(Routes.RECORD) { RecordScreen(services) }
                composable(Routes.SUBTITLE) { SubtitleScreen(services) }
                composable(Routes.MEMORY) { MemoryScreen(services) }
                composable(Routes.IMAGE) { ImageEditorScreen(services) }
                composable(Routes.AUDIO) { AudioEditorScreen(services) }
            }
        }
    }
}

/** CP-135: registry models → chat selector options; router pick = active. */
private fun chatModelOptions(services: ServiceLocator): List<ChatModelOption> {
    val activeId = services.router.pick().fold(
        onSuccess = { it.id },
        onFailure = { null },
    )
    return services.models.all().map {
        ChatModelOption(
            id = it.id,
            name = it.name,
            statusLabel = "${it.provider} • ${it.status.name}",
            active = it.id == activeId,
        )
    }
}

/** Harmless live probes — a tool passes when its executor answers through the gateway. */
private suspend fun selfTest(services: ServiceLocator, toolId: String): String {
    val call = when (toolId) {
        "files" -> ToolCall(Ids.newId("selftest"), "files", "list", emptyMap(), actor = "USER")
        "editor" -> ToolCall(Ids.newId("selftest"), "editor", "preview", emptyMap(), actor = "USER")
        "git" -> ToolCall(
            Ids.newId("selftest"), "git", "status",
            mapOf("repo" to services.workspaceDir.path), actor = "USER",
        )
        "browser" -> ToolCall(Ids.newId("selftest"), "browser", "list", emptyMap(), actor = "USER")
        "debug" -> ToolCall(
            Ids.newId("selftest"), "debug", "analyze",
            mapOf("error" to "java.lang.Exception: selftest"), actor = "USER",
        )
        "memory" -> ToolCall(Ids.newId("selftest"), "memory", "recall", mapOf("key" to "__selftest__"), actor = "USER")
        "skill" -> ToolCall(Ids.newId("selftest"), "skill", "list", emptyMap(), actor = "USER")
        else -> return "ยังไม่มี self-test (ไม่มี executor ให้ทดสอบ)"
    }
    return services.gateway.call(call).fold(
        onSuccess = {
            if (it.ok) "✓ ตอบสนอง: ${it.output.take(120)}"
            else "ตอบสนอง (คาดได้): ${it.error.take(120)}"
        },
        onFailure = { "✕ ${it.message}" },
    )
}

@Composable
private fun rememberGrantSnapshot(services: ServiceLocator): Pair<String, List<String>> {
    val snapshot = services.permissionGrants.snapshot()
    val line = "ให้แล้ว: งาน ${snapshot.taskGrants} • ครั้งเดียว ${snapshot.oneShots} • ปฏิเสธถาวร ${snapshot.denies.size}"
    return line to snapshot.denies
}

/** CP-05: global WHAT/WHY/SCOPE/RISK approval dialog (one at a time, FIFO). */
@Composable
private fun ApprovalOverlay(services: ServiceLocator) {
    val pending by services.approvals.pending.collectAsState()
    val current = pending.firstOrNull() ?: return
    ApprovalDialog(
        request = current,
        onDecide = { services.approvals.decide(current.id, it) },
    )
}

@Composable
private fun ApprovalDialog(request: ApprovalRequest, onDecide: (PermissionDecision) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("AI ขอสิทธิ์") },
        text = {
            Column {
                ApprovalRow("WHAT", request.what)
                ApprovalRow("WHY", request.why)
                ApprovalRow("SCOPE", request.scope)
                ApprovalRow("RISK", "${request.risk.level.name} — ${request.risk.reason}")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDecide(PermissionDecision.ALLOW_ONCE) },
                modifier = Modifier
                    .heightIn(min = AicodeSize.MinTouch)
                    .semantics { contentDescription = "อนุญาตครั้งเดียว" },
            ) { Text("ครั้งเดียว") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { onDecide(PermissionDecision.DENY) },
                    modifier = Modifier
                        .heightIn(min = AicodeSize.MinTouch)
                        .semantics { contentDescription = "ปฏิเสธ" },
                ) { Text("ปฏิเสธ") }
                TextButton(
                    onClick = { onDecide(PermissionDecision.ALLOW_FOR_TASK) },
                    modifier = Modifier
                        .heightIn(min = AicodeSize.MinTouch)
                        .semantics { contentDescription = "อนุญาตทั้งงาน" },
                ) { Text("ทั้งงาน") }
            }
        },
    )
}

@Composable
private fun ApprovalRow(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
