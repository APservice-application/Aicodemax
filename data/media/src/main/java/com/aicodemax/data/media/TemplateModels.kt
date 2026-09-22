package com.aicodemax.data.media

import kotlinx.serialization.Serializable

/** CP-82 §52 template categories. */
object TemplateCategories {
    val ALL = listOf(
        "social", "business", "food", "travel", "vlog", "gaming",
        "advertisement", "education", "birthday", "family", "product",
        "marketing", "custom",
    )
}

/** CP-82 §53 searchable asset-library kinds. */
object AssetKinds {
    val ALL = listOf(
        "effect", "filter", "transition", "sticker", "font", "music",
        "sfx", "template", "lut", "overlay", "background", "aiasset",
    )
}

/**
 * CP-82 §51: one replaceable media slot in a template.
 * @param id slot id used in replacements (e.g. "main").
 * @param clipId id of the placeholder clip inside [ProjectTemplate.timeline].
 * @param kind expected media kind for the replacement.
 */
@Serializable
data class TemplateSlot(
    val id: String,
    val clipId: String,
    val kind: MediaKind,
    val label: String = "",
) {
    fun validate(): List<String> = buildList {
        if (id.isBlank()) add("slot id ว่างไม่ได้")
        if (clipId.isBlank()) add("slot clip ว่างไม่ได้")
    }
}

/**
 * CP-82 §51: a template IS a project graph (timeline + placeholders).
 * Placeholder clips use assetId "slot:<slotId>" so templates stay portable.
 */
@Serializable
data class ProjectTemplate(
    val id: String,
    val name: String,
    val category: String,
    val description: String = "",
    val timeline: Timeline,
    val slots: List<TemplateSlot> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("ชื่อเทมเพลตว่างไม่ได้")
        if (category !in TemplateCategories.ALL) add("หมวดไม่รู้จัก ($category) ใช้ ${TemplateCategories.ALL.joinToString("/")}")
        val clipIds = timeline.tracks.flatMap { it.clips }.map { it.id }.toSet()
        val slotIds = mutableSetOf<String>()
        for (s in slots) {
            addAll(s.validate().map { "slot ${s.id}: $it" })
            if (!slotIds.add(s.id)) add("slot ซ้ำ (${s.id})")
            if (s.clipId !in clipIds) add("slot ${s.id} อ้างคลิปที่ไม่มี (${s.clipId})")
        }
        val selfAssets = timeline.tracks.flatMap { it.clips }.map { it.assetId }.toSet()
        addAll(timeline.validate(selfAssets).map { "ไทม์ไลน์: $it" })
    }

    fun summary(): String {
        val clips = timeline.tracks.sumOf { it.clips.size }
        return "$name [$category] คลิป$clips ข้อความ${timeline.texts.size} ช่อง${slots.size}"
    }

    companion object {
        fun placeholderAsset(slotId: String): String = "slot:$slotId"
        fun slotOf(assetId: String): String? = assetId.removePrefix("slot:").takeIf { assetId.startsWith("slot:") }
    }
}

/** CP-82 §53: one searchable library entry. ref = path, preset name, or template id. */
@Serializable
data class LibraryItem(
    val id: String,
    val kind: String,
    val name: String,
    val tags: List<String> = emptyList(),
    val ref: String = "",
    val createdAt: Long = 0,
) {
    fun validate(): List<String> = buildList {
        if (kind !in AssetKinds.ALL) add("kind ไม่รู้จัก ($kind) ใช้ ${AssetKinds.ALL.joinToString("/")}")
        if (name.isBlank()) add("ชื่อ asset ว่างไม่ได้")
    }

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return name.lowercase().contains(q) || tags.any { it.lowercase().contains(q) } ||
            kind.lowercase().contains(q) || ref.lowercase().contains(q)
    }

    fun summary(): String = "$name [$kind]${if (tags.isNotEmpty()) " #${tags.joinToString(" #")}" else ""}"
}
