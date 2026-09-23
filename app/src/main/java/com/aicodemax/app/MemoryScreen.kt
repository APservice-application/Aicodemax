package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.launch

/** CP-117: full UI for memory (กฎข้อ 5) — save/recall + learned lessons. */
@Composable
fun MemoryScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var lessons by remember { mutableStateOf("ยังไม่ได้โหลด — กด “โหลดบทเรียน”") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(tool: String, action: String, args: Map<String, String>, onOk: (String) -> Unit = {}) {
        busy = true
        scope.launch {
            services.gateway.call(ToolCall(Ids.newId("ui"), tool, action, args, actor = "HUMAN")).fold(
                onSuccess = {
                    if (it.ok) {
                        message = null
                        onOk(it.output)
                    } else {
                        message = it.error
                    }
                },
                onFailure = { message = it.message },
            )
            busy = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                Text("ความจำ", style = MaterialTheme.typography.titleMedium)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Text("จำสิ่งใหม่", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextField(value = key, onValueChange = { key = it }, label = { Text("หัวข้อ (เช่น wifi)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = value, onValueChange = { value = it }, label = { Text("สิ่งที่จำ (เช่น รหัส 1234)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        run("memory", "save", mapOf("key" to key.trim(), "value" to value.trim())) {
                            message = "จำแล้ว: ${key.trim()}"
                            value = ""
                        }
                    }, enabled = !busy && key.isNotBlank() && value.isNotBlank()) { Text("จำไว้") }
                }
            }
            item {
                Text("ทวนความจำ", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextField(value = key, onValueChange = { key = it }, label = { Text("หัวข้อ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        run("memory", "recall", mapOf("key" to key.trim())) { message = it }
                    }, enabled = !busy && key.isNotBlank()) { Text("ทวน") }
                }
            }
            item {
                Text("บทเรียนที่ AI เรียนรู้", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(lessons, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        run("memory", "lessons", emptyMap()) { lessons = it }
                    }, enabled = !busy) { Text("โหลดบทเรียน") }
                }
            }
        }
    }
}
