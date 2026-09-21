package com.aicodemax.tools.files

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import com.aicodemax.tools.registry.ToolDescriptor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Real file runtime on app-scoped storage with path-traversal guard. */
class SandboxFileStore(rootDir: File) : FilePort {
    private val root: File = rootDir.apply { mkdirs() }
    private val rootCanonical: String = root.canonicalPath

    companion object {
        const val MAX_READ_BYTES = 1_000_000L
    }

    override fun descriptor(): ToolDescriptor = filesDescriptorToday()

    private fun resolve(relativePath: String): Outcome<File> {
        val normalized = relativePath.trim().trim('/', ' ', '\n', '\t')
        if (normalized.isEmpty()) return Outcome.Success(root)
        return try {
            val file = File(root, normalized).canonicalFile
            if (file.canonicalPath == rootCanonical || file.canonicalPath.startsWith("$rootCanonical/")) {
                Outcome.Success(file)
            } else {
                Outcome.Failure(AppError("PATH_ESCAPE", "path escapes sandbox: '$relativePath'"))
            }
        } catch (t: Exception) {
            Outcome.Failure(AppError("PATH_INVALID", t.message ?: "invalid path"))
        }
    }

    override suspend fun list(relativeDir: String): Outcome<List<FileEntry>> =
        withContext(Dispatchers.IO) {
            when (val resolved = resolve(relativeDir)) {
                is Outcome.Failure -> resolved
                is Outcome.Success -> runOutcome("FILES_LIST") {
                    val dir = resolved.value
                    if (!dir.exists()) throw NoSuchElementException("directory not found: '$relativeDir'")
                    if (!dir.isDirectory) throw IllegalArgumentException("not a directory: '$relativeDir'")
                    (dir.listFiles() ?: emptyArray())
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        .map {
                            FileEntry(
                                name = it.name,
                                path = it.relativeTo(root).invariantSeparatorsPath,
                                isDirectory = it.isDirectory,
                                sizeBytes = if (it.isFile) it.length() else 0,
                                modifiedAt = it.lastModified(),
                            )
                        }
                }
            }
        }

    override suspend fun read(relativePath: String): Outcome<String> =
        withContext(Dispatchers.IO) {
            when (val resolved = resolve(relativePath)) {
                is Outcome.Failure -> resolved
                is Outcome.Success -> runOutcome("FILES_READ") {
                    val file = resolved.value
                    if (!file.exists()) throw NoSuchElementException("file not found: '$relativePath'")
                    if (!file.isFile) throw IllegalArgumentException("not a file: '$relativePath'")
                    if (file.length() > MAX_READ_BYTES) {
                        throw IllegalStateException("file too large (${file.length()} bytes, cap $MAX_READ_BYTES)")
                    }
                    file.readText()
                }
            }
        }

    override suspend fun write(relativePath: String, content: String): Outcome<Long> =
        withContext(Dispatchers.IO) {
            when (val resolved = resolve(relativePath)) {
                is Outcome.Failure -> resolved
                is Outcome.Success -> runOutcome("FILES_WRITE") {
                    val file = resolved.value
                    if (file.canonicalPath == rootCanonical) {
                        throw IllegalArgumentException("cannot write to sandbox root")
                    }
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    // Read-back verification (100% contract: verify what we claim).
                    val check = file.readText()
                    if (check != content) throw IllegalStateException("read-back verification failed")
                    file.length()
                }
            }
        }

    override suspend fun mkdir(relativePath: String): Outcome<Unit> =
        withContext(Dispatchers.IO) {
            when (val resolved = resolve(relativePath)) {
                is Outcome.Failure -> resolved
                is Outcome.Success -> runOutcome("FILES_MKDIR") {
                    if (!resolved.value.exists() && !resolved.value.mkdirs()) {
                        throw IllegalStateException("cannot create directory: '$relativePath'")
                    }
                }
            }
        }

    override suspend fun delete(relativePath: String): Outcome<Unit> =
        withContext(Dispatchers.IO) {
            when (val resolved = resolve(relativePath)) {
                is Outcome.Failure -> resolved
                is Outcome.Success -> runOutcome("FILES_DELETE") {
                    val file = resolved.value
                    if (file.canonicalPath == rootCanonical) {
                        throw IllegalArgumentException("cannot delete sandbox root")
                    }
                    if (!file.exists()) throw NoSuchElementException("not found: '$relativePath'")
                    if (!file.deleteRecursively()) throw IllegalStateException("delete failed: '$relativePath'")
                }
            }
        }

    override suspend fun exists(relativePath: String): Outcome<Boolean> =
        withContext(Dispatchers.IO) {
            when (val resolved = resolve(relativePath)) {
                is Outcome.Failure -> resolved
                is Outcome.Success -> Outcome.Success(resolved.value.exists())
            }
        }
}
