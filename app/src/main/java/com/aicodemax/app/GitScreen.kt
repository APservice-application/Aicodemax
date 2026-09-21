package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicodemax.core.common.fold
import com.aicodemax.tools.git.GitBranch
import com.aicodemax.tools.git.GitCommit
import com.aicodemax.tools.git.GitStatus
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Git Center (CP-45) over the app workspace: status/branches/diff/commit/push/pull. */
@Composable
fun GitScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val repo = remember { services.workspaceDir.path }
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var branches by remember { mutableStateOf<List<GitBranch>>(emptyList()) }
    var log by remember { mutableStateOf<List<GitCommit>>(emptyList()) }
    var diff by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var branchName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isRepo by remember { mutableStateOf(true) }

    fun load() {
        scope.launch {
            val st = withContext(Dispatchers.IO) { services.git.status(repo) }
            st.fold(
                onSuccess = {
                    status = it
                    isRepo = true
                    error = null
                },
                onFailure = { isRepo = false; status = null },
            )
            if (isRepo) {
                services.git.branches(repo).fold(
                    onSuccess = { branches = it },
                    onFailure = { },
                )
                services.git.log(repo, 10).fold(
                    onSuccess = { log = it },
                    onFailure = { },
                )
            }
        }
    }

    fun io(label: String, call: suspend () -> com.aicodemax.core.common.Outcome<*>) {
        scope.launch {
            withContext(Dispatchers.IO) { call() }.fold(
                onSuccess = { error = null; load() },
                onFailure = { error = "$label: ${it.message}" },
            )
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (!isRepo) {
            Text("workspace ยังไม่ใช่ git repo")
            TextButton(onClick = { io("init") { services.git.ensureRepo(repo) } }) { Text("Init repo") }
        } else {
            status?.let {
                Text(
                    "⎇ ${it.branch} • " + if (it.clean) "clean" else "${it.changedFiles.size} ไฟล์เปลี่ยน",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("commit message…") },
                )
                TextButton(
                    onClick = {
                        io("commit") {
                            withContext(Dispatchers.IO) { services.git.stageAll(repo) }
                            services.git.commit(repo, message.ifBlank { "update" })
                        }
                        message = ""
                    },
                ) { Text("Commit") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("branch ใหม่…") },
                )
                TextButton(
                    onClick = {
                        io("branch") { services.git.createBranch(repo, branchName) }
                        branchName = ""
                    },
                    enabled = branchName.isNotBlank(),
                ) { Text("สร้าง") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TextButton(onClick = {
                    scope.launch {
                        services.git.diff(repo).fold(
                            onSuccess = { diff = it },
                            onFailure = { error = it.message },
                        )
                    }
                }) { Text("Diff") }
                TextButton(onClick = { io("push") { services.git.push(repo) } }) { Text("Push") }
                TextButton(onClick = { io("pull") { services.git.pull(repo) } }) { Text("Pull") }
            }
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                item(key = "__branches__") {
                    Text(
                        "branches: " + branches.joinToString(", ") { (if (it.current) "* " else "") + it.name },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (diff != null) {
                    item(key = "__diff__") {
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                diff!!.take(2000),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(spacing.sm),
                            )
                        }
                    }
                }
                items(log, key = { it.id }) { commit ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(modifier = Modifier.fillMaxWidth().padding(spacing.sm)) {
                            Text(commit.message, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${commit.id.take(7)} • ${commit.author}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
