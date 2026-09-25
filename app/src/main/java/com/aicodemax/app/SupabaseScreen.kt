package com.aicodemax.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.aicodemax.tools.supabase.SupabaseConfig
import com.aicodemax.tools.supabase.SupabaseConfigStore
import com.aicodemax.ui.designsystem.LabeledField
import com.aicodemax.ui.designsystem.LocalSpacing

/**
 * App-private key storage (MODE_PRIVATE sandbox). Keys never leave the
 * device except to the user's own Project URL. Devtool only (§24).
 */
class PrefSupabaseConfigStore(private val context: Context) : SupabaseConfigStore {
    private fun prefs() = context.getSharedPreferences("supabase_keys", Context.MODE_PRIVATE)

    override fun load(): SupabaseConfig? {
        val url = prefs().getString("url", "").orEmpty()
        val key = prefs().getString("key", "").orEmpty()
        return if (url.isBlank() || key.isBlank()) null else SupabaseConfig(url, key)
    }

    override fun save(config: SupabaseConfig) {
        val n = config.normalized()
        prefs().edit().putString("url", n.url).putString("key", n.key).apply()
    }

    override fun clear() {
        prefs().edit().clear().apply()
    }
}

/**
 * CP-68 Supabase connector (§156): user pastes their own Project URL +
 * API key, then tests/auth/queries/inserts. Raw JSON shown for inspection.
 */
@Composable
fun SupabaseScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(services.supabaseStore.load()?.url.orEmpty()) }
    var key by remember { mutableStateOf(services.supabaseStore.load()?.key.orEmpty()) }
    var showKey by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var table by remember { mutableStateOf("") }
    var select by remember { mutableStateOf("*") }
    var limit by remember { mutableStateOf("20") }
    var rowJson by remember { mutableStateOf("") }

    fun call(action: String, args: Map<String, String>) {
        scope.runTool(
            services, "supabase", action, args,
            onBusy = { busy = it },
            onMessage = {
                result = it
                message = null
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item(key = "__keys__") {
            WorkspaceSection("🗄️ คีย์ของคุณ") {
                LabeledField("Project URL", url, { url = it }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API key (anon/service_role)") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "🙈 ซ่อนคีย์" else "👁 แสดงคีย์")
                    }
                    OutlinedButton(onClick = {
                        services.supabaseStore.save(SupabaseConfig(url, key))
                        message = "บันทึกคีย์แล้ว (เก็บในแอปเท่านั้น ไม่ส่งไปไหน)"
                    }) { Text("💾 บันทึก") }
                    OutlinedButton(onClick = {
                        services.supabaseStore.clear()
                        url = ""
                        key = ""
                        message = "ล้างคีย์แล้ว"
                    }) { Text("🗑 ล้าง") }
                }
                WorkspaceMessage(message)
            }
        }
        item(key = "__health__") {
            WorkspaceSection("📡 ทดสอบเชื่อมต่อ") {
                OutlinedButton(
                    onClick = { call("health", emptyMap()) },
                    enabled = !busy,
                ) { Text("ทดสอบ") }
            }
        }
        item(key = "__auth__") {
            WorkspaceSection("🔑 ล็อกอิน (Auth)") {
                LabeledField("อีเมล", email, { email = it }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("รหัสผ่าน") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { call("auth", mapOf("email" to email.trim(), "password" to password)) },
                    enabled = !busy,
                ) { Text("เข้าสู่ระบบ") }
            }
        }
        item(key = "__query__") {
            WorkspaceSection("🔍 ค้นหาแถว (PostgREST)") {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    LabeledField("ตาราง", table, { table = it }, modifier = Modifier.weight(1f))
                    LabeledField("คอลัมน์", select, { select = it }, modifier = Modifier.weight(1f))
                    LabeledField("limit", limit, { limit = it }, modifier = Modifier.weight(1f))
                }
                OutlinedButton(
                    onClick = {
                        call(
                            "query",
                            mapOf("table" to table.trim(), "select" to select.trim(), "limit" to limit.trim()),
                        )
                    },
                    enabled = !busy,
                ) { Text("ค้นหา") }
            }
        }
        item(key = "__insert__") {
            WorkspaceSection("➕ เพิ่มแถว") {
                OutlinedTextField(
                    value = rowJson,
                    onValueChange = { rowJson = it },
                    label = { Text("ข้อมูล JSON 1 แถว เช่น {\"title\":\"hi\"}") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { call("insert", mapOf("table" to table.trim(), "row" to rowJson.trim())) },
                    enabled = !busy,
                ) { Text("เพิ่มแถวในตารางด้านบน") }
            }
        }
        if (result.isNotBlank()) {
            item(key = "__result__") {
                WorkspaceSection("📄 ผลลัพธ์") {
                    Text(result, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
