package com.aicodemax.tools.media

/**
 * CP-74 AI text starter (§23): template ideas for title/hook/caption/CTA/
 * description from a topic + platform. Rule templates today; LLM-backed
 * variants plug in here when a provider is connected (same tool/output).
 */
object TextIdeas {
    fun kinds(): List<String> = listOf("title", "hook", "caption", "cta", "description")

    fun ideas(kind: String, topic: String, platform: String? = null): List<String> {
        val t = topic.ifBlank { "วิดีโอนี้" }
        val plat = platform?.lowercase() ?: ""
        val short = plat in setOf("tiktok", "reels", "shorts")
        return when (kind.lowercase()) {
            "title" -> listOf(
                "$t ใน ${if (short) "30 วินาที" else "3 นาที"}",
                "ทำไมทุกคนพูดถึง$t",
                "$t ที่คุณต้องรู้",
            )
            "hook" -> listOf(
                "หยุดเลื่อน! $t เปลี่ยนทุกอย่าง",
                "90% ไม่รู้เรื่อง${t}นี้",
                "ดูให้จบแล้วจะเข้าใจ$t",
            )
            "caption" -> listOf(
                "$t ✨ ใครเคยลองแล้วบ้าง คอมเมนต์เลย!",
                "สรุป${t}แบบเข้าใจง่าย เซฟไว้ดูทีหลังได้เลย",
                "$t — ฉบับคนไม่มีเวลา ดูจบในคลิปเดียว",
            )
            "cta" -> listOf(
                "สนใจ$t? ทักแชทเลย!",
                "กดติดตามไว้ ไม่พลาดเรื่อง$t",
                "แชร์ให้เพื่อนที่ต้องการ$t",
            )
            "description" -> listOf(
                "วิดีโอนี้เล่าเรื่อง$t ตั้งแต่ต้นจนจบ พร้อมทริกทำตามได้จริง",
                "00:00 เปิดเรื่อง — เนื้อหา${t}แบบเจาะลึก — ท้ายคลิปมีสรุปและชวนติดตาม",
            )
            else -> listOf("$t")
        }
    }
}
