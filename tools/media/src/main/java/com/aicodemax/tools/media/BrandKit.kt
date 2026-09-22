package com.aicodemax.tools.media

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** CP-100: brand kit — name + color + logo + tagline. */
@Serializable
data class BrandKit(
    val id: String,
    val name: String,
    val colorHex: String = "#FFFFFF",
    val logoPath: String = "",
    val tagline: String = "",
    val createdAt: Long = 0,
) {
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("ชื่อแบรนด์ว่างไม่ได้")
        if (colorHex.isNotBlank() && !Regex("#?[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?").matches(colorHex.trim())) {
            add("สีต้องเป็น #RRGGBB (ได้ $colorHex)")
        }
    }

    /** ARGB Long for [com.aicodemax.data.media.OverlayText.color] (white fallback). */
    fun argb(): Long = try {
        val hex = colorHex.trim().removePrefix("#")
        when (hex.length) {
            6 -> (0xFF000000L or hex.toLong(16))
            8 -> hex.toLong(16)
            else -> 0xFFFFFFFFL
        }
    } catch (_: Exception) {
        0xFFFFFFFFL
    }

    fun summary(): String = "$name ($colorHex)${if (tagline.isNotBlank()) " — $tagline" else ""}"
}

/** CP-100: file-backed brand store (brands/<id>.json). */
class FileBrandStore(root: File, private val clock: Clock = SystemClock) {
    private val dir = File(root, "brands")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun save(name: String, colorHex: String, logoPath: String, tagline: String): Outcome<BrandKit> {
        val kit = BrandKit(Ids.newId("brand"), name.trim(), colorHex.trim().ifBlank { "#FFFFFF" }, logoPath.trim(), tagline.trim(), clock.nowMillis())
        val problems = kit.validate()
        if (problems.isNotEmpty()) return Outcome.Failure(AppError("BRAND_BAD", problems.joinToString("; ")))
        return try {
            dir.mkdirs()
            File(dir, "${kit.id}.json").writeText(json.encodeToString(BrandKit.serializer(), kit))
            Outcome.Success(kit)
        } catch (e: Exception) {
            Outcome.Failure(AppError("BRAND_SAVE", "บันทึกแบรนด์ไม่ได้: ${e.message}"))
        }
    }

    fun list(): Outcome<List<BrandKit>> = try {
        dir.mkdirs()
        Outcome.Success(
            dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull {
                try {
                    json.decodeFromString(BrandKit.serializer(), it.readText())
                } catch (_: Exception) {
                    null
                }
            }.sortedByDescending { it.createdAt },
        )
    } catch (e: Exception) {
        Outcome.Failure(AppError("BRAND_LIST", "list แบรนด์ไม่ได้: ${e.message}"))
    }

    fun get(brandId: String): Outcome<BrandKit> {
        val file = File(dir, "$brandId.json")
        if (!file.isFile) return Outcome.Failure(AppError("BRAND_MISSING", "ไม่มีแบรนด์ $brandId"))
        return try {
            Outcome.Success(json.decodeFromString(BrandKit.serializer(), file.readText()))
        } catch (e: Exception) {
            Outcome.Failure(AppError("BRAND_READ", "อ่านแบรนด์ไม่ได้: ${e.message}"))
        }
    }
}
