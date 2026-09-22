package com.aicodemax.tools.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProbeTest {
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    @Test
    fun wavExactDuration() {
        val head = ByteArray(64)
        "RIFF".toByteArray().copyInto(head, 0)
        "WAVE".toByteArray().copyInto(head, 8)
        "fmt ".toByteArray().copyInto(head, 12)
        le32(16).copyInto(head, 16)
        le16(1).copyInto(head, 20) // PCM
        le16(1).copyInto(head, 22) // mono
        le32(8000).copyInto(head, 24)
        le16(16).copyInto(head, 34) // bits
        "data".toByteArray().copyInto(head, 36)
        le32(16000).copyInto(head, 40) // 1s of 8kHz/16-bit mono
        val info = AudioProbe.parse(head, 16044)!!
        assertEquals("WAV", info.format)
        assertEquals(1000L, info.durationMs)
        assertEquals(8000, info.sampleRate)
        assertTrue(info.exact)
    }

    @Test
    fun flacStreaminfo() {
        val head = ByteArray(48)
        "fLaC".toByteArray().copyInto(head, 0)
        head[4] = 0 // STREAMINFO, not last
        head[5] = 0; head[6] = 0; head[7] = 34 // len
        // rate=44100 (0x0AC44), ch=2, bps=16, total=44100
        head[18] = 0x0A
        head[19] = 0xC4.toByte()
        head[20] = 0x42 // rate nibble 4 | ch(1)<<1 | bps-high 0
        head[21] = 0xF0.toByte() // bps-low F | total-high 0
        head[22] = 0; head[23] = 0
        head[24] = 0xAC.toByte(); head[25] = 0x44
        val info = AudioProbe.parse(head, 9999)!!
        assertEquals("FLAC", info.format)
        assertEquals(44100, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(1000L, info.durationMs)
        assertTrue(info.exact)
    }

    @Test
    fun mp4MvhdDuration() {
        val head = ByteArray(64)
        be32(24).copyInto(head, 0)
        "ftyp".toByteArray().copyInto(head, 4)
        be32(32).copyInto(head, 24)
        "moov".toByteArray().copyInto(head, 28)
        be32(24).copyInto(head, 32)
        "mvhd".toByteArray().copyInto(head, 36)
        head[40] = 0 // version 0
        be32(1000).copyInto(head, 52) // timescale @ inner+20 = 32+20
        be32(5000).copyInto(head, 56) // duration @ inner+24 = 32+24
        val info = AudioProbe.parse(head, 100)!!
        assertEquals("M4A", info.format)
        assertEquals(5000L, info.durationMs)
        assertTrue(info.exact)
    }

    @Test
    fun mp3CbrEstimate() {
        val head = ByteArray(32)
        head[0] = 0xFF.toByte(); head[1] = 0xFB.toByte() // MPEG1 Layer III
        head[2] = 0x90.toByte() // 128kbps, 44100Hz
        val info = AudioProbe.parse(head, 128000)!!
        assertEquals("MP3", info.format)
        assertEquals(44100, info.sampleRate)
        assertEquals(8000L, info.durationMs) // 128000*8000/128000
        assertTrue(!info.exact)
    }

    @Test
    fun oggFormatOnly() {
        val head = "OggS".toByteArray() + ByteArray(28)
        val info = AudioProbe.parse(head, 5000)!!
        assertEquals("OGG", info.format)
        assertEquals(-1L, info.durationMs)
    }

    @Test
    fun unknownIsNull() {
        assertNull(AudioProbe.parse(ByteArray(32) { (it * 7).toByte() }, 32))
        assertNull(AudioProbe.parse(ByteArray(4), 4))
    }
}
