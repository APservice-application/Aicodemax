package com.aicodemax.data.settings

import com.aicodemax.core.state.AutonomyLevel
import com.aicodemax.core.state.ThemeMode
import com.aicodemax.core.state.UiMode
import kotlinx.coroutines.flow.Flow

/** Pure settings contract (DataStore impl + in-memory impl share it). */
interface SettingsRepository {
    val theme: Flow<ThemeMode>
    val autonomy: Flow<AutonomyLevel>
    val activeModelId: Flow<String?>
    val uiMode: Flow<UiMode>
    suspend fun setTheme(theme: ThemeMode)
    suspend fun setAutonomy(level: AutonomyLevel)
    suspend fun setActiveModel(modelId: String?)
    suspend fun setUiMode(mode: UiMode)
}
