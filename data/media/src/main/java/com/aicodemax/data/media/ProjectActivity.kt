package com.aicodemax.data.media

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val activityJson = Json { prettyPrint = false }

/**
 * CP-71 project event (§110): every mutation appends one. Stored as
 * `<projectId>/events.jsonl` (one JSON object per line, human-greppable).
 */
@Serializable
data class ProjectEvent(
    val id: String,
    val type: String,
    val projectId: String,
    val at: Long,
    val actor: String,
    val detail: Map<String, String> = emptyMap(),
)

object ProjectEventTypes {
    const val PROJECT_CREATED = "PROJECT_CREATED"
    const val PROJECT_RENAMED = "PROJECT_RENAMED"
    const val PROJECT_DUPLICATED = "PROJECT_DUPLICATED"
    const val PROJECT_DELETED = "PROJECT_DELETED"
    const val PROJECT_RESTORED = "PROJECT_RESTORED"
    const val PROJECT_BACKED_UP = "PROJECT_BACKED_UP"
    const val ASSET_IMPORTED = "ASSET_IMPORTED"
    const val ASSET_REMOVED = "ASSET_REMOVED"
    const val CLIP_ADDED = "CLIP_ADDED"
    const val TIMELINE_SET = "TIMELINE_SET"
    const val VERSION_SAVED = "VERSION_SAVED"
    const val VERSION_RESTORED = "VERSION_RESTORED"
    const val UNDO = "UNDO"
    const val REDO = "REDO"
    const val CHECKPOINT_SAVED = "CHECKPOINT_SAVED"
    const val CHECKPOINT_RECOVERED = "CHECKPOINT_RECOVERED"
    const val TRANSACTION = "TRANSACTION"
    const val CLIP_SPLIT = "CLIP_SPLIT"
    const val CLIP_TRIMMED = "CLIP_TRIMMED"
    const val CLIP_MOVED = "CLIP_MOVED"
    const val CLIP_DELETED = "CLIP_DELETED"
    const val CLIP_DUPLICATED = "CLIP_DUPLICATED"
    const val MARKER_ADDED = "MARKER_ADDED"
    const val MARKER_REMOVED = "MARKER_REMOVED"
    const val TRACK_FLAGS = "TRACK_FLAGS"
    const val CLIP_TRANSFORMED = "CLIP_TRANSFORMED"
    const val CLIP_FROZEN = "CLIP_FROZEN"
    const val TEXT_ADDED = "TEXT_ADDED"
    const val TEXT_UPDATED = "TEXT_UPDATED"
    const val TEXT_REMOVED = "TEXT_REMOVED"
    const val CLIP_SPEED = "CLIP_SPEED"
    const val KEYFRAME_SET = "KEYFRAME_SET"
    const val KEYFRAME_REMOVED = "KEYFRAME_REMOVED"
    const val KEYFRAMES_CLEARED = "KEYFRAMES_CLEARED"
    const val TRANSITION_SET = "TRANSITION_SET"
    const val TRANSITION_CLEARED = "TRANSITION_CLEARED"
    const val CLIP_FX = "CLIP_FX"
    const val CLIP_COLOR = "CLIP_COLOR"
    const val CLIP_MASK = "CLIP_MASK"
    const val CLIP_LUT = "CLIP_LUT"
    const val TEMPLATE_APPLIED = "TEMPLATE_APPLIED"
    const val CLIP_MOTION = "CLIP_MOTION"
    const val SLIDESHOW_MADE = "SLIDESHOW_MADE"
    const val CLIP_VOLUME = "CLIP_VOLUME"
    const val CLIP_AUTOCUT = "CLIP_AUTOCUT"
    const val TIMELINE_CANVAS = "TIMELINE_CANVAS"
    const val CLIP_ENHANCE = "CLIP_ENHANCE"
    const val BRAND_APPLIED = "BRAND_APPLIED"
    const val CLIP_CHROMA = "CLIP_CHROMA"
    const val TIMELINE_BG = "TIMELINE_BG"
    const val TRACK_APPLIED = "TRACK_APPLIED"
    const val STAB_APPLIED = "STAB_APPLIED"
}

class FileEventLog(root: File) {
    private val rootDir = root

    fun append(event: ProjectEvent) {
        try {
            File(rootDir, "${event.projectId}/events.jsonl").appendText(
                activityJson.encodeToString(event) + "\n",
            )
        } catch (_: Exception) {
        }
    }

    /** Newest last; returns at most the last [limit] entries. */
    fun list(projectId: String, limit: Int = 50): List<ProjectEvent> {
        val file = File(rootDir, "$projectId/events.jsonl")
        if (!file.isFile) return emptyList()
        return try {
            file.readLines()
                .takeLast(limit.coerceAtLeast(1))
                .mapNotNull { line ->
                    try {
                        activityJson.decodeFromString<ProjectEvent>(line)
                    } catch (_: Exception) {
                        null
                    }
                }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Snapshot of everything undo/redo/checkpoint must restore (null project = did not exist). */
@Serializable
data class ProjectSnapshot(
    val project: Project?,
    val assets: List<MediaAsset> = emptyList(),
)

@Serializable
data class UndoEntry(
    val label: String,
    val actor: String,
    val at: Long,
    val snapshot: ProjectSnapshot,
)

@Serializable
data class UndoEntryMeta(
    val file: String,
    val label: String,
    val actor: String,
    val at: Long,
)

/**
 * CP-71 file-backed undo/redo (§87): snapshot stacks under
 * `<projectId>/undo/` and `<projectId>/redo/`, cap 30 each. Redo clears on
 * every new push (standard editor semantics).
 */
class FileUndoStore(root: File) {
    private val rootDir = root
    val maxEntries: Int = 30

    fun push(projectId: String, label: String, actor: String, snapshot: ProjectSnapshot) {
        try {
            val dir = stackDir(projectId, "undo")
            val file = nextName(dir, "e")

            File(dir, file).writeText(activityJson.encodeToString(UndoEntry(label, actor, System.currentTimeMillis(), snapshot)))
            clearStack(projectId, "redo")
            trim(dir)
        } catch (_: Exception) {
        }
    }

    fun undo(projectId: String, current: ProjectSnapshot): Outcome<UndoEntry> {
        val popped = pop(projectId, "undo")
            ?: return Outcome.Failure(AppError("MEDIA_UNDO_EMPTY", "ไม่มีอะไรให้เลิกทำ"))
        try {
            val dir = stackDir(projectId, "redo")
            val file = nextName(dir, "e")
            File(dir, file).writeText(
                activityJson.encodeToString(UndoEntry(popped.label, popped.actor, System.currentTimeMillis(), current)),
            )
            trim(dir)
        } catch (_: Exception) {
        }
        return Outcome.Success(popped)
    }

    fun redo(projectId: String, current: ProjectSnapshot): Outcome<UndoEntry> {
        val popped = pop(projectId, "redo")
            ?: return Outcome.Failure(AppError("MEDIA_REDO_EMPTY", "ไม่มีอะไรให้ทำซ้ำ"))
        try {
            val dir = stackDir(projectId, "undo")
            val file = nextName(dir, "e")
            File(dir, file).writeText(
                activityJson.encodeToString(UndoEntry(popped.label, actor = popped.actor, at = System.currentTimeMillis(), snapshot = current)),
            )
            trim(dir)
        } catch (_: Exception) {
        }
        return Outcome.Success(popped)
    }

    /** Oldest first. */
    fun history(projectId: String): List<UndoEntryMeta> =
        stackDir(projectId, "undo").listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { file ->
                try {
                    val entry = activityJson.decodeFromString<UndoEntry>(file.readText())
                    UndoEntryMeta(file.name, entry.label, entry.actor, entry.at)
                } catch (_: Exception) {
                    null
                }
            }

    private fun pop(projectId: String, stack: String): UndoEntry? {
        val dir = stackDir(projectId, stack)
        val file = dir.listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .maxByOrNull { it.name } ?: return null
        return try {
            val entry = activityJson.decodeFromString<UndoEntry>(file.readText())
            file.delete()
            entry
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fixed-width monotonic names (nanoTime + seq): lexicographic order ==
     * chronological order, so pop/trim always hit the right end. The old
     * `e<ms>_<rand>` scheme flaked when two pushes shared a millisecond.
     */
    private val seq = java.util.concurrent.atomic.AtomicLong(0)

    private fun nextName(dir: File, prefix: String): String {
        val nano = System.nanoTime()
        val n = seq.incrementAndGet() % 1_000_000
        return "%s%020d-%06d.json".format(prefix, nano, n)
    }

    private fun stackDir(projectId: String, stack: String): File =
        File(rootDir, "$projectId/$stack").also { it.mkdirs() }

    private fun clearStack(projectId: String, stack: String) {
        stackDir(projectId, stack).listFiles()?.forEach { it.delete() }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
        if (files.size > maxEntries) {
            files.take(files.size - maxEntries).forEach { it.delete() }
        }
    }
}

@Serializable
data class CheckpointMeta(
    val id: String,
    val reason: String,
    val actor: String,
    val at: Long,
)

/**
 * CP-71 checkpoints (§88): named full snapshots under
 * `<projectId>/checkpoints/`, cap 10. Written before AI jobs, renders and
 * exports; [recover] restores the latest (or a chosen) one after a crash.
 */
class FileCheckpointStore(root: File) {
    private val rootDir = root
    val maxEntries: Int = 10

    fun save(projectId: String, reason: String, actor: String, snapshot: ProjectSnapshot): CheckpointMeta {
        val dir = File(rootDir, "$projectId/checkpoints").also { it.mkdirs() }
        var id = "${System.currentTimeMillis()}-$reason"
        var n = 2
        while (File(dir, "$id.json").exists()) {
            id = "${System.currentTimeMillis()}-$reason-$n"
            n += 1
        }
        val meta = CheckpointMeta(id, reason, actor, System.currentTimeMillis())
        File(dir, "$id.json").writeText(activityJson.encodeToString(snapshot))
        trim(dir)
        return meta
    }

    /** Newest first. */
    fun list(projectId: String): List<CheckpointMeta> =
        File(rootDir, "$projectId/checkpoints").listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .sortedByDescending { it.name }
            .mapNotNull { file ->
                val parts = file.nameWithoutExtension.split("-", limit = 2)
                val at = parts.getOrNull(0)?.toLongOrNull() ?: 0
                CheckpointMeta(file.nameWithoutExtension, parts.getOrNull(1) ?: "", "", at)
            }

    fun recover(projectId: String, id: String?): Outcome<ProjectSnapshot> {
        val dir = File(rootDir, "$projectId/checkpoints")
        val file = if (id == null) {
            dir.listFiles { f -> f.isFile && f.extension == "json" }
                .orEmpty()
                .maxByOrNull { it.name }
        } else {
            File(dir, "$id.json").takeIf { it.isFile }
        } ?: return Outcome.Failure(AppError("MEDIA_NO_CHECKPOINT", "ไม่มีเช็คพอยต์ให้กู้"))
        return try {
            Outcome.Success(activityJson.decodeFromString<ProjectSnapshot>(file.readText()))
        } catch (e: Exception) {
            Outcome.Failure(AppError("MEDIA_CHECKPOINT", "อ่านเช็คพอยต์ไม่ได้: ${e.message}"))
        }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
        if (files.size > maxEntries) {
            files.take(files.size - maxEntries).forEach { it.delete() }
        }
    }
}
