package com.aicodemax.tools.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageProbeTest {
    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    @Test
    fun pngDims() {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val bytes = sig + be32(13) + "IHDR".toByteArray() + be32(800) + be32(600) + ByteArray(8)
        val info = ImageProbe.parse(bytes)!!
        assertEquals("PNG", info.format)
        assertEquals(800, info.width)
        assertEquals(600, info.height)
    }

    @Test
    fun jpegDims() {
        val bytes = ByteArray(40)
        bytes[0] = 0xFF.toByte(); bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte(); bytes[3] = 0xE0.toByte() // APP0
        bytes[4] = 0x00; bytes[5] = 0x10 // len 16
        bytes[20] = 0xFF.toByte(); bytes[21] = 0xC0.toByte() // SOF0
        bytes[22] = 0x00; bytes[23] = 0x08
        bytes[24] = 0x08 // precision
        be16(480).copyInto(bytes, 25)
        be16(640).copyInto(bytes, 27)
        val info = ImageProbe.parse(bytes)!!
        assertEquals("JPEG", info.format)
        assertEquals(640, info.width)
        assertEquals(480, info.height)
    }

    @Test
    fun gifDims() {
        val bytes = "GIF89a".toByteArray() + le16(320) + le16(200) + ByteArray(8)
        val info = ImageProbe.parse(bytes)!!
        assertEquals("GIF", info.format)
        assertEquals(320, info.width)
        assertEquals(200, info.height)
    }

    @Test
    fun bmpDims() {
        val bytes = ByteArray(30)
        bytes[0] = 0x42; bytes[1] = 0x4D
        le32(40).copyInto(bytes, 14)
        le32(100).copyInto(bytes, 18)
        le32(50).copyInto(bytes, 22)
        val info = ImageProbe.parse(bytes)!!
        assertEquals("BMP", info.format)
        assertEquals(100, info.width)
        assertEquals(50, info.height)
    }

    @Test
    fun webpLossyDims() {
        val bytes = ByteArray(34)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        "VP8 ".toByteArray().copyInto(bytes, 12)
        bytes[23] = 0x9D.toByte(); bytes[24] = 0x01; bytes[25] = 0x2A
        le16(550).copyInto(bytes, 26)
        le16(368).copyInto(bytes, 28)
        val info = ImageProbe.parse(bytes)!!
        assertEquals("WebP", info.format)
        assertEquals(550, info.width)
        assertEquals(368, info.height)
    }

    @Test
    fun webpExtendedDims() {
        val bytes = ByteArray(34)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        "VP8X".toByteArray().copyInto(bytes, 12)
        bytes[24] = 99; bytes[25] = 0; bytes[26] = 0 // w-1 = 99
        bytes[27] = 49; bytes[28] = 0; bytes[29] = 0 // h-1 = 49
        val info = ImageProbe.parse(bytes)!!
        assertEquals(100, info.width)
        assertEquals(50, info.height)
    }

    @Test
    fun unknownIsNull() {
        assertNull(ImageProbe.parse(ByteArray(32) { it.toByte() }))
        assertNull(ImageProbe.parse(ByteArray(4)))
    }
}
