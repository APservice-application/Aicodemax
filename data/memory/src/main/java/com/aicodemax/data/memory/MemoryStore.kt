package com.aicodemax.data.memory

import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.common.runOutcome
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class MemoryRecord(
    val id: String,
    /** GLOBAL | PROJECT:<id> | TASK:<id> */
    val scope: String,
    val key: String,
    val value: String,
    val createdAt: Long,
    val updatedAt: Long,
)

interface MemoryStore {
    fun save(scope: String, key: String, value: String): Outcome<MemoryRecord>
    fun recall(scope: String, key: String): Outcome<MemoryRecord>
    fun search(query: String, scopePrefix: String = "", limit: Int = 20): Outcome<List<MemoryRecord>>
    fun delete(scope: String, key: String): Boolean
}

/**
 * File-backed memory (v0: substring search). Vector/semantic search plugs in
 * behind this same interface in a later phase.
 */
class FileMemoryStore(
    rootDir: File,
    private val clock: Clock = SystemClock,
) : MemoryStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSer = ListSerializer(MemoryRecord.serializer())
    private val file: File = File(rootDir.apply { mkdirs() }, "memories.json")

    private fun readAll(): MutableList<MemoryRecord> {
        if (!file.exists()) return mutableListOf()
        val text = file.readText()
        if (text.isBlank()) return mutableListOf()
        return json.decodeFromString(listSer, text).toMutableList()
    }

    private fun writeAll(records: List<MemoryRecord>) {
        file.writeText(json.encodeToString(listSer, records))
    }

    @Synchronized
    override fun save(scope: String, key: String, value: String): Outcome<MemoryRecord> =
        runOutcome("MEMORY_WRITE") {
            val records = readAll()
            val now = clock.nowMillis()
            val existing = records.firstOrNull { it.scope == scope && it.key == key }
            val record = if (existing == null) {
                MemoryRecord(Ids.newId("mem"), scope, key, value, now, now).also { records.add(it) }
            } else {
                existing.copy(value = value, updatedAt = now).also {
                    records[records.indexOf(existing)] = it
                }
            }
            writeAll(records)
            record
        }

    @Synchronized
    override fun recall(scope: String, key: String): Outcome<MemoryRecord> =
        runOutcome("MEMORY_READ") {
            readAll().firstOrNull { it.scope == scope && it.key == key }
                ?: throw NoSuchElementException("memory '$scope/$key' not found")
        }

    @Synchronized
    override fun search(query: String, scopePrefix: String, limit: Int): Outcome<List<MemoryRecord>> =
        runOutcome("MEMORY_READ") {
            readAll()
                .filter { it.scope.startsWith(scopePrefix) }
                .filter { it.key.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true) }
                .sortedByDescending { it.updatedAt }
                .take(limit.coerceAtLeast(0))
        }

    @Synchronized
    override fun delete(scope: String, key: String): Boolean = try {
        val records = readAll()
        val removed = records.removeIf { it.scope == scope && it.key == key }
        if (removed) writeAll(records)
        removed
    } catch (_: Exception) {
        false
    }
}
