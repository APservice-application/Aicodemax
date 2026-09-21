package com.aicodemax.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicodemax.core.state.AutonomyLevel
import com.aicodemax.core.state.ThemeMode
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val AUTONOMY = stringPreferencesKey("autonomy")
        val MODEL = stringPreferencesKey("active_model")
    }

    override val theme: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    override val autonomy: Flow<AutonomyLevel> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.AUTONOMY]?.let { runCatching { AutonomyLevel.valueOf(it) }.getOrNull() }
            ?: AutonomyLevel.ASK_ALWAYS
    }

    override val activeModelId: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.MODEL]
    }

    override suspend fun setTheme(theme: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME] = theme.name }
    }

    override suspend fun setAutonomy(level: AutonomyLevel) {
        context.settingsDataStore.edit { it[Keys.AUTONOMY] = level.name }
    }

    override suspend fun setActiveModel(modelId: String?) {
        context.settingsDataStore.edit {
            if (modelId == null) it.remove(Keys.MODEL) else it[Keys.MODEL] = modelId
        }
    }
}
