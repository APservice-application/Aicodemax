package com.aicodemax.data.skills

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.ZipArchive
import com.aicodemax.core.common.runOutcome
import java.io.File

/**
 * CP-58: skill files (ported from the previous app's SkillManager + AMENDMENT-002).
 * Skills are text knowledge (.md/.txt, ≤2MB each, .zip bundles) injected into AI
 * context as `=== SKILL: <id> ===` blocks. Binary files are rejected honestly.
 */
data class SkillMeta(
    val id: String,
    val category: String,
    val sizeBytes: Long,
    val builtin: Boolean,
)

data class Skill(
    val meta: SkillMeta,
    val content: String,
)

object SkillValidator {
    val ID_REGEX = Regex("[a-z0-9_-]{1,40}")
    const val MAX_FILE_BYTES = 2L * 1024 * 1024
    val TEXT_EXTENSIONS = setOf("md", "txt", "markdown")

    fun slugify(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .take(40)

    fun isText(bytes: ByteArray): Boolean = !bytes.contains(0.toByte())
}

interface SkillStore {
    fun list(): Outcome<List<SkillMeta>>
    fun get(id: String): Outcome<Skill>
    fun install(source: File): Outcome<List<SkillMeta>>
    fun remove(id: String): Outcome<Unit>
}

/** File-backed skill store: `<dir>/index.json` + one .md file per skill id. */
class FileSkillStore(private val dir: File) : SkillStore {
    init {
        dir.mkdirs()
    }

    override fun list(): Outcome<List<SkillMeta>> = runOutcome("SKILL_LIST") {
        BuiltinSkills.all.map { it.meta } + readIndex()
    }

    override fun get(id: String): Outcome<Skill> {
        BuiltinSkills.all.firstOrNull { it.meta.id == id }?.let { return Outcome.Success(it) }
        if (!SkillValidator.ID_REGEX.matches(id)) {
            return Outcome.Failure(AppError("SKILL_BAD_ID", "bad skill id '$id'"))
        }
        val file = skillFile(id)
        if (!file.isFile) return Outcome.Failure(AppError("SKILL_MISSING", "no skill '$id'"))
        return Outcome.Success(
            Skill(SkillMeta(id, categoryOf(id), file.length(), false), file.readText()),
        )
    }

    override fun install(source: File): Outcome<List<SkillMeta>> {
        if (!source.isFile) {
            return Outcome.Failure(AppError("SKILL_NO_FILE", "file not found: ${source.name}"))
        }
        val ext = source.extension.lowercase()
        if (ext == "zip") return installZip(source)
        if (ext !in SkillValidator.TEXT_EXTENSIONS) {
            return Outcome.Failure(
                AppError("SKILL_BAD_TYPE", "รับเฉพาะ .md/.txt/.zip — '${source.name}' แปลงเป็นสกิลไม่ได้"),
            )
        }
        if (source.length() > SkillValidator.MAX_FILE_BYTES) {
            return Outcome.Failure(AppError("SKILL_TOO_BIG", "'${source.name}' เกิน 2MB"))
        }
        val bytes = source.readBytes()
        if (!SkillValidator.isText(bytes)) {
            return Outcome.Failure(
                AppError("SKILL_BINARY", "ไฟล์นี้เป็นไบนารี — แปลงเป็นสกิลไม่ได้ (รับไฟล์ข้อความเท่านั้น)"),
            )
        }
        val id = SkillValidator.slugify(source.nameWithoutExtension)
        if (id.isBlank() || !SkillValidator.ID_REGEX.matches(id)) {
            return Outcome.Failure(AppError("SKILL_BAD_ID", "ชื่อไฟล์ใช้เป็น id ไม่ได้: ${source.name}"))
        }
        if (BuiltinSkills.all.any { it.meta.id == id }) {
            return Outcome.Failure(AppError("SKILL_RESERVED", "id '$id' สงวนไว้ (built-in)"))
        }
        skillFile(id).writeBytes(bytes)
        return Outcome.Success(listOf(SkillMeta(id, "general", bytes.size.toLong(), false)))
    }

    override fun remove(id: String): Outcome<Unit> {
        if (BuiltinSkills.all.any { it.meta.id == id }) {
            return Outcome.Failure(AppError("SKILL_BUILTIN", "ลบ built-in skill '$id' ไม่ได้"))
        }
        if (!SkillValidator.ID_REGEX.matches(id)) {
            return Outcome.Failure(AppError("SKILL_BAD_ID", "bad skill id '$id'"))
        }
        val deleted = skillFile(id).delete()
        if (!deleted) return Outcome.Failure(AppError("SKILL_MISSING", "no skill '$id'"))
        return Outcome.Success(Unit)
    }

    private fun installZip(zip: File): Outcome<List<SkillMeta>> {
        val staging = File(dir, ".staging-${System.currentTimeMillis()}")
        staging.mkdirs()
        try {
            val unzipped = ZipArchive.unzip(zip, staging)
            if (unzipped is Outcome.Failure) return unzipped
            val installed = mutableListOf<SkillMeta>()
            staging.walkTopDown().filter { it.isFile }.forEach { file ->
                val ext = file.extension.lowercase()
                if (ext !in SkillValidator.TEXT_EXTENSIONS) return@forEach
                if (file.length() > SkillValidator.MAX_FILE_BYTES) return@forEach
                val bytes = file.readBytes()
                if (!SkillValidator.isText(bytes)) return@forEach
                val id = SkillValidator.slugify(file.nameWithoutExtension)
                if (id.isBlank() || !SkillValidator.ID_REGEX.matches(id)) return@forEach
                if (BuiltinSkills.all.any { it.meta.id == id }) return@forEach
                skillFile(id).writeBytes(bytes)
                installed.add(SkillMeta(id, "general", bytes.size.toLong(), false))
            }
            if (installed.isEmpty()) {
                return Outcome.Failure(
                    AppError("SKILL_ZIP_EMPTY", "zip นี้ไม่มีไฟล์ .md/.txt ที่ใช้ได้"),
                )
            }
            return Outcome.Success(installed)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun skillFile(id: String) = File(dir, "$id.md")

    private fun readIndex(): List<SkillMeta> =
        dir.listFiles { file -> file.isFile && file.extension == "md" }
            .orEmpty()
            .mapNotNull { file ->
                val id = file.nameWithoutExtension
                if (!SkillValidator.ID_REGEX.matches(id)) return@mapNotNull null
                SkillMeta(id, categoryOf(id), file.length(), false)
            }
            .sortedBy { it.id }

    private fun categoryOf(id: String): String = "general"
}

/** Context injection format (matches the previous app's `=== SKILL:` convention). */
object SkillInjector {
    fun inject(skills: List<Skill>): String =
        skills.joinToString("\n\n") { skill ->
            "=== SKILL: ${skill.meta.id} ===\n${skill.content.trim()}"
        }

    fun injectIds(ids: List<String>, store: SkillStore): Outcome<String> =
        runOutcome("SKILL_INJECT") {
            val missing = mutableListOf<String>()
            val found = ids.mapNotNull { id ->
                when (val got = store.get(id)) {
                    is Outcome.Success -> got.value
                    is Outcome.Failure -> {
                        missing.add(id)
                        null
                    }
                }
            }
            if (found.isEmpty()) {
                throw IllegalStateException("no matching skills: ${ids.joinToString(",")}")
            }
            val header = if (missing.isNotEmpty()) {
                "(skills not found, skipped: ${missing.joinToString(", ")})\n\n"
            } else {
                ""
            }
            header + inject(found)
        }
}
