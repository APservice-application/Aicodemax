package com.aicodemax.tools.audio

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WavCodecTest {
    @Test
    fun roundTrip16Bit() {
        val src = PcmAudio(8000, 1, FloatArray(800) { i -> (i % 100) / 100f - 0.5f })
        val decoded = (WavCodec.decode(WavCodec.encode(src)) as Outcome.Success<PcmAudio>).value
        assertEquals(8000, decoded.sampleRate)
        assertEquals(1, decoded.channels)
        assertEquals(800, decoded.frames)
        for (i in 0 until 800 step 50) {
            assertEquals(src.samples[i], decoded.samples[i], 0.001f)
        }
    }

    @Test
    fun stereoRoundTrip() {
        val src = PcmAudio(44100, 2, FloatArray(200) { 0.25f })
        val decoded = (WavCodec.decode(WavCodec.encode(src)) as Outcome.Success<PcmAudio>).value
        assertEquals(2, decoded.channels)
        assertEquals(100, decoded.frames)
    }

    @Test
    fun nonWavFailsHonestly() {
        assertTrue(WavCodec.decode(ByteArray(100)) is Outcome.Failure)
        assertTrue(WavCodec.decode("RIFF....NOPE".toByteArray() + ByteArray(40)) is Outcome.Failure)
    }

    @Test
    fun eightBitDecodes() {
        // Minimal 8-bit mono WAV: 4 frames [0,128,255,64].
        val bytes = ByteArray(48)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WAVE".toByteArray().copyInto(bytes, 8)
        "fmt ".toByteArray().copyInto(bytes, 12)
        bytes[16] = 16
        bytes[20] = 1 // PCM
        bytes[22] = 1 // mono
        bytes[24] = 0x40; bytes[25] = 0x1F // 8000 Hz
        bytes[34] = 8 // bits
        "data".toByteArray().copyInto(bytes, 36)
        bytes[40] = 4
        bytes[44] = 0
        bytes[45] = 128.toByte()
        bytes[46] = 255.toByte()
        bytes[47] = 64
        val decoded = (WavCodec.decode(bytes) as Outcome.Success<PcmAudio>).value
        assertEquals(4, decoded.frames)
        assertEquals(-1f, decoded.samples[0], 0.01f)
        assertEquals(0f, decoded.samples[1], 0.01f)
        assertEquals(127 / 128f, decoded.samples[2], 0.01f)
    }
}
