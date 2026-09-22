package com.aicodemax.tools.media

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.data.media.Clip
import com.aicodemax.data.media.ClipFx
import com.aicodemax.data.media.ClipTransition
import com.aicodemax.data.media.LibraryItem
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.OverlayText
import com.aicodemax.data.media.ProjectTemplate
import com.aicodemax.data.media.TemplateSlot
import com.aicodemax.data.media.Timeline
import com.aicodemax.data.media.Track
import java.io.File
import kotlinx.serialization.json.Json

/** CP-82 §51/§52: built-in starter templates (offline, always available). */
object BuiltinTemplates {
    fun list(): List<ProjectTemplate> = listOf(
        ProjectTemplate(
            id = "builtin-social-hook",
            name = "Social Hook",
            category = "social",
            description = "เปิดด้วยฮุค + เฟดท้าย (แนวตั้งโซเชียล)",
            timeline = Timeline(
                tracks = listOf(
                    Track(
                        "V1", MediaKind.VIDEO,
                        listOf(
                            Clip(
                                "c-main", ProjectTemplate.placeholderAsset("main"),
                                0, 5000, 0, transitionOut = ClipTransition("fade", 400),
                            ),
                        ),
                    ),
                ),
                texts = listOf(
                    OverlayText("t-hook", "อย่าเลื่อนผ่าน!", 0, 2500, yPct = 18, sizePct = 9, bold = true),
                ),
            ),
            slots = listOf(TemplateSlot("main", "c-main", MediaKind.VIDEO, "คลิปหลัก")),
        ),
        ProjectTemplate(
            id = "builtin-cinematic-opener",
            name = "Cinematic Opener",
            category = "vlog",
            description = "เปิดเรื่องสไตล์หนัง + ไตเติล + วิกเน็ต",
            timeline = Timeline(
                tracks = listOf(
                    Track(
                        "V1", MediaKind.VIDEO,
                        listOf(
                            Clip(
                                "c-main", ProjectTemplate.placeholderAsset("main"),
                                0, 6000, 0,
                                transitionIn = ClipTransition("fade", 800),
                                fx = ClipFx(vignette = 40),
                            ),
                        ),
                    ),
                ),
                texts = listOf(
                    OverlayText("t-title", "MY STORY", 500, 4500, yPct = 78, sizePct = 8, bold = true),
                ),
            ),
            slots = listOf(TemplateSlot("main", "c-main", MediaKind.VIDEO, "คลิปหลัก")),
        ),
    )
}

/** CP-82: JSON template store (templates/<id>.json), seeds built-ins once. */
class FileTemplateStore(root: File, private val clock: Clock = SystemClock) {
    private val dir = File(root, "templates")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun ensureSeed() {
        if (!dir.isDirectory || (dir.listFiles()?.isEmpty() != false)) {
            dir.mkdirs()
            val now = clock.nowMillis()
            for (b in BuiltinTemplates.list()) {
                val f = File(dir, "${b.id}.json")
                if (!f.isFile) f.writeText(json.encodeToString(ProjectTemplate.serializer(), b.copy(createdAt = now, updatedAt = now)))
            }
        }
    }

    fun list(): Outcome<List<ProjectTemplate>> {
        return try {
        ensureSeed()
        Outcome.Success(
            dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull {
                try {
                    json.decodeFromString(ProjectTemplate.serializer(), it.readText())
                } catch (_: Exception) {
                    null
                }
            }.sortedByDescending { it.updatedAt },
        )
    } catch (e: Exception) {
        Outcome.Failure(AppError("TEMPLATE_LIST", "list เทมเพลตไม่ได้: ${e.message}"))
    }
    }
    fun get(id: String): Outcome<ProjectTemplate> {
        ensureSeed()
        val f = File(dir, "$id.json")
        if (!f.isFile) return Outcome.Failure(AppError("TEMPLATE_MISSING", "ไม่มีเทมเพลต $id"))
        return try {
            Outcome.Success(json.decodeFromString(ProjectTemplate.serializer(), f.readText()))
        } catch (e: Exception) {
            Outcome.Failure(AppError("TEMPLATE_OPEN", "เปิดเทมเพลตไม่ได้: ${e.message}"))
        }
    }

    fun save(template: ProjectTemplate): Outcome<ProjectTemplate> {
        return try {
        ensureSeed()
        val problems = template.validate()
        if (problems.isNotEmpty()) {
            return Outcome.Failure(AppError("TEMPLATE_INVALID", problems.joinToString("; ")))
        }
        val now = clock.nowMillis()
        val stamped = template.copy(
            id = template.id.ifBlank { Ids.newId("tpl") },
            createdAt = template.createdAt.takeUnless { it == 0L } ?: now,
            updatedAt = now,
        )
        File(dir, "${stamped.id}.json").writeText(json.encodeToString(ProjectTemplate.serializer(), stamped))
        Outcome.Success(stamped)
    } catch (e: Exception) {
        Outcome.Failure(AppError("TEMPLATE_SAVE", "บันทึกเทมเพลตไม่ได้: ${e.message}"))
    }
    }
    fun delete(id: String): Outcome<Unit> {
        return try {
        ensureSeed()
        if (id.startsWith("builtin-")) {
            return Outcome.Failure(AppError("TEMPLATE_BUILTIN", "เทมเพลตตั้งต้นลบไม่ได้"))
        }
        val f = File(dir, "$id.json")
        if (!f.isFile) return Outcome.Failure(AppError("TEMPLATE_MISSING", "ไม่มีเทมเพลต $id"))
        f.delete()
        Outcome.Success(Unit)
    } catch (e: Exception) {
        Outcome.Failure(AppError("TEMPLATE_DELETE", "ลบเทมเพลตไม่ได้: ${e.message}"))
    }
    }
}

/** CP-82 §53: JSON asset-library store (library/<id>.json). */
class FileLibraryStore(root: File, private val clock: Clock = SystemClock) {
    private val dir = File(root, "library")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun list(): Outcome<List<LibraryItem>> {
        return try {
        dir.mkdirs()
        Outcome.Success(
            dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull {
                try {
                    json.decodeFromString(LibraryItem.serializer(), it.readText())
                } catch (_: Exception) {
                    null
                }
            }.sortedByDescending { it.createdAt },
        )
    } catch (e: Exception) {
        Outcome.Failure(AppError("LIB_LIST", "list คลังไม่ได้: ${e.message}"))
    }
    }
    fun add(item: LibraryItem): Outcome<LibraryItem> {
        return try {
        dir.mkdirs()
        val problems = item.validate()
        if (problems.isNotEmpty()) return Outcome.Failure(AppError("LIB_INVALID", problems.joinToString("; ")))
        val stamped = item.copy(
            id = item.id.ifBlank { Ids.newId("lib") },
            createdAt = item.createdAt.takeUnless { it == 0L } ?: clock.nowMillis(),
        )
        File(dir, "${stamped.id}.json").writeText(json.encodeToString(LibraryItem.serializer(), stamped))
        Outcome.Success(stamped)
    } catch (e: Exception) {
        Outcome.Failure(AppError("LIB_ADD", "เพิ่มเข้าคลังไม่ได้: ${e.message}"))
    }
    }
    fun remove(id: String): Outcome<Unit> {
        return try {
        val f = File(dir, "$id.json")
        if (!f.isFile) return Outcome.Failure(AppError("LIB_MISSING", "ไม่มี asset $id"))
        f.delete()
        Outcome.Success(Unit)
    } catch (e: Exception) {
        Outcome.Failure(AppError("LIB_REMOVE", "ลบออกจากคลังไม่ได้: ${e.message}"))
    }
    }
}
