package com.aicodemax.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aicodemax.core.state.ThemeMode
import com.aicodemax.ui.chat.ChatViewModel
import com.aicodemax.ui.designsystem.AicodemaxTheme

class MainActivity : ComponentActivity() {
    private val services: ServiceLocator
        get() = (application as AicodeApp).services

    private val chatViewModel: ChatViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(services.orchestrator, services.conversations, services.tasks, services.workingSet) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme by services.settings.theme.collectAsState(initial = ThemeMode.SYSTEM)
            val dark = when (theme) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            AicodemaxTheme(darkTheme = dark) {
                AicodeNav(services = services, chatViewModel = chatViewModel)
            }
        }
    }
}
