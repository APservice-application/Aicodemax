package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicodemax.core.common.fold
import com.aicodemax.tools.builder.Artifact
import com.aicodemax.tools.builder.BuildRequest
import com.aicodemax.tools.tester.AggregatingTestEngine
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Build & Test Center (CP-44): pipeline runs, test suites, and build artifacts. */
@Composable
fun BuildScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var buildLog by remember { mutableStateOf("(ยังไม่เคยรัน)") }
    var testLine by remember { mutableStateOf("(ยังไม่เคยรัน)") }
    var artifacts by remember { mutableStateOf<List<Artifact>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var benchLine by remember { mutableStateOf("(ยังไม่เคยวัด)") }
    var benchBusy by remember { mutableStateOf(false) }

    fun activeProjectId(): String? =
        services.projects.getActive().fold(
            onSuccess = { it.id },
            onFailure = { null },
        )

    fun loadArtifacts() {
        val id = activeProjectId()
        artifacts = if (id == null) emptyList() else services.artifacts.list(id)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("Build pipeline", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { services.builds.build(BuildRequest(services.workspaceDir.path)) }
                        .fold(
                            onSuccess = { buildLog = it.output.ifBlank { "(no output)" }; error = null },
                            onFailure = { error = it.message },
                        )
                    loadArtifacts()
                }
            }) { Text("รัน build") }
            TextButton(onClick = {
                scope.launch {
                    val engine = AggregatingTestEngine(emptyList())
                    engine.runAll().fold(
                        onSuccess = {
                            testLine = if (it.isEmpty()) "(ยังไม่มี test suite ที่ลงทะเบียน)" else engine.summarize(it)
                        },
                        onFailure = { error = it.message },
                    )
                }
            }) { Text("รัน test") }
            TextButton(onClick = {
                scope.launch {
                    benchBusy = true
                    val call = com.aicodemax.tools.gateway.ToolCall(
                        com.aicodemax.core.common.Ids.newId("ui"), "debug", "bench",
                        mapOf("quick" to "true"), actor = "HUMAN",
                    )
                    services.gateway.call(call).fold(
                        onSuccess = { benchLine = if (it.ok) it.output else it.error },
                        onFailure = { benchLine = it.message },
                    )
                    benchBusy = false
                }
            }, enabled = !benchBusy) { Text("วัดความเร็ว") }
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        Text("ผล build ล่าสุด:", style = MaterialTheme.typography.labelSmall)
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                buildLog.take(1500),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(spacing.sm),
            )
        }
        Text("ผล test: $testLine", style = MaterialTheme.typography.bodyMedium)
        Text("เบนช์มาร์ก: $benchLine", style = MaterialTheme.typography.bodyMedium)
        Text("Artifacts (โปรเจกต์ที่เลือก):", style = MaterialTheme.typography.labelSmall)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (artifacts.isEmpty()) {
                item(key = "__empty__") { Text("(ยังไม่มี artifact — build ที่สำเร็จจะมาอยู่ตรงนี้)") }
            }
            items(artifacts, key = { it.id }) { artifact ->
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(modifier = Modifier.fillMaxWidth().padding(spacing.sm)) {
                        Text(artifact.fileName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${artifact.kind.name} • ${artifact.sizeBytes} bytes • ${artifact.sha256.take(12)}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }
}
