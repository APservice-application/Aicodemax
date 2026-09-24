package com.aicodemax.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.Project
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.OutputBlock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * CP-136 shared workspace helpers: pick/copy files, gateway calls, project
 * picker, result output. Every media workspace reuses these.
 */

/** Copies a picked content URI into the workspace so tools can read it. */
fun copyUriIntoWorkspace(context: Context, workspaceDir: File, subdir: String, uri: Uri): String? {
    return try {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: "import-${System.currentTimeMillis()}"
        val dir = File(workspaceDir, subdir).apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val dest = File(dir, "${System.currentTimeMillis()}-$safe")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: return null
        dest.absolutePath
    } catch (_: Exception) {
        null
    }
}

/** Runs one gateway call, reporting busy state and the result message. */
fun CoroutineScope.runTool(
    services: ServiceLocator,
    toolId: String,
    action: String,
    args: Map<String, String>,
    onBusy: (Boolean) -> Unit,
    onMessage: (String) -> Unit,
) {
    onBusy(true)
    launch {
        services.gateway.call(
            ToolCall(Ids.newId("ui"), toolId, action, args, actor = "HUMAN"),
        ).fold(
            onSuccess = { onMessage(if (it.ok) it.output else it.error) },
            onFailure = { onMessage(it.message ?: "เรียกเครื่องมือไม่ได้") },
        )
        onBusy(false)
    }
}

/** Project switcher row shared by media workspaces. */
@Composable
fun ProjectPickerRow(
    projects: List<Project>,
    index: Int,
    onIndex: (Int) -> Unit,
    label: String = "โปรเจกต์",
) {
    val spacing = LocalSpacing.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$label: ${projects.getOrNull(index)?.name ?: "—"}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (projects.size > 1) {
            OutlinedButton(onClick = { onIndex((index + 1) % projects.size) }) {
                Text("สลับ")
            }
        }
    }
}

/** Result/error message block shared by media workspaces. */
@Composable
fun WorkspaceMessage(message: String?) {
    if (message != null) {
        OutputBlock(message.take(2000))
    }
}

/** Section title + content column shared by media workspaces. */
@Composable
fun WorkspaceSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = LocalSpacing.current.xs),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}
