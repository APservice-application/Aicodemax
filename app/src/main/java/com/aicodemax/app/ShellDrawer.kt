package com.aicodemax.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aicodemax.data.conversations.Conversation
import com.aicodemax.ui.chat.ConversationDrawerContent
import com.aicodemax.ui.designsystem.AicodeSize
import com.aicodemax.ui.designsystem.LauncherEntry
import com.aicodemax.ui.designsystem.LauncherGrid
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.SectionHeader

/** CP-135 app launcher (§13): every workspace behind ☰ (no dashboard). */
private val appEntries = listOf(
    LauncherEntry(Routes.BROWSER, "เบราว์เซอร์", "🌐"),
    LauncherEntry(Routes.TIMELINE, "วิดีโอ", "🎬"),
    LauncherEntry(Routes.IMAGE, "แต่งรูป", "🖼️"),
    LauncherEntry(Routes.AUDIO, "ตัดเสียง", "🎚️"),
    LauncherEntry(Routes.GEN, "สร้างมีเดีย", "🎨"),
    LauncherEntry(Routes.RECORD, "อัดเสียง", "🎙️"),
    LauncherEntry(Routes.SUBTITLE, "ซับไตเติล", "📝"),
    LauncherEntry(Routes.PROJECTS, "โปรเจกต์", "📁"),
    LauncherEntry(Routes.TERMINAL, "เทอร์มินัล", "💻"),
    LauncherEntry(Routes.GIT, "Git", "🔀"),
    LauncherEntry(Routes.BUILD, "Build", "🛠️"),
    LauncherEntry(Routes.SKILLS, "Skills", "🧩"),
    LauncherEntry(Routes.RENDER, "เรนเดอร์", "🖥️"),
    LauncherEntry(Routes.TEMPLATES, "เทมเพลต", "📦"),
    LauncherEntry(Routes.AGENTS, "เอเจนต์", "🤖"),
    LauncherEntry(Routes.MEMORY, "ความจำ", "🧠"),
    LauncherEntry(Routes.TOOLS, "เครื่องมือ", "🧰"),
    LauncherEntry(Routes.ABOUT, "เกี่ยวกับ", "ℹ️"),
)

private val systemEntries = listOf(
    Triple(Routes.TASKS, "✅ งาน", "งานทั้งหมดของ AI"),
    Triple(Routes.MODELS, "🤖 โมเดล", "โมเดล AI ที่ติดตั้ง"),
    Triple(Routes.AUDIT, "🔍 ตรวจสอบ", "ประวัติการตรวจสอบ"),
    Triple(Routes.SETTINGS, "⚙️ ตั้งค่า", "ตั้งค่าทั้งหมด"),
)

/**
 * CP-135 shell drawer (§12): chat history + app launcher + system entries.
 * One drawer for the whole app; the chat screen no longer owns its own.
 */
@Composable
fun ShellDrawerContent(
    conversations: List<Conversation>,
    activeConversationId: String?,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onOpen: (String) -> Unit,
    onOpenChat: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(spacing.sm),
    ) {
        Text(
            text = "Aicodemax",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(spacing.sm),
        )
        TextButton(
            onClick = onOpenChat,
            modifier = Modifier.fillMaxWidth().heightIn(min = AicodeSize.MinTouch),
        ) { Text("💬 AI Chat (หน้าหลัก)") }
        SectionHeader("แชท")
        ConversationDrawerContent(
            conversations = conversations,
            activeId = activeConversationId,
            onNew = onNewChat,
            onSelect = onSelectConversation,
            onDelete = onDeleteConversation,
            onRename = onRenameConversation,
        )
        SectionHeader("แอป")
        LauncherGrid(entries = appEntries, onOpen = onOpen)
        SectionHeader("ระบบ")
        for ((route, label, hint) in systemEntries) {
            TextButton(
                onClick = { onOpen(route) },
                modifier = Modifier.fillMaxWidth().heightIn(min = AicodeSize.MinTouch),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(label)
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}
