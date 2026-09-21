package com.aicodemax.tools.files

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.registry.ToolDescriptor

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

data class ContentMatch(
    val path: String,
    val lineNumber: Int,
    val line: String,
)

data class FileMetadata(
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/** App-scoped file access. All paths are relative to the sandbox root. */
interface FilePort {
    fun descriptor(): ToolDescriptor
    suspend fun list(relativeDir: String = ""): Outcome<List<FileEntry>>
    suspend fun read(relativePath: String): Outcome<String>
    suspend fun write(relativePath: String, content: String): Outcome<Long>
    suspend fun mkdir(relativePath: String): Outcome<Unit>
    suspend fun delete(relativePath: String): Outcome<Unit>
    suspend fun exists(relativePath: String): Outcome<Boolean>
    suspend fun copy(source: String, dest: String): Outcome<Unit>
    suspend fun move(source: String, dest: String): Outcome<Unit>
    suspend fun search(relativeDir: String, query: String, maxResults: Int = 50): Outcome<List<ContentMatch>>
    suspend fun archive(relativeDir: String, outName: String): Outcome<Long>
    suspend fun unarchive(relativeZip: String, destDir: String): Outcome<Int>
    suspend fun metadata(relativePath: String): Outcome<FileMetadata>
}
