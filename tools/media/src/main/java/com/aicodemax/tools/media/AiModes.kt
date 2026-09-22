package com.aicodemax.tools.media

/**
 * CP-96: 5 AI content modes (Mode A). Structured Thai production plans
 * (shots + beats + caption + CTA) from topic + platform. Rule templates
 * today; LLM-backed variants plug in here (same tool/output).
 */
object AiModes {
    val MODES = listOf("commercial", "story", "vlog", "tutorial", "review")

    fun thaiName(mode: String): String = when (mode.lowercase()) {
        "commercial" -> "โฆษณา"
        "story" -> "เล่าเรื่อง"
        "vlog" -> "วล็อก"
        "tutorial" -> "สอนทำ"
        "review" -> "รีวิว"
        else -> mode
    }

    fun plan(mode: String, topic: String, platform: String? = null): String {
        val t = topic.ifBlank { "หัวข้อนี้" }
        val short = (platform?.lowercase() ?: "") in setOf("tiktok", "reels", "shorts")
        val beats: List<String> = when (mode.lowercase()) {
            "commercial" -> listOf(
                "ฮุก 0-3วิ: ปัญหาชัดหนึ่งประโยค + ภาพก่อนใช้",
                "สาธิต: ใช้${t}จริง เห็นผลในคลิปเดียว",
                "หลักฐาน: ตัวเลข/รีวิวสั้น 1 ช็อต",
                "CTA: โปร+ช่องทางสั่งซื้อ จบด้วยโลโก้",
            )
            "story" -> listOf(
                "เปิดเรื่อง: ตัวละคร+เป้าหมายใน 5วิ",
                "ปมขัดแย้ง: อุปสรรคของ${t}",
                "จุดเปลี่ยน: วิธีแก้/บทเรียน",
                "ปิดเรื่อง: สรุปคมๆ + ชวนติดตาม",
            )
            "vlog" -> listOf(
                "เปิดวล็อก: ทักทาย+บอกแผนวันนี้เรื่อง${t}",
                "กิจกรรมหลัก 2-3 ช็อต (B-roll แทรก)",
                "โมเมนต์จริง: ความรู้สึก/เรื่องเซอร์ไพรส์",
                "ปิดท้าย: สรุป+ถามคนดู+CTA",
            )
            "tutorial" -> listOf(
                "บอกผลลัพธ์: ดูจบทำ${t}ได้ + เวลาที่ใช้",
                "ขั้นตอน 3-5 ขั้น (ทีละขั้น เห็นชัด)",
                "ทริก/ข้อผิดพลาดที่พบบ่อย",
                "สรุปเช็กลิสต์ + ชวนกดเซฟ/แชร์",
            )
            "review" -> listOf(
                "เกริ่น: ${t}คืออะไร + ให้คะแนนก่อน (สปอยล์เบาๆ)",
                "ข้อดี 3 ข้อ (ภาพประกอบจริง)",
                "ข้อเสีย/ข้อควรรู้ 2 ข้อ",
                "สรุป: เหมาะกับใคร + CTA",
            )
            else -> throw IllegalArgumentException("โหมดไม่รู้จัก $mode (มี ${MODES.joinToString("/")})")
        }
        val caption = if (short) "$t ฉบับจบในคลิปเดียว เซฟไว้เลย!" else "สรุป$t ตั้งแต่ต้นจนจบ ดูจบทำตามได้จริง"
        return buildString {
            appendLine("โหมด${thaiName(mode)}: $t")
            beats.forEachIndexed { i, b -> appendLine("${i + 1}. $b") }
            appendLine("แคปชัน: $caption")
            append("CTA: สนใจทักแชท / กดติดตามไว้ไม่พลาดคลิปหน้า")
        }
    }
}
