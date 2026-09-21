package com.aicodemax.data.backup

import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.common.ZipArchive
import com.aicodemax.core.common.fold
import java.io.File

data class BackupInfo(
    val fileName: String,
    val bytes: Long,
    val entries: Int,
    val createdAt: Long,
)

/**
 * CP-03: backup/restore over zip files (MASTER_ARCHITECTURE §3 data layer).
 * Exports a root directory (state, conversations, audit…) and restores it
 * back with zip-slip protection. Overwrites on restore — callers must
 * confirm with the user first (destructive by design).
 */
class BackupManager(
    private val rootDir: File,
    private val clock: Clock = SystemClock,
) {
    fun exportZip(outFile: File): Outcome<BackupInfo> {
        rootDir.mkdirs()
        return ZipArchive.zipDir(rootDir, outFile).fold(
            onSuccess = { Outcome.Success(BackupInfo(outFile.name, outFile.length(), it, clock.nowMillis())) },
            onFailure = { Outcome.Failure(it) },
        )
    }

    fun importZip(zipFile: File): Outcome<BackupInfo> {
        rootDir.mkdirs()
        return ZipArchive.unzip(zipFile, rootDir).fold(
            onSuccess = { Outcome.Success(BackupInfo(zipFile.name, zipFile.length(), it, clock.nowMillis())) },
            onFailure = { Outcome.Failure(it) },
        )
    }

    /** Full backup: several roots (settings, conversations, memory…) in one zip. */
    fun exportFull(outFile: File, sources: Map<String, File>): Outcome<BackupInfo> {
        return ZipArchive.zipDirs(sources, outFile).fold(
            onSuccess = { Outcome.Success(BackupInfo(outFile.name, outFile.length(), it, clock.nowMillis())) },
            onFailure = { Outcome.Failure(it) },
        )
    }

    /** Restores a full backup under [destRoot] (one directory per section). */
    fun importFull(zipFile: File, destRoot: File): Outcome<BackupInfo> {
        destRoot.mkdirs()
        return ZipArchive.unzip(zipFile, destRoot).fold(
            onSuccess = { Outcome.Success(BackupInfo(zipFile.name, zipFile.length(), it, clock.nowMillis())) },
            onFailure = { Outcome.Failure(it) },
        )
    }

    fun listBackups(backupDir: File): List<BackupInfo> {
        if (!backupDir.isDirectory) return emptyList()
        return backupDir.listFiles { f -> f.isFile && f.extension == "zip" }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { BackupInfo(it.name, it.length(), -1, it.lastModified()) }
    }
}
