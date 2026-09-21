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
    fun everythingElseIsChat() {
        assertEquals(IntentType.CHAT, IntentParser.parse("สวัสดี").type)
        assertEquals(IntentType.CHAT, IntentParser.parse("hello there").type)
        assertEquals(IntentType.CHAT, IntentParser.parse("").type)
    }
}
