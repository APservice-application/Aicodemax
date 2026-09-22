package com.aicodemax.ai.core

/**
 * CP-57: full 155-word Thai vocabulary ported from the previous app's SmartIntentRouter.
 * Single owner — [IntentParser] uses these lists (longest-first matching).
 * Words that map to engines we don't have yet are parsed but answered honestly
 * (planner-level "coming in CP-xx", never fake execution).
 */
object ThaiVocabulary {
    /** Polite request openers stripped from the front (≥2 chars must remain). */
    val politePrefixes: List<String> = listOf(
        "ฉันอยากให้", "ฉันต้องการ", "ผมอยากให้", "ผมต้องการ", "เราอยากให้",
        "ช่วยหน่อย", "ช่วยด้วย", "ให้หน่อยครับ", "ให้หน่อยค่ะ", "ฉันอยาก", "ผมอยาก",
        "เราอยาก", "อยากให้", "อยากได้", "รบกวน", "กรุณา", "ช่วย", "โปรด",
        "กูขอ", "กุขอ", "อยาก", "ขอ",
    ).sortedByDescending { it.length }

    /** Polite particles stripped from the end (≥2 chars must remain). */
    val politeSuffixes: List<String> = listOf(
        "หน่อยครับ", "หน่อยค่ะ", "หน่อยคับ", "ให้หน่อยครับ", "ให้หน่อยค่ะ",
        "ให้หน่อยสิ", "ได้ไหมครับ", "ได้ไหมค่ะ", "ได้เลยไหม", "หน่อยนะ",
        "หน่อยสิ", "หน่อยเถอะ", "สักหน่อย", "ให้หน่อย", "ให้ด้วย", "ด้วยครับ",
        "ด้วยค่ะ", "ด้วยเลย", "นะครับ", "นะค่ะ", "ได้ไหม", "ได้มั้ย", "หน่อย",
        "ให้ที", "ด้วย", "ครับ", "ค่ะ", "คับ", "จ้า", "จ๊ะ", "ไหม", "มั้ย",
        "นะ", "สิ", "ที",
    ).sortedByDescending { it.length }

    /** Filler/noise words stripped from the FRONT (longest-first). */
    val leadingFillers: List<String> = listOf(
        "ในแอปพลิเคชัน", "ในแอปพลิเคชั่น", "ในแอปนี้", "ในโทรศัพท์", "ในมือถือ",
        "ในเครื่อง", "ที่แอปนี้", "ในแอปฯ", "กำลังจะ", "คือว่า", "ในเอป",
        "ในแอป", "สวัสดี", "หวัดดี", "เถอะ", "แล้ว", "เอ่อ", "อ้อ",
        "คือ", "ว่า", "จะ", "ก่อน", "ที่", "ให้",
    ).sortedByDescending { it.length }

    /**
     * Fillers safe to strip from the END — scope qualifiers only.
     * Greetings/content-like words are NEVER stripped trailing (they may be the payload,
     * e.g. content "สวัสดี" in สร้างไฟล์).
     */
    val trailingFillers: List<String> = listOf(
        "ในแอปพลิเคชัน", "ในแอปพลิเคชั่น", "ในแอปนี้", "ในโทรศัพท์", "ในมือถือ",
        "ในเครื่อง", "ที่แอปนี้", "ในแอปฯ", "ในเอป", "ในแอป",
    ).sortedByDescending { it.length }

    /** Stop commands — matched anywhere (no bare หยุด: avoids อย่าหยุด false hits). */
    val stopWords: List<String> = listOf(
        "สั่งหยุด", "หยุดทำงาน", "หยุดเลย", "หยุดเอไอ", "หยุดงาน", "เลิกงาน",
        "เลิกทำ", "เลิกเลย", "หยุด ai",
    ).sortedByDescending { it.length }

    /** Media-edit verbs. */
    val mediaWords: List<String> = listOf("ตัดคลิป", "ตัดต่อ")

    /** Share/publish verbs — parsed now, share sheet lands with export UI (CP-67). */
    val shareWords: List<String> = listOf(
        "โพสต์ลง", "โพสลง", "ลงประกาศ", "โพสต์", "โพส", "แชร์",
    ).sortedByDescending { it.length }

    /** Platform targets carried as intent params (blueprint §1). */
    val platforms: Map<String, String> = mapOf(
        "ติ๊กต็อก" to "tiktok",
        "ยูทูบ" to "youtube",
        "ยูทูป" to "youtube",
        "เฟสบุ๊ค" to "facebook",
        "เฟสบุค" to "facebook",
        "แฟนเพจ" to "facebook",
        "เฟส" to "facebook",
        "ไอจี" to "instagram",
        "ไลน์" to "line",
        "ทวิตเตอร์" to "twitter",
        "ทวิต" to "twitter",
        "เมสเซนเจอร์" to "messenger",
    )

    /** Debug verbs. */
    val debugWords: List<String> = listOf("แก้บั๊ก", "แก้ bug", "ดีบั๊ก")

    /** Search verbs. */
    val searchWords: List<String> = listOf("ค้นหา")

    /** Test verbs (extra). */
    val testWords: List<String> = listOf("เทส")

    /** Status verbs. */
    val statusWords: List<String> = listOf("สถานะระบบ", "สถานะ")

    /** Settings verbs. */
    val settingsWords: List<String> = listOf("ตั้งค่า")

    /** Memory verbs. */
    val memorySaveWords: List<String> = listOf("บันทึก", "จำไว้")
    val memoryRecallWords: List<String> = listOf("ความจำ")

    /** Browser verbs. */
    val browserOpenWords: List<String> = listOf("เปิดเว็บ", "เปิดดู", "เปิด")
    val browserCloseWords: List<String> = listOf("ปิดแท็บ", "ปิดหน้า", "ปิดเว็บ", "ปิด")
    val browserListWords: List<String> = listOf("แท็บ", "เบราว์", "หน้าเว็บ")

    /** Terminal words (map to RUN_COMMAND; capability honestly BLOCKED until CP-32). */
    val terminalWords: List<String> = listOf(
        "เทอร์มินัลสอง", "เทอร์มินอลสอง", "เทอร์มินัล", "เทอร์มินอล", "เทอร์ม",
    ).sortedByDescending { it.length }

    /** Write-code verbs → CREATE_FILE. */
    val writeWords: List<String> = listOf("เขียนโค้ด", "เขียน")

    /** LLM-connect verbs — wired in CP-59 (key via Models UI, never chat). */
    val llmConnectWords: List<String> = listOf("เชื่อมต่อ ai", "ต่อ ai")

    /** Voice verbs (CP-60): speak aloud / listen on mic. */
    val speakWords: List<String> = listOf("อ่านให้ฟัง", "พูดให้ฟัง", "ออกเสียง", "อ่านออกเสียง")
    val listenWords: List<String> = listOf("ฟังเสียง", "รับคำสั่งเสียง", "ฟังหน่อย")

    /** Image verbs (CP-61). */
    val imageInfoWords: List<String> = listOf("ข้อมูลรูป", "รายละเอียดรูป")
    val imageResizeWords: List<String> = listOf("ย่อรูป", "ลดขนาดรูป")
    val imageCropWords: List<String> = listOf("ครอปรูป", "ตัดรูป")
    val imageRotateWords: List<String> = listOf("หมุนรูป")
    val imageGrayWords: List<String> = listOf("รูปขาวดำ", "ขาวดำ")

    /** Audio verbs (CP-62). */
    val audioInfoWords: List<String> = listOf("ข้อมูลเสียง", "รายละเอียดเสียง")
    val audioTrimWords: List<String> = listOf("ตัดเสียง")
    val audioConcatWords: List<String> = listOf("ต่อเสียง")
    val audioGainWords: List<String> = listOf("เร่งเสียง", "เบาเสียง", "ปรับเสียง")
    val audioFadeWords: List<String> = listOf("เฟดเสียง", "เฟด")

    /** Video verbs (CP-63). */
    val videoInfoWords: List<String> = listOf("ข้อมูลวิดีโอ")
    val videoTrimWords: List<String> = listOf("ตัดวิดีโอ")
    val videoThumbWords: List<String> = listOf("ภาพปก", "แคปวิดีโอ")
    val videoAudioWords: List<String> = listOf("ดึงเสียง")

    /** Media project verbs (CP-64). */
    val projectNewWords: List<String> = listOf("โปรเจกต์ใหม่", "สร้างโปรเจกต์")
    val projectListWords: List<String> = listOf("โปรเจกต์")
    val assetImportWords: List<String> = listOf("เพิ่มไฟล์", "import")
    val versionSaveWords: List<String> = listOf("บันทึกเวอร์ชัน", "เซฟเวอร์ชัน")
    val versionRestoreWords: List<String> = listOf("ย้อนเวอร์ชัน", "กลับเวอร์ชัน")

    /** Subtitle verbs (CP-66). */
    val subtitleMakeWords: List<String> = listOf("ทำซับ", "ซับไตเติล")
    val subtitleShiftWords: List<String> = listOf("เลื่อนซับ")
    val subtitleBurnWords: List<String> = listOf("ฝังซับ")
}
