package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperSegmentsTest {
    @Test
    fun parseTwoSegmentsWithThai() {
        val json = "{\"segments\":[" +
            "{\"t0\":0,\"t1\":1500,\"text\":\" สวัสดีครับ\"}," +
            "{\"t0\":1500,\"t1\":3200,\"text\":\" วันนี้อากาศดี\"}" +
            "]}"
        val res = WhisperSegments.parse(json)
        assertTrue(res is Outcome.Success)
        val segs = (res as Outcome.Success).value
        assertEquals(2, segs.size)
        assertEquals(WhisperSegment(0, 1500, " สวัสดีครับ"), segs[0])
        assertEquals(WhisperSegment(1500, 3200, " วันนี้อากาศดี"), segs[1])
    }

    @Test
    fun parseHandlesEscapesAndKeyOrder() {
        val json = "{ \"segments\" : [ { \"text\" : \"a\\\"b\\\\c\\n\\u0041\", \"t1\" : 200, \"t0\" : 100 } ] }"
        val res = WhisperSegments.parse(json)
        assertTrue(res is Outcome.Success)
        val segs = (res as Outcome.Success).value
        assertEquals(1, segs.size)
        assertEquals(WhisperSegment(100, 200, "a\"b\\c\nA"), segs[0])
    }

    @Test
    fun parseEmptySegments() {
        val res = WhisperSegments.parse("{\"segments\":[]}")
        assertTrue(res is Outcome.Success)
        assertEquals(0, (res as Outcome.Success).value.size)
    }

    @Test
    fun parseMalformedFails() {
        assertTrue(WhisperSegments.parse("not json") is Outcome.Failure)
        assertTrue(WhisperSegments.parse("{\"segments\":[{\"t0\":1}]}") is Outcome.Failure)
        assertTrue(WhisperSegments.parse("{\"nope\":[]}") is Outcome.Failure)
    }

    @Test
    fun toSrtFormatsCues() {
        val segs = listOf(
            WhisperSegment(0, 1500, "สวัสดี"),
            WhisperSegment(61500, 3723000, "บรรทัดสอง"),
        )
        val expected = "1\n00:00:00,000 --> 00:00:01,500\nสวัสดี\n\n" +
            "2\n00:01:01,500 --> 01:02:03,000\nบรรทัดสอง\n\n"
        assertEquals(expected, WhisperSegments.toSrt(segs))
    }

    @Test
    fun toTextJoinsTrimmed() {
        val segs = listOf(WhisperSegment(0, 1, " หนึ่ง "), WhisperSegment(1, 2, "สอง  "))
        assertEquals("หนึ่ง\nสอง", WhisperSegments.toText(segs))
    }
}
