package com.aicodemax.tools.builder

import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.common.runOutcome
import java.io.File
import java.security.MessageDigest

/** CP-27: artifact engine (MASTER_ARCHITECTURE — Artifact section). Build outputs with integrity hashes. */
enum class ArtifactKind { APK, AAB, LOG, REPORT, ZIP, OTHER }

data class Artifact(
    val id: String,
    val projectId: String,
    val kind: ArtifactKind,
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: Long,
)

class ArtifactStore(
    rootDir: File,
    private val clock: Clock = SystemClock,
) {
    private val base: File = rootDir.apply { mkdirs() }
    private val records = mutableMapOf<String, Artifact>()

    @Synchronized
    fun register(projectId: String, kind: ArtifactKind, file: File): Outcome<Artifact> =
        runOutcome("ARTIFACT_REGISTER") {
            check(projectId.isNotBlank()) { "project id is blank" }
            check(file.isFile) { "artifact file missing: '${file.path}'" }
            val artifact = Artifact(
                id = Ids.newId("art"),
                projectId = projectId,
                kind = kind,
                fileName = file.name,
                path = file.absolutePath,
                sizeBytes = file.length(),
                sha256 = sha256Of(file),
                createdAt = clock.nowMillis(),
            )
            records[artifact.id] = artifact
            artifact
        }

    @Synchronized
    fun get(id: String): Outcome<Artifact> = runOutcome("ARTIFACT_READ") {
        records[id] ?: throw NoSuchElementException("artifact '$id' not found")
    }

    @Synchronized
    fun list(projectId: String): List<Artifact> =
        records.values.filter { it.projectId == projectId }.sortedByDescending { it.createdAt }

    /** Re-hashes the file: detects tampering, moves, or truncation. */
    @Synchronized
    fun verify(id: String): Outcome<Artifact> = runOutcome("ARTIFACT_VERIFY") {
        val record = records[id] ?: throw NoSuchElementException("artifact '$id' not found")
        val file = File(record.path)
        check(file.isFile) { "artifact file gone: '${record.path}'" }
        check(file.length() == record.sizeBytes) { "size changed: ${file.length()} != ${record.sizeBytes}" }
        check(sha256Of(file) == record.sha256) { "hash mismatch (file changed)" }
        record
    }

    @Synchronized
    fun delete(id: String, deleteFile: Boolean = false): Outcome<Unit> = runOutcome("ARTIFACT_DELETE") {
        val record = records.remove(id) ?: throw NoSuchElementException("artifact '$id' not found")
        if (deleteFile) File(record.path).delete()
    }

    fun storeDir(): File = base

    companion object {
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
