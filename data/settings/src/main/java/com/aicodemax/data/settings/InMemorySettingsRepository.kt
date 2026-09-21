package com.aicodemax.data.settings

import com.aicodemax.core.state.AutonomyLevel
import com.aicodemax.core.state.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory settings (CP-46 Settings UI data backfill; MASTER §30). Production uses DataStore. */
class InMemorySettingsRepository(
    theme: ThemeMode = ThemeMode.SYSTEM,
    autonomy: AutonomyLevel = AutonomyLevel.ASK_ALWAYS,
    activeModelId: String? = null,
) : SettingsRepository {
    private val themeState = MutableStateFlow(theme)
    private val autonomyState = MutableStateFlow(autonomy)
    private val modelState = MutableStateFlow(activeModelId)

    override val theme: Flow<ThemeMode> = themeState
    override val autonomy: Flow<AutonomyLevel> = autonomyState
    override val activeModelId: Flow<String?> = modelState

    override suspend fun setTheme(theme: ThemeMode) {
        themeState.value = theme
    }

    override suspend fun setAutonomy(level: AutonomyLevel) {
        autonomyState.value = level
    }

    override suspend fun setActiveModel(modelId: String?) {
        modelState.value = modelId
    }
}
