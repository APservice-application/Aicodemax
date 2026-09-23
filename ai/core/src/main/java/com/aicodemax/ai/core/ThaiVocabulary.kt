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
    val benchWords: List<String> = listOf("เบนช์มาร์ก", "วัดความเร็ว", "ทดสอบความเร็ว", "benchmark", "bench")

    /** Search verbs. */
    val searchWords: List<String> = listOf("ค้นหา")
    val searchAllWords: List<String> = listOf("ค้นหาทุกที่", "ค้นหาทั้งหมด", "search all")

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

    /** Terminal words (map to RUN_COMMAND; wired to the real system shell in CP-113). */
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
    val imageAdjustWords: List<String> = listOf("แต่งภาพ", "ปรับภาพ", "ปรับแสง", "สว่างขึ้น", "คอนทราสต์", "ความคมชัด")
    val imageUpscaleWords: List<String> = listOf("ขยายภาพ", "อัปสเกล", "อัพสเกล", "upscale")
    val imageRestoreWords: List<String> = listOf("ฟื้นฟูภาพ", "ภาพเก่า", "รีสโตร์ภาพ", "restore")

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
    val projectRenameWords: List<String> = listOf("เปลี่ยนชื่อโปรเจกต์", "ตั้งชื่อโปรเจกต์ใหม่")
    val projectDeleteWords: List<String> = listOf("ลบโปรเจกต์")
    val projectDuplicateWords: List<String> = listOf("สำเนาโปรเจกต์", "ทำสำเนาโปรเจกต์", "ก๊อปปี้โปรเจกต์")
    val checkpointWords: List<String> = listOf("เช็คพอยต์", "จุดบันทึก")
    val undoWords: List<String> = listOf("ย้อนกลับ", "undo")
    val redoWords: List<String> = listOf("ทำซ้ำ", "redo")
    val clipSplitWords: List<String> = listOf("แยกคลิป", "split")
    val clipTrimWords: List<String> = listOf("ทริมคลิป", "ตัดหัวคลิป", "ตัดท้ายคลิป", "trim")
    val clipMoveWords: List<String> = listOf("ย้ายคลิป", "เลื่อนคลิป")
    val clipDeleteWords: List<String> = listOf("ลบคลิป")
    val clipDuplicateWords: List<String> = listOf("สำเนาคลิป", "ทำสำเนาคลิป", "ก๊อปปี้คลิป")
    val clipRotateWords: List<String> = listOf("หมุนคลิป")
    val clipFlipWords: List<String> = listOf("พลิกคลิป")
    val clipFreezeWords: List<String> = listOf("ฟรีซเฟรม", "ฟรีซคลิป", "หยุดภาพ")
    val textAddWords: List<String> = listOf("เพิ่มข้อความ", "ใส่ข้อความ", "เพิ่มตัวหนังสือ")
    val textRemoveWords: List<String> = listOf("ลบข้อความ")
    val textIdeaWords: List<String> = listOf("คิดแคปชัน", "คิดหัวข้อ", "คิดสโลแกน", "ช่วยคิดข้อความ", "เขียนแคปชัน")
    val clipSpeedWords: List<String> = listOf("ความเร็วคลิป", "สปีดคลิป", "ช้าลงคลิป", "เร็วขึ้นคลิป")
    val clipReverseWords: List<String> = listOf("ย้อนคลิป", "เล่นย้อนกลับ")
    val keyframeWords: List<String> = listOf("คีย์เฟรม", "keyframe")
    val keyframeClearWords: List<String> = listOf("ลบคีย์เฟรม", "ล้างคีย์เฟรม")
    val transitionWords: List<String> = listOf("ทรานซิชัน", "transition", "เฟดคลิป", "ดีซอล์ฟ", "ไวป์คลิป")
    val clipFxWords: List<String> = listOf("เบลอคลิป", "วิกเน็ต", "เกรนคลิป", "ใส่เกรน")
    val clipColorWords: List<String> = listOf("สีคลิป", "แก้สี", "โทนอุ่น", "โทนเย็น", "ขาวดำคลิป", "ซีนีม่า", "ความสว่างคลิป", "คอนทราสต์คลิป", "อิ่มสี", "เอ็กซ์โพเชอร์", "รับแสง", "ไฮไลต์", "แชโดว์", "จุดขาว", "จุดดำ", "ทินต์", "ไวท์", "แบล็ก")
    val colorAutoWords: List<String> = listOf("ออโต้สี", "ปรับสีอัตโนมัติ", "สีอัตโนมัติ", "ออโต้คัลเลอร์")
    val lutWords: List<String> = listOf("lut", "ลุต", "ลัท")
    val templateWords: List<String> = listOf("เทมเพลต", "เทมเพลท", "template")
    val libraryWords: List<String> = listOf("คลัง", "ไลบรารี", "library")
    val genWords: List<String> = listOf("สร้างภาพ", "สร้างโปสเตอร์", "ทำโปสเตอร์", "สร้างพื้นหลัง", "ทำเสียงพูด", "เสียงบรรยาย", "พากย์เสียง", "แต่งรูป", "ทำปก", "ปกคลิป", "ธัมบ์เนล", "thumbnail")
    val genListWords: List<String> = listOf("สร้างอะไรได้บ้าง", "มีตัวสร้างอะไร", "ตัวสร้างมีเดีย")
    val motionWords: List<String> = listOf("โมชัน", "เคนเบิร์น", "ซูมภาพนิ่ง", "แพนภาพ")
    val slideshowWords: List<String> = listOf("สไลด์โชว์", "สไลด์โช", "slideshow")
    val beatWords: List<String> = listOf("จังหวะ", "บีต", "bpm")
    val clipVolumeWords: List<String> = listOf("วอลลุ่ม", "ระดับเสียง", "ดังขึ้น", "เบาลง", "เสียงคลิป")
    val voiceFxWords: List<String> = listOf("เปลี่ยนเสียง", "เสียงหุ่นยนต์", "เสียงแหลม", "เสียงทุ้ม")
    val synthWords: List<String> = listOf("ทำเพลง", "ดนตรีประกอบ", "เสียงเอฟเฟกต์", "ทำซาวด์", "เอฟเฟกต์เสียง")
    val autocutWords: List<String> = listOf("ตัดเงียบ", "ตัดช่วงเงียบ", "ออโตคัต", "autocut")
    val highlightWords: List<String> = listOf("ช็อตเด่น", "ช่วงเด็ด", "ไฮไลต์คลิป", "หาช็อต")
    val reframeWords: List<String> = listOf("รีเฟรม", "แนวตั้ง", "แนวนอน", "จตุรัส", "9:16", "16:9", "1:1", "4:5", "reframe")
    val colorMatchWords: List<String> = listOf("จับคู่สี", "สีเหมือน", "สีตาม", "แมตช์สี", "match")
    val colorWbWords: List<String> = listOf("ไวต์บาลานซ์", "สมดุลแสงขาว", "white balance")
    val enhanceWords: List<String> = listOf("ปรับปรุงคลิป", "เพิ่มคุณภาพ", "ภาพชัดขึ้น", "เอ็นฮานซ์", "enhance")
    val camTrackWords: List<String> = listOf("แทร็กกล้อง", "ติดตามกล้อง", "camera track", "camtrack", "3d track")
    val beautyWords: List<String> = listOf("บิวตี้", "หน้าเนียน", "ผิวเนียน", "beauty")
    val recordStartWords: List<String> = listOf("อัดเสียง", "บันทึกเสียง", "เริ่มอัด", "record")
    val recordStopWords: List<String> = listOf("หยุดอัด", "เลิกอัด", "stop record")
    val screenRecordWords: List<String> = listOf("อัดหน้าจอ", "บันทึกหน้าจอ", "screen record", "แคปหน้าจอวิดีโอ")
    val podcastWords: List<String> = listOf("พอดแคสต์", "พอดคาสต์", "podcast")
    val audioMixWords: List<String> = listOf("ผสมเสียง", "มิกซ์เสียง", "mix")
    val normalizeWords: List<String> = listOf("นอร์มัลไลซ์", "ปรับระดับเสียง", "normalize")
    val aiPlanWords: List<String> = listOf("วางแผนคลิป", "ไอเดียวิดีโอ", "แผนคอนเทนต์", "aiplan")
    val scriptVideoWords: List<String> = listOf("สคริปต์เป็นวิดีโอ", "บทเป็นวิดีโอ", "บทเป็นคลิป", "script to video", "scriptvideo")
    val lipsyncWords: List<String> = listOf("ลิปซิงค์", "ลิปซิงก์", "ขยับปากตามเสียง", "lipsync", "lip sync")
    val presenterWords: List<String> = listOf("ผู้ประกาศ", "พิธีกร", "presenter", "avatar พูด", "อวตารพูด")
    val brandSaveWords: List<String> = listOf("บันทึกแบรนด์", "สร้างแบรนด์", "brand kit", "ชุดแบรนด์")
    val brandApplyWords: List<String> = listOf("ใช้แบรนด์", "ใส่แบรนด์", "apply brand")
    val packageWords: List<String> = listOf("แพ็กโปรเจกต์", "แพคโปรเจกต์", "zip โปรเจกต์", "สำรองโปรเจกต์", "package")
    val batchWords: List<String> = listOf("เรนเดอร์หลาย", "เอ็กซ์พอร์ตหลาย", "เรนเดอร์ทั้งหมด", "batch")
    val proxyWords: List<String> = listOf("พร็อกซี", "ไฟล์พร็อกซี", "ไฟล์ตัดต่อเบา", "proxy")
    val cacheWords: List<String> = listOf("แคช", "พื้นที่แคช", "ล้างแคช", "cache")
    val hwWords: List<String> = listOf("จีพียู", "ฮาร์ดแวร์", "ตัวเร่ง", "gpu", "hw", "เอนโค้ดเดอร์")
    val multicamWords: List<String> = listOf("มัลติแคม", "หลายกล้อง", "หลายมุม", "ซิงก์กล้อง", "multicam", "multi-cam")
    val scopesWords: List<String> = listOf("สโคป", "เวฟฟอร์ม", "เวกเตอร์สโคป", "ฮิสโตแกรม", "พาเหรด", "waveform", "vectorscope", "scopes")
    val shortcutWords: List<String> = listOf("คีย์ลัด", "ปุ่มลัด", "ชอร์ตคัต", "shortcut", "hotkey")
    val aiModeWords: Map<String, String> = mapOf(
        "โฆษณา" to "commercial", "commercial" to "commercial",
        "เล่าเรื่อง" to "story", "สตอรี่" to "story",
        "วล็อก" to "vlog", "vlog" to "vlog",
        "สอนทำ" to "tutorial", "สอน" to "tutorial", "tutorial" to "tutorial",
        "รีวิว" to "review", "review" to "review",
    )
    val imageScopesWords: List<String> = listOf("สโคป", "ฮิสโตแกรม", "เช็คแสง", "วัดแสง")
    val clipMaskWords: List<String> = listOf("มาสก์คลิป", "maskคลิป")
    val clipChromaWords: List<String> = listOf("กรีนสกรีน", "ลบฉากเขียว", "ฉากเขียว", "chroma")
    val bgWords: List<String> = listOf("พื้นหลังเบลอ", "พื้นหลังสี", "พื้นหลังรูป", "พื้นหลังดำ")
    val trackWords: List<String> = listOf("แทร็กคลิป", "แทร็กวัตถุ", "ตามวัตถุ")
    val stabWords: List<String> = listOf("กันสั่น", "ลดสั่น", "stabilize")
    val markerAddWords: List<String> = listOf("เพิ่มมาร์กเกอร์", "มาร์กเกอร์", "จุดมาร์ก")
    val markerRemoveWords: List<String> = listOf("ลบมาร์กเกอร์")
    val trackUnlockWords: List<String> = listOf("ปลดล็อกแทร็ก")
    val trackLockWords: List<String> = listOf("ล็อกแทร็ก")
    val trackMuteWords: List<String> = listOf("ปิดเสียงแทร็ก")
    val trackUnmuteWords: List<String> = listOf("เปิดเสียงแทร็ก")
    val trackHideWords: List<String> = listOf("ซ่อนแทร็ก")
    val trackShowWords: List<String> = listOf("แสดงแทร็ก")
    val assetImportWords: List<String> = listOf("เพิ่มไฟล์", "import")
    val versionSaveWords: List<String> = listOf("บันทึกเวอร์ชัน", "เซฟเวอร์ชัน")
    val versionRestoreWords: List<String> = listOf("ย้อนเวอร์ชัน", "กลับเวอร์ชัน")

    /** Subtitle verbs (CP-66). */
    val subtitleMakeWords: List<String> = listOf("ทำซับ", "ซับไตเติล")
    val subtitleShiftWords: List<String> = listOf("เลื่อนซับ")
    val subtitleBurnWords: List<String> = listOf("ฝังซับ")
    val subtitleTranslateWords: List<String> = listOf("แปลซับ", "แปลซับไตเติล", "translate subtitle")
    val renderStatusWords: List<String> = listOf("สถานะเรนเดอร์", "คิวเรนเดอร์", "งานเรนเดอร์")
    val renderApproveWords: List<String> = listOf("อนุมัติ")
    val renderExportWords: List<String> = listOf("เอ็กซ์พอร์ต", "export", "ส่งออก")
    val renderStartWords: List<String> = listOf("เรนเดอร์", "render")
    val directorWords: List<String> = listOf("ผู้กำกับ", "ตรวจงาน", "รีวิวงาน", "director")
}
