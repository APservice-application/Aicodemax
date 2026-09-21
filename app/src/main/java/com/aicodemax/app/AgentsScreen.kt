package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aicodemax.ui.designsystem.LocalSpacing

/** Agents Center (CP-40): every agent the app can run, with its capability allowlist. */
@Composable
fun AgentsScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val agents = rememberAgents(services)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (agents.isEmpty()) {
            Text("ยังไม่มี agent")
        }
        for (agent in agents) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
                    Text(agent.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "id: ${agent.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    if (agent.capabilities.isEmpty()) {
                        Text(
                            "ยังไม่มีสิทธิ์ใช้เครื่องมือ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            "สิทธิ์: ${agent.capabilities.joinToString(", ")}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        agent.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
        Text(
            "specialist agents (coder/tester/reviewer) จะตามมา — ทุกตัวต้องมี allowlist ก่อนรัน",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

private data class AgentCard(val id: String, val name: String, val capabilities: List<String>, val status: String)

@Composable
private fun rememberAgents(services: ServiceLocator): List<AgentCard> {
    val descriptor = services.agent.descriptor
    val runnable = services.toolRegistry.all().count { it.isRunnable() }
    return listOf(
        AgentCard(
            id = descriptor.id,
            name = descriptor.name,
            capabilities = descriptor.capabilities,
            status = "พร้อมใช้ • เครื่องมือพร้อม $runnable ตัว • ผ่าน ToolGateway (permission+audit)",
        ),
    )
}
