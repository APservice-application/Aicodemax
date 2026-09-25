package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicodemax.core.common.fold
import com.aicodemax.ui.designsystem.LabeledField
import com.aicodemax.ui.designsystem.LocalSpacing

/**
 * CP-69 WebAI bridge (§31): localhost-only agent server. Start/stop,
 * issue bearer tokens (capabilities + expiry), revoke, inspect agents
 * and the request log. Tokens are shown ONCE at issue time.
 */
@Composable
fun WebAiScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    var message by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var label by remember { mutableStateOf("") }
    var caps by remember { mutableStateOf("files, editor") }
    var ttlMin by remember { mutableStateOf("60") }
    var issued by remember { mutableStateOf<String?>(null) }

    val server = services.webAiServer
    val running = server.isRunning()
    val grants = remember(tick) { services.webAiTokens.list() }
    val agents = remember { services.webAiAgents.list() }
    val log = remember(tick) { server.logSnapshot().takeLast(20).asReversed() }

    fun refresh() {
        tick++
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item(key = "__server__") {
            WorkspaceSection("🌉 เซิร์ฟเวอร์ (127.0.0.1 เท่านั้น)") {
                Text(
                    if (running) "รันอยู่ที่ 127.0.0.1:${server.port}" else "หยุดอยู่",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedButton(
                        onClick = {
                            server.start(0).fold(
                                onSuccess = { message = "bridge รันที่ 127.0.0.1:$it" },
                                onFailure = { message = it.message },
                            )
                            refresh()
                        },
                        enabled = !running,
                    ) { Text("▶ เริ่ม") }
                    OutlinedButton(
                        onClick = {
                            server.stop()
                            message = "หยุด bridge แล้ว"
                            refresh()
                        },
                        enabled = running,
                    ) { Text("⏹ หยุด") }
                }
                WorkspaceMessage(message)
            }
        }
        item(key = "__issue__") {
            WorkspaceSection("🎫 ออก token") {
                LabeledField("ชื่อ", label, { label = it }, modifier = Modifier.fillMaxWidth())
                LabeledField(
                    "ความสามารถ (คั่นจุลภาค หรือ *)",
                    caps, { caps = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledField("อายุ (นาที)", ttlMin, { ttlMin = it }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    val ttl = ttlMin.toLongOrNull()?.times(60_000)
                    if (ttl == null) {
                        message = "อายุนาทีต้องเป็นตัวเลข"
                        return@OutlinedButton
                    }
                    services.webAiTokens.issue(label, caps.split(",").map { it.trim() }.toSet(), ttl).fold(
                        onSuccess = {
                            issued = it.secret
                            message = "ออก token แล้ว — คัดลอกตอนนี้ แสดงครั้งเดียว"
                        },
                        onFailure = { message = it.message },
                    )
                    refresh()
                }) { Text("ออก token") }
                val token = issued
                if (token != null) {
                    Text(token, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item(key = "__tokens__") {
            WorkspaceSection("🔑 token ที่มี (${grants.size})") {
                if (grants.isEmpty()) {
                    Text("(ยังไม่มี)", style = MaterialTheme.typography.bodySmall)
                }
                grants.forEach { grant ->
                    val minsLeft = ((grant.expiresAtMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${grant.label} (${grant.tokenPrefix}…)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "ใช้ได้: ${grant.capabilities.sorted().joinToString()} · เหลือ ~${minsLeft} นาที",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(onClick = {
                            services.webAiTokens.revoke(grant.tokenPrefix)
                            refresh()
                        }) { Text("เพิกถอน") }
                    }
                }
            }
        }
        item(key = "__agents__") {
            WorkspaceSection("🤖 เอเจนต์ (${agents.size})") {
                agents.forEach { agent ->
                    Text(
                        "${agent.name} [${agent.id}] — ${agent.capabilities.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item(key = "__log__") {
            WorkspaceSection("📜 ล็อกคำขอ") {
                OutlinedButton(onClick = { refresh() }) { Text("🔄 รีเฟรช") }
                if (log.isEmpty()) {
                    Text("(ยังไม่มีคำขอ)", style = MaterialTheme.typography.bodySmall)
                }
                log.forEach { entry ->
                    Text(
                        "${entry.method} ${entry.path} → ${entry.code} ${entry.note}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
