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

/** Git Center (CP-45): local git + GitHub (repos/issues), token memory-only. */
@Composable
fun GitScreen(services: ServiceLocator) {
    var tab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Git") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("GitHub") })
        }
        if (tab == 0) GitTab(services) else GitHubTab()
    }
}

@Composable
private fun GitTab(services: ServiceLocator) {
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

/** GitHub browser: repo info + issues + create issue. Token stays in memory only. */
@Composable
private fun GitHubTab() {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("APservice-application") }
    var name by remember { mutableStateOf("Aicodemax") }
    var repo by remember { mutableStateOf<GitHubRepo?>(null) }
    var issues by remember { mutableStateOf<List<GitHubIssue>>(emptyList()) }
    var issueTitle by remember { mutableStateOf("") }
    var issueBody by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val client = remember { GitHubClient(JavaNetHttpTransport(), token = { token.ifBlank { null } }) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            val repoResult = withContext(Dispatchers.IO) { client.repo(owner.trim(), name.trim()) }
            repoResult.fold(
                onSuccess = { repo = it },
                onFailure = { error = "repo: ${it.message}"; repo = null },
            )
            withContext(Dispatchers.IO) { client.listIssues(owner.trim(), name.trim()) }.fold(
                onSuccess = { issues = it },
                onFailure = { if (repo != null) error = "issues: ${it.message}" },
            )
            loading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.md).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        TextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = { Text("GitHub token (ถ้ามี)…") },
        )
        Text(
            "โทเคนอยู่ในหน่วยความจำเท่านั้น — ไม่ถูกบันทึก (public repo ไม่ต้องใส่)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            TextField(
                value = owner, onValueChange = { owner = it },
                modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("owner") },
            )
            TextField(
                value = name, onValueChange = { name = it },
                modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("repo") },
            )
        }
        TextButton(onClick = { load() }, enabled = !loading) {
            Text(if (loading) "กำลังโหลด…" else "โหลด")
        }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        repo?.let {
            Text(
                "📦 ${it.fullName} • ${it.defaultBranch}" + if (it.private) " • private" else "",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text("issues (${issues.size})", style = MaterialTheme.typography.titleSmall)
        if (issues.isEmpty()) {
            Text("ยังไม่มี issue (หรือโหลดไม่สำเร็จ)", style = MaterialTheme.typography.bodySmall)
        }
        issues.take(30).forEach { issue ->
            Text(
                "#${issue.number} ${issue.title} [${issue.state}]",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("สร้าง issue ใหม่ (ต้องมี token)", style = MaterialTheme.typography.titleSmall)
        TextField(
            value = issueTitle, onValueChange = { issueTitle = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("หัวข้อ…") },
        )
        TextField(
            value = issueBody, onValueChange = { issueBody = it },
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("รายละเอียด…") },
        )
        TextButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        client.createIssue(owner.trim(), name.trim(), issueTitle.trim(), issueBody.trim())
                    }.fold(
                        onSuccess = { issueTitle = ""; issueBody = ""; error = null; load() },
                        onFailure = { error = "create: ${it.message}" },
                    )
                }
            },
            enabled = token.isNotBlank() && issueTitle.isNotBlank(),
        ) { Text("สร้าง issue") }
    }
}
