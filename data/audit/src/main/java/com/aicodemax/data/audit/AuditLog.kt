package com.aicodemax.data.audit

import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.common.runOutcome
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AuditEntry(
    val id: String,
    val timestamp: Long,
    /** AI | USER | SYSTEM */
    val actor: String,
    val action: String,
    val toolId: String = "",
    val detail: String = "",
    val allowed: Boolean = true,
)

interface AuditLog {
    fun append(
        actor: String,
        action: String,
        toolId: String = "",
        detail: String = "",
        allowed: Boolean = true,
    ): Outcome<AuditEntry>

    /** Newest first. */
    fun query(limit: Int = 100): Outcome<List<AuditEntry>>
    fun count(): Long
}

/** Append-only JSONL audit log. Never deleted by the app (retention is a user action). */
class FileAuditLog(
    rootDir: File,
    private val clock: Clock = SystemClock,
) : AuditLog {
    private val json = Json { ignoreUnknownKeys = true }
    private val file: File = File(rootDir.apply { mkdirs() }, "audit.log")

    @Synchronized
    override fun append(
        actor: String,
        action: String,
        toolId: String,
        detail: String,
        allowed: Boolean,
    ): Outcome<AuditEntry> {
        val entry = AuditEntry(Ids.newId("audit"), clock.nowMillis(), actor, action, toolId, detail, allowed)
        return runOutcome("AUDIT_WRITE") {
            file.appendText(json.encodeToString(AuditEntry.serializer(), entry) + "\n")
            entry
        }
    }

    @Synchronized
    override fun query(limit: Int): Outcome<List<AuditEntry>> = runOutcome("AUDIT_READ") {
        if (!file.exists()) return@runOutcome emptyList()
        file.readLines()
            .filter { it.isNotBlank() }
            .takeLast(limit.coerceAtLeast(0))
            .map { json.decodeFromString(AuditEntry.serializer(), it) }
            .reversed()
    }

    @Synchronized
    override fun count(): Long {
        if (!file.exists()) return 0
        return file.readLines().count { it.isNotBlank() }.toLong()
    }
}
