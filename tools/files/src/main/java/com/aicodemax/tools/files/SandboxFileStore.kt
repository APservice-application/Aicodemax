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

    override suspend fun copy(source: String, dest: String): Outcome<Unit> =
        withContext(Dispatchers.IO) {
            when (val src = resolve(source)) {
                is Outcome.Failure -> src
                is Outcome.Success -> when (val dst = resolve(dest)) {
                    is Outcome.Failure -> dst
                    is Outcome.Success -> runOutcome("FILES_COPY") {
                        val from = src.value
                        val to = dst.value
                        if (!from.exists()) throw NoSuchElementException("not found: '$source'")
                        if (to.canonicalPath == rootCanonical) throw IllegalArgumentException("cannot overwrite sandbox root")
                        to.parentFile?.mkdirs()
                        val copied = if (from.isDirectory) {
                            from.copyRecursively(to, overwrite = true)
                        } else {
                            from.copyTo(to, overwrite = true)
                            true
                        }
                        if (!copied) throw IllegalStateException("copy failed: '$source'")
                    }
                }
            }
        }

    override suspend fun move(source: String, dest: String): Outcome<Unit> =
        withContext(Dispatchers.IO) {
            when (val copied = copy(source, dest)) {
                is Outcome.Failure -> copied
                is Outcome.Success -> delete(source)
            }
        }

    override suspend fun search(
        relativeDir: String,
        query: String,
        maxResults: Int,
    ): Outcome<List<ContentMatch>> {
        if (query.isBlank()) {
            return Outcome.Failure(AppError("SEARCH_NO_QUERY", "search query is blank"))
        }
        return withContext(Dispatchers.IO) {
            when (val resolved = resolve(relativeDir)) {
                is Outcome.Failure -> resolved
                is Outcome.Success -> runOutcome("FILES_SEARCH") {
                    val dir = resolved.value
                    if (!dir.isDirectory) throw IllegalArgumentException("not a directory: '$relativeDir'")
                    val matches = mutableListOf<ContentMatch>()
                    val limit = maxResults.coerceIn(1, 500)
                    dir.walkTopDown().filter { it.isFile && it.length() <= MAX_READ_BYTES }.forEach { file ->
                        if (matches.size >= limit) return@forEach
                        file.readLines().forEachIndexed { index, line ->
                            if (matches.size >= limit) return@forEachIndexed
                            if (line.contains(query)) {
                                matches.add(
                                    ContentMatch(
                                        file.relativeTo(root).invariantSeparatorsPath,
                                        index + 1,
                                        line.take(300),
                                    ),
                                )
                            }
                        }
                    }
                    matches
                }
            }
        }
    }

    override suspend fun archive(relativeDir: String, outName: String): Outcome<Long> =
        withContext(Dispatchers.IO) {
            when (val src = resolve(relativeDir)) {
                is Outcome.Failure -> src
                is Outcome.Success -> when (val dst = resolve(outName)) {
                    is Outcome.Failure -> dst
                    is Outcome.Success -> runOutcome("FILES_ARCHIVE") {
                        val dir = src.value
                        if (!dir.isDirectory) throw IllegalArgumentException("not a directory: '$relativeDir'")
                        val out = dst.value
                        if (out.canonicalPath == rootCanonical) throw IllegalArgumentException("cannot overwrite sandbox root")
                        when (val zipped = com.aicodemax.core.common.ZipArchive.zipDir(dir, out)) {
                            is Outcome.Failure -> throw IllegalStateException(zipped.error.message)
                            is Outcome.Success -> out.length()
                        }
                    }
                }
            }
        }

    override suspend fun unarchive(relativeZip: String, destDir: String): Outcome<Int> =
        withContext(Dispatchers.IO) {
            when (val src = resolve(relativeZip)) {
                is Outcome.Failure -> src
                is Outcome.Success -> when (val dst = resolve(destDir)) {
                    is Outcome.Failure -> dst
                    is Outcome.Success -> runOutcome("FILES_UNARCHIVE") {
                        val count = com.aicodemax.core.common.ZipArchive.unzip(src.value, dst.value)
                        when (count) {
                            is Outcome.Failure -> throw IllegalStateException(count.error.message)
                            is Outcome.Success -> count.value
                        }
                    }
                }
            }
        }

    override suspend fun metadata(relativePath: String): Outcome<FileMetadata> =
        withContext(Dispatchers.IO) {
            when (val resolved = resolve(relativePath)) {
                is Outcome.Failure -> resolved
                is Outcome.Success -> runOutcome("FILES_METADATA") {
                    val file = resolved.value
                    if (!file.exists()) throw NoSuchElementException("not found: '$relativePath'")
                    FileMetadata(
                        path = if (file.canonicalPath == rootCanonical) "" else file.relativeTo(root).invariantSeparatorsPath,
                        isDirectory = file.isDirectory,
                        sizeBytes = if (file.isFile) file.length() else 0,
                        modifiedAt = file.lastModified(),
                    )
                }
            }
        }
}
