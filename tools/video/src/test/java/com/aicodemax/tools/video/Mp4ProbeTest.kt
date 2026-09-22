package com.aicodemax.tools.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4ProbeTest {
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun box(name: String, content: ByteArray): ByteArray =
        be32(8 + content.size) + name.toByteArray(Charsets.US_ASCII) + content

    private fun mdhd(timescale: Int, duration: Int): ByteArray {
        val c = ByteArray(24)
        be32(timescale).copyInto(c, 12)
        be32(duration).copyInto(c, 16)
        return box("mdhd", c)
    }

    private fun hdlr(kind: String): ByteArray {
        val c = ByteArray(24)
        kind.toByteArray(Charsets.US_ASCII).copyInto(c, 8)
        return box("hdlr", c)
    }

    private fun tkhd(w: Int, h: Int): ByteArray {
        val c = ByteArray(88)
        be32(w shl 16).copyInto(c, 76)
        be32(h shl 16).copyInto(c, 80)
        return box("tkhd", c)
    }

    private fun trak(kind: String, w: Int, h: Int, timescale: Int, duration: Int): ByteArray {
        val mdia = box("mdia", mdhd(timescale, duration) + hdlr(kind))
        return box("trak", tkhd(w, h) + mdia)
    }

    private fun sample(): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(8))
        val mvhdContent = ByteArray(24)
        be32(1000).copyInto(mvhdContent, 12)
        be32(30000).copyInto(mvhdContent, 16)
        val moov = box(
            "moov",
            box("mvhd", mvhdContent) +
                trak("vide", 1280, 720, 90000, 2700000) +
                trak("soun", 0, 0, 48000, 1440000),
        )
        return ftyp + moov
    }

    @Test
    fun parsesDurationDimsAndTracks() {
        val info = Mp4Probe.parse(sample(), 123456)!!
        assertEquals("MP4", info.format)
        assertEquals(30000L, info.durationMs)
        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        assertTrue(info.hasVideo)
        assertTrue(info.hasAudio)
        assertEquals(123456L, info.sizeBytes)
    }

    @Test
    fun fallsBackToTrackDuration() {
        val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(8))
        val moov = box("moov", trak("vide", 640, 480, 1000, 5000))
        val info = Mp4Probe.parse(ftyp + moov, 100)!!
        assertEquals(5000L, info.durationMs)
        assertEquals(640, info.width)
        assertTrue(info.hasVideo)
        assertTrue(!info.hasAudio)
    }

    @Test
    fun nonBoxFileIsNull() {
        assertNull(Mp4Probe.parse(ByteArray(64) { it.toByte() }, 64))
        assertNull(Mp4Probe.parse(ByteArray(4), 4))
    }
}
