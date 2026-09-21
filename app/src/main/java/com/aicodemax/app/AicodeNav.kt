package com.aicodemax.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aicodemax.ui.chat.ChatRoute
import com.aicodemax.ui.chat.ChatViewModel
import com.aicodemax.ui.settings.AboutScreen
import com.aicodemax.ui.settings.SettingsRoute
import com.aicodemax.ui.workspace.ToolsScreen

object Routes {
    const val HOME = "home"
    const val CHAT = "chat"
    const val PROJECTS = "projects"
    const val TASKS = "tasks"
    const val MODELS = "models"
    const val TOOLS = "tools"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

private fun NavHostController.navigateSingle(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(Routes.HOME) { saveState = true }
    }
}

private fun titleFor(route: String): String = when (route) {
    Routes.HOME -> "Aicodemax"
    Routes.CHAT -> "แชท"
    Routes.PROJECTS -> "โปรเจกต์"
    Routes.TASKS -> "งาน"
    Routes.MODELS -> "AI"
    Routes.TOOLS -> "เครื่องมือ"
    Routes.SETTINGS -> "ตั้งค่า"
    Routes.ABOUT -> "เกี่ยวกับ"
    else -> "Aicodemax"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AicodeNav(services: ServiceLocator, chatViewModel: ChatViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.HOME

    // Keep gateway autonomy in sync with persisted settings.
    val autonomy by services.settings.autonomy.collectAsState(initial = null)
    LaunchedEffect(autonomy) { autonomy?.let { services.refreshAutonomy(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(route)) },
                actions = {
                    TextButton(onClick = { nav.navigateSingle(Routes.TOOLS) }) { Text("Tools") }
                    TextButton(onClick = { nav.navigateSingle(Routes.SETTINGS) }) { Text("ตั้งค่า") }
                },
            )
        },
        bottomBar = { AicodeBottomBar(route) { nav.navigateSingle(it) } },
    ) { padding ->
        NavHost(navController = nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    services = services,
                    onOpen = { nav.navigateSingle(it) },
                    onNewChat = { chatViewModel.newChat(); nav.navigateSingle(Routes.CHAT) },
                )
            }
            composable(Routes.CHAT) { ChatRoute(chatViewModel) }
            composable(Routes.PROJECTS) { ProjectsScreen(services) }
            composable(Routes.TASKS) { TasksScreen(services) }
            composable(Routes.MODELS) { ModelsScreen(services) }
            composable(Routes.TOOLS) {
                ToolsScreen(
                    tools = services.toolRegistry.all(),
                    onOpenTool = { toolId ->
                        if (toolId == "files" || toolId == "editor") nav.navigateSingle(Routes.PROJECTS)
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsRoute(services.settings, onOpenAbout = { nav.navigateSingle(Routes.ABOUT) })
            }
            composable(Routes.ABOUT) { AboutScreen() }
        }
    }
}

@Composable
private fun AicodeBottomBar(route: String, onSelect: (String) -> Unit) {
    val tabs = listOf(
        Triple(Routes.HOME, "Home", "🏠"),
        Triple(Routes.CHAT, "Chat", "💬"),
        Triple(Routes.PROJECTS, "Projects", "📁"),
        Triple(Routes.TASKS, "Tasks", "✅"),
        Triple(Routes.MODELS, "AI", "🤖"),
    )
    NavigationBar {
        for ((id, label, glyph) in tabs) {
            NavigationBarItem(
                selected = route == id,
                onClick = { onSelect(id) },
                icon = { Text(glyph) },
                label = { Text(label) },
            )
        }
    }
}
