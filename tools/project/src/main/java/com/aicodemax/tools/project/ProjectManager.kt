package com.aicodemax.tools.project

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.common.runOutcome
import java.io.File

/**
 * CP-20: project container (MASTER_ARCHITECTURE §20).
 * A project owns a root directory; files/git/tasks/memory attach by
 * project id in their own engines. Active-project selection persists.
 */
data class Project(
    val id: String,
    val name: String,
    val rootPath: String,
    val createdAt: Long,
    val updatedAt: Long,
)

class ProjectManager(
    rootDir: File,
    private val clock: Clock = SystemClock,
) {
    private val base: File = rootDir.apply { mkdirs() }
    private val activeFile: File = File(base, "active.txt")

    fun create(name: String): Outcome<Project> = runOutcome("PROJECT_CREATE") {
        val clean = name.trim()
        check(clean.isNotBlank()) { "project name is blank" }
        check(!clean.contains('/') && !clean.contains(File.separatorChar)) {
            "project name must not contain path separators"
        }
        val id = Ids.newId("proj")
        val dir = File(base, id).apply { mkdirs() }
        val now = clock.nowMillis()
        File(dir, ".project-meta").writeText("$now\n$clean")
        Project(id, clean, dir.absolutePath, now, now)
    }

    fun get(projectId: String): Outcome<Project> = runOutcome("PROJECT_READ") {
        val dir = File(base, projectId)
        if (!dir.isDirectory) throw NoSuchElementException("project '$projectId' not found")
        readProject(dir)
    }

    fun list(): Outcome<List<Project>> = runOutcome("PROJECT_READ") {
        (base.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .map { readProject(it) }
            .sortedWith(compareBy({ it.createdAt }, { it.name }))
    }

    fun delete(projectId: String): Outcome<Unit> = runOutcome("PROJECT_DELETE") {
        val dir = File(base, projectId)
        if (!dir.isDirectory) throw NoSuchElementException("project '$projectId' not found")
        if (!dir.deleteRecursively()) throw IllegalStateException("delete failed: '$projectId'")
        if (activeFile.isFile && activeFile.readText().trim() == projectId) activeFile.delete()
    }

    fun setActive(projectId: String): Outcome<Project> {
        return when (val project = get(projectId)) {
            is Outcome.Failure -> project
            is Outcome.Success -> runOutcome("PROJECT_WRITE") {
                activeFile.writeText(projectId)
                project.value
            }
        }
    }

    fun getActive(): Outcome<Project> {
        if (!activeFile.isFile) {
            return Outcome.Failure(AppError("PROJECT_NO_ACTIVE", "no active project"))
        }
        return get(activeFile.readText().trim())
    }

    private fun readProject(dir: File): Project {
        val metaFile = File(dir, ".project-meta")
        val lines = if (metaFile.isFile) metaFile.readText().lines() else emptyList()
        val createdAt = lines.getOrNull(0)?.toLongOrNull() ?: dir.lastModified()
        val name = lines.getOrNull(1)?.trim()?.ifBlank { dir.name } ?: dir.name
        return Project(dir.name, name, dir.absolutePath, createdAt, dir.lastModified())
    }
}
