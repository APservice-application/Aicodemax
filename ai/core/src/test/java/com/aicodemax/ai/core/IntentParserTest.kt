package com.aicodemax.ai.core

import org.junit.Assert.assertEquals
import org.junit.Test

class IntentParserTest {
    @Test
    fun parsesFileCommands() {
        val create = IntentParser.parse("สร้างไฟล์ notes.txt: สวัสดี")
        assertEquals(IntentType.CREATE_FILE, create.type)
        assertEquals("notes.txt", create.parameters["path"])
        assertEquals("สวัสดี", create.parameters["content"])

        val read = IntentParser.parse("อ่านไฟล์ notes.txt")
        assertEquals(IntentType.READ_FILE, read.type)
        assertEquals("notes.txt", read.parameters["path"])

        val list = IntentParser.parse("ดูไฟล์")
        assertEquals(IntentType.LIST_FILES, list.type)

        val mkdir = IntentParser.parse("mkdir folder docs")
        assertEquals(IntentType.MAKE_DIR, mkdir.type)
        assertEquals("docs", mkdir.parameters["path"])

        val delete = IntentParser.parse("ลบไฟล์ notes.txt")
        assertEquals(IntentType.DELETE_PATH, delete.type)
        assertEquals("notes.txt", delete.parameters["path"])
    }

    @Test
    fun parsesRuntimeCommands() {
        assertEquals(IntentType.RUN_COMMAND, IntentParser.parse("run ls").type)
        assertEquals(IntentType.RUN_COMMAND, IntentParser.parse("รัน pwd").type)
        assertEquals(IntentType.RUN_TESTS, IntentParser.parse("run tests").type)
        assertEquals(IntentType.BUILD_PROJECT, IntentParser.parse("build").type)
        assertEquals(IntentType.GIT_ACTION, IntentParser.parse("git status").type)

        val open = IntentParser.parse("เปิดเว็บ https://example.com")
        assertEquals(IntentType.OPEN_URL, open.type)
        assertEquals("https://example.com", open.parameters["url"])
    }

    @Test
    fun politeWrappersStillParse() {
        val create = IntentParser.parse("ช่วยสร้างไฟล์ notes.txt: สวัสดี หน่อยครับ")
        assertEquals(IntentType.CREATE_FILE, create.type)
        assertEquals("notes.txt", create.parameters["path"])
        assertEquals("สวัสดี", create.parameters["content"])

        val read = IntentParser.parse("รบกวนอ่านไฟล์ a.txt ด้วยค่ะ")
        assertEquals(IntentType.READ_FILE, read.type)
        assertEquals("a.txt", read.parameters["path"])

        val git = IntentParser.parse("git commit -m \"done\"")
        assertEquals(IntentType.GIT_ACTION, git.type)
        assertEquals("commit", git.parameters["action"])
        assertEquals("done", git.parameters["message"])

        val status = IntentParser.parse("git status")
        assertEquals("status", status.parameters["action"])
    }

    @Test
    fun everythingElseIsChat() {
        assertEquals(IntentType.CHAT, IntentParser.parse("สวัสดี").type)
        assertEquals(IntentType.CHAT, IntentParser.parse("hello there").type)
        assertEquals(IntentType.CHAT, IntentParser.parse("").type)
    }

    @Test
    fun cp57AutomationIntents() {
        assertEquals(IntentType.STOP_TASK, IntentParser.parse("หยุดงาน").type)
        assertEquals(IntentType.STOP_TASK, IntentParser.parse("ช่วยสั่งหยุดหน่อยครับ").type)
        assertEquals(IntentType.SEARCH_FILES, IntentParser.parse("ค้นหา TODO").type)
        assertEquals(IntentType.SYSTEM_STATUS, IntentParser.parse("สถานะระบบ").type)
        assertEquals(IntentType.OPEN_SETTINGS, IntentParser.parse("ตั้งค่า").type)
        assertEquals(IntentType.BROWSER_LIST, IntentParser.parse("ดูแท็บหน่อย").type)
        assertEquals(IntentType.DEBUG_CODE, IntentParser.parse("แก้บั๊ก: boom").type)
        assertEquals(IntentType.LLM_CONNECT, IntentParser.parse("เชื่อมต่อ ai").type)

        val save = IntentParser.parse("บันทึก wifi: รหัส 1234")
        assertEquals(IntentType.MEMORY_SAVE, save.type)
        assertEquals("wifi", save.parameters["key"])
        assertEquals("รหัส 1234", save.parameters["value"])

        val recall = IntentParser.parse("ความจำ wifi")
        assertEquals(IntentType.MEMORY_RECALL, recall.type)
        assertEquals("wifi", recall.parameters["key"])

        val media = IntentParser.parse("ช่วยตัดคลิปติ๊กต็อกหน่อย")
        assertEquals(IntentType.MEDIA_EDIT, media.type)
        assertEquals("tiktok", media.parameters["platform"])

        val open = IntentParser.parse("เปิดดู example.com")
        assertEquals(IntentType.BROWSER_OPEN, open.type)
        assertEquals("example.com", open.parameters["url"])

        // No false positives on incidental substrings.
        assertEquals(IntentType.CHAT, IntentParser.parse("ตกลง").type)
        assertEquals(IntentType.CHAT, IntentParser.parse("อย่าหยุดนะ").type)
    }

    @Test
    fun cp58SkillIntents() {
        assertEquals(IntentType.SKILL_LIST, IntentParser.parse("สกิล").type)
        assertEquals(IntentType.SKILL_LIST, IntentParser.parse("ขอดูสกิลหน่อยครับ").type)
        val get = IntentParser.parse("สกิล aicode-tools")
        assertEquals(IntentType.SKILL_GET, get.type)
        assertEquals("aicode-tools", get.parameters["id"])
        val remove = IntentParser.parse("ลบสกิล my-note")
        assertEquals(IntentType.SKILL_REMOVE, remove.type)
        assertEquals("my-note", remove.parameters["id"])
    }

    @Test
    fun cp60VoiceIntents() {
        val speak = IntentParser.parse("อ่านให้ฟัง: สวัสดีตอนเช้า")
        assertEquals(IntentType.VOICE_SPEAK, speak.type)
        assertEquals("สวัสดีตอนเช้า", speak.parameters["text"])
        val speak2 = IntentParser.parse("ช่วยพูดให้ฟังหน่อยครับ")
        assertEquals(IntentType.VOICE_SPEAK, speak2.type)
        assertEquals(IntentType.VOICE_LISTEN, IntentParser.parse("ฟังเสียงหน่อย").type)
        assertEquals(IntentType.VOICE_LISTEN, IntentParser.parse("รับคำสั่งเสียง").type)
    }

    @Test
    fun cp61ImageIntents() {
        val info = IntentParser.parse("ข้อมูลรูป photo.png")
        assertEquals(IntentType.IMAGE_INFO, info.type)
        assertEquals("photo.png", info.parameters["path"])
        val resize = IntentParser.parse("ย่อรูป photo.png เหลือ 800")
        assertEquals(IntentType.IMAGE_RESIZE, resize.type)
        assertEquals("800", resize.parameters["maxDim"])
        // Digits in the file name must not leak into params.
        val resize2 = IntentParser.parse("ย่อรูป photo2.png")
        assertEquals("photo2.png", resize2.parameters["path"])
        assertEquals(null, resize2.parameters["maxDim"])
        val crop = IntentParser.parse("ครอปรูป a.png 10,20,100,100")
        assertEquals(IntentType.IMAGE_CROP, crop.type)
        assertEquals("10", crop.parameters["x"])
        assertEquals("100", crop.parameters["h"])
        val rot = IntentParser.parse("หมุนรูป a.png 180")
        assertEquals(IntentType.IMAGE_ROTATE, rot.type)
        assertEquals("180", rot.parameters["degrees"])
        val gray = IntentParser.parse("ทำรูปขาวดำ a.png หน่อย")
        assertEquals(IntentType.IMAGE_GRAY, gray.type)
        assertEquals("a.png", gray.parameters["path"])
    }

    @Test
    fun cp62AudioIntents() {
        val info = IntentParser.parse("ข้อมูลเสียง song.mp3")
        assertEquals(IntentType.AUDIO_INFO, info.type)
        assertEquals("song.mp3", info.parameters["path"])
        val trim = IntentParser.parse("ตัดเสียง a.wav 0,5000")
        assertEquals(IntentType.AUDIO_TRIM, trim.type)
        assertEquals("0", trim.parameters["startMs"])
        assertEquals("5000", trim.parameters["endMs"])
        val concat = IntentParser.parse("ต่อเสียง a.wav b.wav")
        assertEquals(IntentType.AUDIO_CONCAT, concat.type)
        assertEquals("a.wav|b.wav", concat.parameters["srcs"])
        val gain = IntentParser.parse("เร่งเสียง a.wav 6")
        assertEquals(IntentType.AUDIO_GAIN, gain.type)
        assertEquals("6", gain.parameters["db"])
        val soft = IntentParser.parse("เบาเสียง a.wav 6")
        assertEquals("-6", soft.parameters["db"])
        val fade = IntentParser.parse("เฟดเสียง a.wav 1000,2000")
        assertEquals(IntentType.AUDIO_FADE, fade.type)
        assertEquals("1000", fade.parameters["inMs"])
        assertEquals("2000", fade.parameters["outMs"])
    }

    @Test
    fun cp63VideoIntents() {
        val info = IntentParser.parse("ข้อมูลวิดีโอ clip.mp4")
        assertEquals(IntentType.VIDEO_INFO, info.type)
        assertEquals("clip.mp4", info.parameters["path"])
        val trim = IntentParser.parse("ตัดวิดีโอ a.mp4 0,10000")
        assertEquals(IntentType.VIDEO_TRIM, trim.type)
        assertEquals("10000", trim.parameters["endMs"])
        val thumb = IntentParser.parse("ภาพปก a.mp4 2000")
        assertEquals(IntentType.VIDEO_THUMB, thumb.type)
        assertEquals("2000", thumb.parameters["timeMs"])
        val audio = IntentParser.parse("ดึงเสียง a.mp4")
        assertEquals(IntentType.VIDEO_AUDIO, audio.type)
        assertEquals("a.mp4", audio.parameters["path"])
    }

    @Test
    fun cp64ProjectIntents() {
        val create = IntentParser.parse("โปรเจกต์ใหม่ เที่ยวทะเล")
        assertEquals(IntentType.PROJECT_NEW, create.type)
        assertEquals("เที่ยวทะเล", create.parameters["name"])
        assertEquals(IntentType.PROJECT_NEW, IntentParser.parse("สร้างโปรเจกต์").type)
        assertEquals(IntentType.PROJECT_LIST, IntentParser.parse("ดูโปรเจกต์หน่อย").type)
        val import = IntentParser.parse("เพิ่มไฟล์ a.mp4")
        assertEquals(IntentType.ASSET_IMPORT, import.type)
        assertEquals("a.mp4", import.parameters["path"])
        assertEquals(IntentType.PROJECT_VERSION, IntentParser.parse("บันทึกเวอร์ชัน").type)
        val restore = IntentParser.parse("ย้อนเวอร์ชัน 2")
        assertEquals(IntentType.PROJECT_RESTORE, restore.type)
        assertEquals("2", restore.parameters["version"])
    }
}
