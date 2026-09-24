package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.ui.designsystem.AicodeSearchField
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.OutputBlock
import com.aicodemax.ui.designsystem.SectionHeader
import com.aicodemax.ui.designsystem.ShimmerSkeleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SearchHit(val group: String, val title: String, val detail: String, val action: (() -> Unit)?)

/**
 * CP-135 global search (§61): one query across conversations, files, projects
 * and skills. Replaces the old dashboard as the find-anything entry point.
 */
@Composable
fun SearchScreen(
    services: ServiceLocator,
    onOpen: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var rawOutputs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty() || busy) return
        scope.launch {
            busy = true
            searched = true
            val found = mutableListOf<SearchHit>()
            val raws = mutableListOf<Pair<String, String>>()
            withContext(Dispatchers.IO) {
                // Conversations: titles + message bodies (cap 20 conversations).
                services.conversations.list().fold(
                    onSuccess = { convs ->
                        for (conv in convs.take(20)) {
                            if (conv.title.contains(q, ignoreCase = true)) {
                                found += SearchHit("แชท", conv.title.ifBlank { "(ไม่มีชื่อ)" }, "ชื่อแชทตรงคำค้น") {
                                    onOpenConversation(conv.id)
                                }
                            } else {
                                val bodies = services.conversations.getMessages(conv.id).fold(
                                    onSuccess = { it },
                                    onFailure = { emptyList() },
                                )
                                val match = bodies.firstOrNull { it.text.contains(q, ignoreCase = true) }
                                if (match != null) {
                                    found += SearchHit(
                                        "แชท", conv.title.ifBlank { "(ไม่มีชื่อ)" },
                                        match.text.take(120),
                                    ) { onOpenConversation(conv.id) }
                                }
                            }
                        }
                    },
                    onFailure = {},
                )
                // Tools: files + projects + skills through the gateway (CP-111).
                suspend fun ask(toolId: String, action: String, label: String, route: String?) {
                    val call = ToolCall(Ids.newId("ui"), toolId, action, mapOf("query" to q), actor = "HUMAN")
                    services.gateway.call(call).fold(
                        onSuccess = {
                            val body = it.output.ifBlank { it.error }.take(800)
                            if (body.isNotBlank()) {
                                raws += label to body
                                if (route != null) {
                                    found += SearchHit(label, "ผลจาก$label", body.take(120)) { onOpen(route) }
                                }
                            }
                        },
                        onFailure = { raws += label to (it.message ?: "ค้นไม่ได้") },
                    )
                }
                ask("files", "search", "ไฟล์", Routes.PROJECTS)
                ask("media", "project.list", "โปรเจกต์", Routes.PROJECTS)
                ask("skill", "list", "สกิล", Routes.SKILLS)
            }
            hits = found
            rawOutputs = raws
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        AicodeSearchField(
            value = query,
            onValueChange = { query = it },
            onSearch = ::runSearch,
            placeholder = "ค้นหาแชท ไฟล์ โปรเจกต์ สกิล…",
        )
        Button(onClick = ::runSearch, enabled = !busy && query.isNotBlank()) {
            Text(if (busy) "กำลังค้น…" else "ค้นหา")
        }
        if (busy) {
            ShimmerSkeleton(lines = 4)
        } else if (searched && hits.isEmpty()) {
            Text(
                "ไม่พบ “$query” — ลองคำอื่น หรือถาม AI ในแชท",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else if (hits.isNotEmpty()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                items(hits, key = { it.group + it.title + it.detail }) { hit ->
                    SearchHitRow(hit)
                }
            }
        }
        if (rawOutputs.isNotEmpty() && !busy) {
            SectionHeader("รายละเอียดผลลัพธ์")
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                for ((label, body) in rawOutputs) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    OutputBlock(body)
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit) {
    val spacing = LocalSpacing.current
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.sm)) {
            Text(hit.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${hit.group} • ${hit.detail}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (hit.action != null) {
                TextButton(onClick = hit.action) { Text("เปิด") }
            }
        }
    }
}
