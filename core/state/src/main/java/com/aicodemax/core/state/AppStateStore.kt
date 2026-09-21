package com.aicodemax.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode { LIGHT, DARK, SYSTEM }
enum class AutonomyLevel { ASK_ALWAYS, AUTO_SAFE, AUTO_ALL }

data class AppState(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val autonomy: AutonomyLevel = AutonomyLevel.ASK_ALWAYS,
    val activeModelId: String? = null,
    val activeConversationId: String? = null,
)

interface AppStateStore {
    val state: StateFlow<AppState>
    fun update(transform: (AppState) -> AppState)
}

class InMemoryAppStateStore(initial: AppState = AppState()) : AppStateStore {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<AppState> = _state.asStateFlow()
    override fun update(transform: (AppState) -> AppState) {
        _state.update(transform)
    }
}
