package com.aicodemax.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun cp72ClipIntents() {
        val split = IntentParser.parse("แยกคลิปที่ 1 นาทีที่ 2")
        assertEquals(IntentType.CLIP_SPLIT, split.type)
        assertEquals("1", split.parameters["clipIndex"])
        assertEquals("120000", split.parameters["atMs"])
        val sec = IntentParser.parse("แยกคลิปที่ 2 90 วิ")
        assertEquals("90000", sec.parameters["atMs"])
        val move = IntentParser.parse("ย้ายคลิปที่ 2 ไปนาทีที่ 1")
        assertEquals(IntentType.CLIP_MOVE, move.type)
        assertEquals("60000", move.parameters["toAtMs"])
        val del = IntentParser.parse("ลบคลิปที่ 3")
        assertEquals(IntentType.CLIP_DELETE, del.type)
        assertEquals("3", del.parameters["clipIndex"])
        val trim = IntentParser.parse("ทริมคลิปที่ 1 เริ่ม 5 วิ จบ 20 วิ")
        assertEquals(IntentType.CLIP_TRIM, trim.type)
        assertEquals("5000", trim.parameters["startMs"])
        assertEquals("20000", trim.parameters["endMs"])
        val mark = IntentParser.parse("มาร์กเกอร์ไฮไลต์ นาทีที่ 2")
        assertEquals(IntentType.MARKER_ADD, mark.type)
        assertEquals("120000", mark.parameters["atMs"])
        val lock = IntentParser.parse("ล็อกแทร็ก V1")
        assertEquals(IntentType.TRACK_FLAGS, lock.type)
        assertEquals("V1", lock.parameters["trackId"])
        assertEquals("true", lock.parameters["locked"])
        val unlock = IntentParser.parse("ปลดล็อกแทร็ก A1")
        assertEquals("false", unlock.parameters["locked"])
    }

    @Test
    fun cp71UndoMgmtIntents() {
        assertEquals(IntentType.EDIT_UNDO, IntentParser.parse("ย้อนกลับ").type)
        assertEquals(IntentType.EDIT_REDO, IntentParser.parse("ทำซ้ำ").type)
        assertEquals(IntentType.PROJECT_DELETE, IntentParser.parse("ลบโปรเจกต์").type)
        assertEquals(IntentType.PROJECT_DUPLICATE, IntentParser.parse("สำเนาโปรเจกต์").type)
        assertEquals(IntentType.PROJECT_CHECKPOINT, IntentParser.parse("เช็คพอยต์").type)
        val rename = IntentParser.parse("เปลี่ยนชื่อโปรเจกต์ ทริปทะเล")
        assertEquals(IntentType.PROJECT_RENAME, rename.type)
        assertEquals("ทริปทะเล", rename.parameters["name"])
    }

    @Test
    fun cp75SpeedIntents() {
        val speed = IntentParser.parse("สปีดคลิปที่ 1 200")
        assertEquals(IntentType.CLIP_SPEED, speed.type)
        assertEquals("1", speed.parameters["clipIndex"])
        assertEquals("200", speed.parameters["rate"])
        val slow = IntentParser.parse("ช้าลงคลิปที่ 2")
        assertEquals("50", slow.parameters["rate"])
        val rev = IntentParser.parse("ย้อนคลิปที่ 1")
        assertEquals(IntentType.CLIP_REVERSE, rev.type)
    }

    @Test
    fun cp94RecordIntents() {
        val start = IntentParser.parse("อัดเสียงที่ rec.m4a")
        assertEquals(IntentType.RECORD_START, start.type)
        assertEquals("rec.m4a", start.parameters["dst"])
        val stop = IntentParser.parse("หยุดอัด")
        assertEquals(IntentType.RECORD_STOP, stop.type)
        val screen = IntentParser.parse("อัดหน้าจอ")
        assertEquals(IntentType.SCREEN_RECORD, screen.type)
    }

    @Test
    fun cp92CamTrackDeferIntent() {
        val ct = IntentParser.parse("แทร็กกล้องคลิปที่ 1")
        assertEquals(IntentType.CAM_TRACK, ct.type)
    }

    @Test
    fun cp93BeautyDeferIntent() {
        val b = IntentParser.parse("บิวตี้หน้าเนียนคลิปที่ 1")
        assertEquals(IntentType.BEAUTY, b.type)
    }

    @Test
    fun cp91EnhanceIntent() {
        val en = IntentParser.parse("ปรับปรุงคลิปที่ 1")
        assertEquals(IntentType.CLIP_ENHANCE, en.type)
        assertEquals("1", en.parameters["clipIndex"])
    }

    @Test
    fun cp90ColorProIntents() {
        val match = IntentParser.parse("จับคู่สีคลิปที่ 2 ตามคลิปที่ 1")
        assertEquals(IntentType.COLOR_MATCH, match.type)
        assertEquals("2", match.parameters["clipIndex"])
        assertEquals("1", match.parameters["refIndex"])
        val wb = IntentParser.parse("ไวต์บาลานซ์คลิปที่ 1 อัตโนมัติ")
        assertEquals(IntentType.COLOR_WB, wb.type)
        assertEquals("auto", wb.parameters["preset"])
    }

    @Test
    fun cp89PhotoIntents() {
        val adj = IntentParser.parse("แต่งภาพ /tmp/a.png สว่าง 20")
        assertEquals(IntentType.IMAGE_ADJUST, adj.type)
        assertEquals("20", adj.parameters["brightness"])
        val up = IntentParser.parse("ขยายภาพ /tmp/a.png 4")
        assertEquals(IntentType.IMAGE_UPSCALE, up.type)
        assertEquals("4", up.parameters["scale"])
        val re = IntentParser.parse("ฟื้นฟูภาพเก่า /tmp/a.png")
        assertEquals(IntentType.IMAGE_RESTORE, re.type)
    }

    @Test
    fun cp88ReframeCanvasIntents() {
        val re = IntentParser.parse("รีเฟรมคลิปที่ 1 เป็นแนวตั้ง")
        assertEquals(IntentType.REFRAME, re.type)
        assertEquals("9:16", re.parameters["aspect"])
        val canvas = IntentParser.parse("ตั้งแคนวาส 16:9")
        assertEquals(IntentType.SET_CANVAS, canvas.type)
    }

    @Test
    fun cp87CutHighlightIntents() {
        val cut = IntentParser.parse("ตัดเงียบคลิปที่ 1")
        assertEquals(IntentType.AUTOCUT, cut.type)
        val hi = IntentParser.parse("ช็อตเด่นคลิปที่ 2")
        assertEquals(IntentType.HIGHLIGHTS, hi.type)
        assertEquals("2", hi.parameters["clipIndex"])
        val colorStill = IntentParser.parse("เพิ่มไฮไลต์คลิปที่ 1 20")
        assertEquals(IntentType.CLIP_COLOR, colorStill.type)
    }

    @Test
    fun cp86VoiceSynthIntents() {
        val fx = IntentParser.parse("เปลี่ยนเสียงพูด a.wav เสียงแหลม")
        assertEquals(IntentType.VOICE_FX, fx.type)
        assertEquals("7", fx.parameters["semitones"])
        val music = IntentParser.parse("ทำเพลงดนตรีประกอบสนุก 15 วิ")
        assertEquals(IntentType.SYNTH_MUSIC, music.type)
        assertEquals("bright", music.parameters["style"])
        val sfx = IntentParser.parse("ทำเสียงเอฟเฟกต์กระแทก")
        assertEquals(IntentType.SYNTH_SFX, sfx.type)
        assertEquals("impact", sfx.parameters["kind"])
    }

    @Test
    fun cp85BeatVolumeIntents() {
        val clip = IntentParser.parse("จับจังหวะคลิปที่ 1")
        assertEquals(IntentType.BEATS, clip.type)
        assertEquals("1", clip.parameters["clipIndex"])
        val file = IntentParser.parse("จับจังหวะเพลง song.wav")
        assertEquals(IntentType.BEATS, file.type)
        assertEquals("song.wav", file.parameters["path"])
        val vol = IntentParser.parse("เสียงคลิปที่ 2 80")
        assertEquals(IntentType.CLIP_VOLUME, vol.type)
        assertEquals("80", vol.parameters["volume"])
    }

    @Test
    fun cp84MotionSlideIntents() {
        val motion = IntentParser.parse("โมชันคลิปที่ 1 ซูมเข้า")
        assertEquals(IntentType.CLIP_MOTION, motion.type)
        assertEquals("in", motion.parameters["dir"])
        val slide = IntentParser.parse("สไลด์โชว์ a.png,b.png")
        assertEquals(IntentType.SLIDESHOW, slide.type)
        assertEquals("a.png,b.png", slide.parameters["assets"])
    }

    @Test
    fun cp83GenIntents() {
        val poster = IntentParser.parse("ทำโปสเตอร์ \"เปิดร้าน\"")
        assertEquals(IntentType.GEN_MAKE, poster.type)
        assertEquals("poster", poster.parameters["kind"])
        assertEquals("เปิดร้าน", poster.parameters["prompt"])
        val tts = IntentParser.parse("ทำเสียงพูด \"สวัสดีครับ\"")
        assertEquals(IntentType.GEN_MAKE, tts.type)
        assertEquals("tts", tts.parameters["kind"])
        val list = IntentParser.parse("สร้างอะไรได้บ้าง")
        assertEquals(IntentType.GEN_LIST, list.type)
    }

    @Test
    fun cp82TemplateLibIntents() {
        val save = IntentParser.parse("บันทึกเทมเพลต \"เปิดคลิป\"")
        assertEquals(IntentType.TEMPLATE_SAVE, save.type)
        assertEquals("เปิดคลิป", save.parameters["name"])
        val apply = IntentParser.parse("ใช้เทมเพลต \"Social Hook\"")
        assertEquals(IntentType.TEMPLATE_APPLY, apply.type)
        assertEquals("Social Hook", apply.parameters["name"])
        val list = IntentParser.parse("ดูเทมเพลต")
        assertEquals(IntentType.TEMPLATE_LIST, list.type)
        val del = IntentParser.parse("ลบเทมเพลต \"เก่า\"")
        assertEquals(IntentType.TEMPLATE_DELETE, del.type)
        val search = IntentParser.parse("ค้นหาคลัง lut")
        assertEquals(IntentType.LIB_SEARCH, search.type)
        assertEquals("lut", search.parameters["query"])
    }

    @Test
    fun cp81ColorLutIntents() {
        val auto = IntentParser.parse("ออโต้สีคลิปที่ 1")
        assertEquals(IntentType.COLOR_AUTO, auto.type)
        assertEquals("1", auto.parameters["clipIndex"])
        val lut = IntentParser.parse("ใส่ LUT คลิปที่ 1 ไฟล์ warm.cube")
        assertEquals(IntentType.LUT_SET, lut.type)
        assertEquals("warm.cube", lut.parameters["path"])
        val clear = IntentParser.parse("ล้าง LUT คลิปที่ 2")
        assertEquals(IntentType.LUT_CLEAR, clear.type)
        val expo = IntentParser.parse("เพิ่มเอ็กซ์โพเชอร์คลิปที่ 1 20")
        assertEquals(IntentType.CLIP_COLOR, expo.type)
        assertEquals("20", expo.parameters["exposure"])
    }

    @Test
    fun cp80TrackStabIntents() {
        val track = IntentParser.parse("แทร็กคลิปที่ 1")
        assertEquals(IntentType.TRACK, track.type)
        assertEquals("1", track.parameters["clipIndex"])
        val follow = IntentParser.parse("แทร็กคลิปที่ 1 ตามด้วยข้อความที่ 2")
        assertEquals("text:2", follow.parameters["target"])
        val stab = IntentParser.parse("กันสั่นคลิปที่ 2")
        assertEquals(IntentType.STABILIZE, stab.type)
        assertEquals("2", stab.parameters["clipIndex"])
    }

    @Test
    fun cp79MaskChromaBgIntents() {
        val mask = IntentParser.parse("มาสก์คลิปที่ 1 วงรี")
        assertEquals(IntentType.CLIP_MASK, mask.type)
        assertEquals("1", mask.parameters["clipIndex"])
        assertEquals("ellipse", mask.parameters["shape"])
        val chroma = IntentParser.parse("กรีนสกรีนคลิปที่ 2")
        assertEquals(IntentType.CLIP_CHROMA, chroma.type)
        assertEquals("2", chroma.parameters["clipIndex"])
        val bg = IntentParser.parse("พื้นหลังสีแดง")
        assertEquals(IntentType.BG_SET, bg.type)
        assertEquals("color", bg.parameters["mode"])
        assertEquals("FF0000", bg.parameters["color"])
    }

    @Test
    fun cp78ColorScopesIntents() {
        val warm = IntentParser.parse("สีคลิปที่ 1 โทนอุ่น")
        assertEquals(IntentType.CLIP_COLOR, warm.type)
        assertEquals("1", warm.parameters["clipIndex"])
        assertEquals("warm", warm.parameters["preset"])
        val bright = IntentParser.parse("ความสว่างคลิปที่ 2 20")
        assertEquals("20", bright.parameters["brightness"])
        val scopes = IntentParser.parse("เช็คแสง beach.jpg")
        assertEquals(IntentType.IMAGE_SCOPES, scopes.type)
        assertEquals("beach.jpg", scopes.parameters["path"])
    }

    @Test
    fun cp77TransitionFxIntents() {
        val tr = IntentParser.parse("ทรานซิชันคลิปที่ 2 ดีซอล์ฟ")
        assertEquals(IntentType.TRANSITION_SET, tr.type)
        assertEquals("2", tr.parameters["clipIndex"])
        assertEquals("dissolve", tr.parameters["kind"])
        assertEquals("in", tr.parameters["edge"])
        val fx = IntentParser.parse("เบลอคลิปที่ 1 5")
        assertEquals(IntentType.CLIP_FX, fx.type)
        assertEquals("5", fx.parameters["blur"])
    }

    @Test
    fun cp76KeyframeIntents() {
        val set = IntentParser.parse("คีย์เฟรมสเกลคลิปที่ 1 150 ตอน 2 วิ")
        assertEquals(IntentType.KEYFRAME_SET, set.type)
        assertEquals("1", set.parameters["clipIndex"])
        assertEquals("scale", set.parameters["prop"])
        assertEquals("150", set.parameters["value"])
        assertEquals("2000", set.parameters["atMs"])
        val clear = IntentParser.parse("ลบคีย์เฟรมคลิปที่ 2")
        assertEquals(IntentType.KEYFRAME_CLEAR, clear.type)
        assertEquals("2", clear.parameters["clipIndex"])
    }

    @Test
    fun cp74TextIntents() {
        val add = IntentParser.parse("เพิ่มข้อความ: เปิดร้านแล้ว!")
        assertEquals(IntentType.TEXT_ADD, add.type)
        assertEquals("เปิดร้านแล้ว!", add.parameters["text"])
        val remove = IntentParser.parse("ลบข้อความที่ 2")
        assertEquals(IntentType.TEXT_REMOVE, remove.type)
        assertEquals("2", remove.parameters["textIndex"])
        val idea = IntentParser.parse("คิดแคปชันร้านกาแฟ")
        assertEquals(IntentType.TEXT_IDEA, idea.type)
        assertEquals("caption", idea.parameters["kind"])
        assertEquals("ร้านกาแฟ", idea.parameters["topic"])
    }

    @Test
    fun cp73TransformIntents() {
        val rotate = IntentParser.parse("หมุนคลิปที่ 1 180")
        assertEquals(IntentType.CLIP_ROTATE, rotate.type)
        assertEquals("1", rotate.parameters["clipIndex"])
        assertEquals("180", rotate.parameters["rotation"])
        val flip = IntentParser.parse("พลิกคลิปที่ 2")
        assertEquals(IntentType.CLIP_FLIP, flip.type)
        assertEquals("true", flip.parameters["flipH"])
        val flipV = IntentParser.parse("พลิกคลิปที่ 2 บนล่าง")
        assertEquals("true", flipV.parameters["flipV"])
        val freeze = IntentParser.parse("ฟรีซคลิปที่ 1 3 วิ")
        assertEquals(IntentType.CLIP_FREEZE, freeze.type)
        assertEquals("3000", freeze.parameters["holdMs"])
    }

    @Test
    fun cp67RenderIntents() {
        val start = IntentParser.parse("เรนเดอร์ 720p")
        assertEquals(IntentType.RENDER_START, start.type)
        assertEquals("720p", start.parameters["preset"])
        val status = IntentParser.parse("สถานะเรนเดอร์")
        assertEquals(IntentType.RENDER_STATUS, status.type)
        val approve = IntentParser.parse("อนุมัติ")
        assertEquals(IntentType.RENDER_APPROVE, approve.type)
        val export = IntentParser.parse("เอ็กซ์พอร์ต")
        assertEquals(IntentType.RENDER_EXPORT, export.type)
        val share = IntentParser.parse("แชร์ลงติ๊กต็อก")
        assertEquals(IntentType.SHARE_MEDIA, share.type)
    }

    @Test
    fun cp66SubtitleIntents() {
        val make = IntentParser.parse("ทำซับ a.wav: สวัสดีครับทุกคน")
        assertEquals(IntentType.SUBTITLE_MAKE, make.type)
        assertEquals("a.wav", make.parameters["path"])
        assertEquals("สวัสดีครับทุกคน", make.parameters["transcript"])
        val shift = IntentParser.parse("เลื่อนซับ a.srt 500")
        assertEquals(IntentType.SUBTITLE_SHIFT, shift.type)
        assertEquals("a.srt", shift.parameters["path"])
        assertEquals("500", shift.parameters["offsetMs"])
        val back = IntentParser.parse("เลื่อนซับ a.srt ถอย 500")
        assertEquals("-500", back.parameters["offsetMs"])
        val burn = IntentParser.parse("ฝังซับ a.mp4 a.srt")
        assertEquals(IntentType.SUBTITLE_BURN, burn.type)
        assertEquals("a.mp4", burn.parameters["src"])
        assertEquals("a.srt", burn.parameters["srt"])
        val burnRev = IntentParser.parse("ฝังซับ a.srt ลง a.mp4")
        assertEquals("a.mp4", burnRev.parameters["src"])
        assertEquals("a.srt", burnRev.parameters["srt"])
    }
}
