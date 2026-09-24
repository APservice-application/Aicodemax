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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.git.GitBranch
import com.aicodemax.tools.git.GitCommit
import com.aicodemax.tools.git.GitStatus
import com.aicodemax.tools.github.GitHubClient
import com.aicodemax.tools.github.GitHubIssue
import com.aicodemax.tools.github.GitHubRepo
import com.aicodemax.tools.github.JavaNetHttpTransport
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.OutputBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class GitTab { CHANGES, COMMITS, BRANCHES, HISTORY, SYNC, GITHUB }

/**
 * CP-137 git workspace (SCR-GIT-001..006): changes/commits/branches/history/
 * sync + GitHub browser. All ops run on IO; destructive actions confirmed.
 */
@Composable
fun GitScreen(services: ServiceLocator, onHandToChat: (String) -> Unit = {}) {
    var tab by remember { mutableStateOf(GitTab.CHANGES) }
    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
            for (value in GitTab.values()) {
                Tab(
                    selected = tab == value,
                    onClick = { tab = value },
                    text = {
                        Text(
                            when (value) {
                                GitTab.CHANGES -> "การเปลี่ยน"
                                GitTab.COMMITS -> "คอมมิต"
                                GitTab.BRANCHES -> "สาขา"
                                GitTab.HISTORY -> "ประวัติ"
                                GitTab.SYNC -> "ซิงก์"
                                GitTab.GITHUB -> "GitHub"
                            },
                        )
                    },
                )
            }
        }
        when (tab) {
            GitTab.GITHUB -> GitHubTab()
            else -> LocalGitTab(services, tab, onHandToChat)
        }
    }
}

@Composable
private fun LocalGitTab(services: ServiceLocator, tab: GitTab, onHandToChat: (String) -> Unit) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val repo = remember { services.workspaceDir.path }
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var branches by remember { mutableStateOf<List<GitBranch>>(emptyList()) }
    var log by remember { mutableStateOf<List<GitCommit>>(emptyList()) }
    var diff by remember { mutableStateOf<String?>(null) }
    var conflicts by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var branchName by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isRepo by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

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
                services.git.log(repo, 20).fold(
                    onSuccess = { log = it },
                    onFailure = { },
                )
                services.git.conflicts(repo).fold(
                    onSuccess = { conflicts = it },
                    onFailure = { conflicts = emptyList() },
                )
            }
        }
    }

    fun io(label: String, call: suspend () -> Outcome<*>, say: String? = null) {
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) { call() }.fold(
                onSuccess = { error = null; output = say; load() },
                onFailure = { error = "$label: ${it.message}" },
            )
            busy = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (!isRepo) {
            Text("workspace ยังไม่ใช่ git repo")
            TextButton(onClick = { io("init", { services.git.ensureRepo(repo) }, "สร้าง repo แล้ว") }) {
                Text("Init repo")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            return
        }
        status?.let {
            Text(
                "⎇ ${it.branch} • " + if (it.clean) "clean" else "${it.changedFiles.size} ไฟล์เปลี่ยน",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        output?.let { OutputBlock(it.take(800)) }

        when (tab) {
            GitTab.CHANGES -> {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    OutlinedButton(onClick = { io("stage", { services.git.stageAll(repo) }, "stage ทั้งหมดแล้ว") }, enabled = !busy) {
                        Text("Stage ทั้งหมด")
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            services.git.diff(repo).fold(
                                onSuccess = { diff = it },
                                onFailure = { error = it.message },
                            )
                        }
                    }, enabled = !busy) { Text("ดู Diff") }
                    OutlinedButton(onClick = {
                        onHandToChat("สร้าง commit message ภาษาไทยสั้นๆ จากการเปลี่ยนใน workspace ตอนนี้ (ดู diff ก่อน แล้วตอบแค่ message)")
                    }) { Text("🤖 ช่วยเขียน message") }
                }
                val changed = status?.changedFiles ?: emptyList()
                if (changed.isEmpty()) {
                    Text("ไม่มีไฟล์เปลี่ยน — working tree clean")
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    items(changed, key = { it }) { file ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(file, modifier = Modifier.padding(spacing.sm))
                        }
                    }
                    if (diff != null) {
                        item(key = "__diff__") { OutputBlock(diff!!.take(2000)) }
                    }
                }
            }
            GitTab.COMMITS -> {
                TextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("commit message…") },
                )
                OutlinedButton(
                    onClick = {
                        io("commit", {
                            withContext(Dispatchers.IO) { services.git.stageAll(repo) }
                            services.git.commit(repo, message.ifBlank { "update" })
                        }, "commit แล้ว")
                        message = ""
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stage + Commit") }
                Text(
                    "หรือสั่ง AI: “สร้าง commit จากการแก้ไขชุดนี้”",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            GitTab.BRANCHES -> {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    TextField(
                        value = branchName,
                        onValueChange = { branchName = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("branch ใหม่…") },
                    )
                    OutlinedButton(
                        onClick = {
                            io("branch", { services.git.createBranch(repo, branchName) }, "สร้างสาขาแล้ว")
                            branchName = ""
                        },
                        enabled = !busy && branchName.isNotBlank(),
                    ) { Text("สร้าง") }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    items(branches, key = { it.name }) { branch ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (branch.current) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(modifier = Modifier.padding(spacing.sm)) {
                                Text(
                                    (if (branch.current) "* " else "") + branch.name,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!branch.current) {
                                    TextButton(
                                        onClick = { io("checkout", { services.git.checkout(repo, branch.name) }, "สลับสาขาแล้ว") },
                                        enabled = !busy,
                                    ) { Text("สลับ") }
                                }
                            }
                        }
                    }
                }
            }
            GitTab.HISTORY -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    items(log, key = { it.id }) { commit ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(spacing.sm)) {
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
            GitTab.SYNC -> {
                if (conflicts.isNotEmpty()) {
                    Text(
                        "⚠️ ไฟล์ขัดแย้ง ${conflicts.size} ไฟล์ — แก้ไขก่อน push/pull",
                        color = MaterialTheme.colorScheme.error,
                    )
                    for (file in conflicts) {
                        Text("• $file", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    OutlinedButton(onClick = { io("push", { services.git.push(repo) }, "push แล้ว") }, enabled = !busy) {
                        Text("⬆️ Push")
                    }
                    OutlinedButton(onClick = { io("pull", { services.git.pull(repo) }, "pull แล้ว") }, enabled = !busy) {
                        Text("⬇️ Pull")
                    }
                }
                Text(
                    "push/pull ต้องมีเน็ตและสิทธิ์ remote — ถ้าออฟไลน์จะอธิบายสาเหตุ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            GitTab.GITHUB -> Unit
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
