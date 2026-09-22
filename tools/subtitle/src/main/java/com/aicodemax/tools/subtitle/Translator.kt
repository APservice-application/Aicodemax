package com.aicodemax.tools.subtitle

/**
 * CP-98: honest offline translator (embedded Thai↔English phrase dictionary).
 * Covers common creator lines; unknown segments pass through and are counted,
 * so callers can report coverage %. Full MT needs a model (roadmap §29 Cloud).
 */
object Translator {
    val PAIRS: List<Pair<String, String>> = listOf(
        "สวัสดีครับ" to "Hello",
        "สวัสดีค่ะ" to "Hello",
        "สวัสดีทุกคน" to "Hello everyone",
        "ยินดีต้อนรับ" to "Welcome",
        "ขอบคุณครับ" to "Thank you",
        "ขอบคุณค่ะ" to "Thank you",
        "ขอบคุณที่รับชม" to "Thanks for watching",
        "ฝากกดติดตาม" to "Please subscribe",
        "กดไลก์ กดแชร์" to "Like and share",
        "กดกระดิ่ง" to "Hit the bell",
        "เจอกันคลิปหน้า" to "See you in the next video",
        "วันนี้เรามา" to "Today we are going to",
        "ไปดูกันเลย" to "Let's take a look",
        "เริ่มกันเลย" to "Let's get started",
        "สรุป" to "In summary",
        "ข้อดี" to "Pros",
        "ข้อเสีย" to "Cons",
        "ราคา" to "Price",
        "คุ้มค่า" to "Worth it",
        "ไม่ควรพลาด" to "Don't miss it",
        "ทักแชทเลย" to "Message us now",
        "สนใจสั่งซื้อ" to "Interested in ordering",
        "โปรโมชัน" to "Promotion",
        "ส่วนลด" to "Discount",
        "จำนวนจำกัด" to "Limited quantity",
        "อร่อยมาก" to "Very delicious",
        "สวยมาก" to "Very beautiful",
        "ดีมาก" to "Very good",
        "ง่ายมาก" to "Very easy",
        "สนุกมาก" to "So much fun",
        "ตอนนี้" to "Right now",
        "ต่อไป" to "Next",
        "สุดท้าย" to "Finally",
        "อย่าลืม" to "Don't forget",
        "คอมเมนต์เลย" to "Comment below",
        "แชร์ให้เพื่อน" to "Share with friends",
        "ดูให้จบ" to "Watch till the end",
        "พลาดไม่ได้" to "You can't miss this",
        "เปิดตัว" to "Introducing",
        "รีวิว" to "Review",
        "สอนทำ" to "How to make",
        "วิธีใช้" to "How to use",
        "คำเตือน" to "Warning",
        "โปรดอ่าน" to "Please read",
    )

    data class Translation(val text: String, val hits: Int, val total: Int) {
        val coveragePct: Int get() = if (total == 0) 100 else (hits * 100 / total)
    }

    /**
     * Translates [text] ("th-en" or "en-th"). Segments split on sentence
     * punctuation; longest dictionary match wins per segment.
     */
    fun translate(text: String, direction: String): Translation {
        val dir = direction.lowercase()
        if (dir != "th-en" && dir != "en-th") {
            throw IllegalArgumentException("รองรับแค่ th-en/th-en กลับกัน (พจนานุกรมในตัว)")
        }
        val dict = if (dir == "th-en") PAIRS else PAIRS.map { (a, b) -> b to a }
        val byFirst = dict.sortedByDescending { it.first.length }
        // Thai has no spaces: match greedily left-to-right, longest first.
        val out = StringBuilder()
        var i = 0
        var hits = 0
        var total = 0
        val src = text
        while (i < src.length) {
            if (src[i].isWhitespace()) {
                out.append(src[i])
                i++
                continue
            }
            total++
            var matched: Pair<String, String>? = null
            for (pair in byFirst) {
                if (src.startsWith(pair.first, i, ignoreCase = dir == "en-th")) {
                    matched = pair
                    break
                }
            }
            if (matched != null) {
                out.append(matched.second)
                i += matched.first.length
                hits++
            } else {
                // Pass one word/char through.
                var j = i + 1
                while (j < src.length && !src[j].isWhitespace()) j++
                // For Thai, unknown runs pass whole (no word boundaries).
                out.append(src.substring(i, j))
                i = j
            }
        }
        return Translation(out.toString(), hits, total)
    }
}
