package com.aicodemax.data.settings

import com.aicodemax.core.state.AutonomyLevel
import com.aicodemax.core.state.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun inMemoryRoundtrip() = runBlocking {
        val repo: SettingsRepository = InMemorySettingsRepository()
        assertEquals(ThemeMode.SYSTEM, repo.theme.first())
        assertEquals(AutonomyLevel.ASK_ALWAYS, repo.autonomy.first())
        assertEquals(null, repo.activeModelId.first())

        repo.setTheme(ThemeMode.DARK)
        repo.setAutonomy(AutonomyLevel.AUTO_SAFE)
        repo.setActiveModel("model-1")
        assertEquals(ThemeMode.DARK, repo.theme.first())
        assertEquals(AutonomyLevel.AUTO_SAFE, repo.autonomy.first())
        assertEquals("model-1", repo.activeModelId.first())
    }
}
