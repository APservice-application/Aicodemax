package com.aicodemax.tools.voice

import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryVoicePortTest {
    @Test
    fun listenReplaysScriptInOrder(): Unit = runBlocking {
        val port = InMemoryVoicePort()
        port.feed(VoiceInput("สวัสดี", "th-TH", 0.9f))
        port.feed(VoiceInput("hello", "en-US", 0.8f))
        assertEquals("สวัสดี", (port.listen() as Outcome.Success<VoiceInput>).value.text)
        assertEquals("hello", (port.listen("en-US") as Outcome.Success<VoiceInput>).value.text)
        assertTrue(port.listen() is Outcome.Failure)
    }

    @Test
    fun unavailableEnginesFailHonestly(): Unit = runBlocking {
        val port = InMemoryVoicePort(VoiceStatus(sttAvailable = false, ttsAvailable = false))
        assertTrue(port.listen() is Outcome.Failure)
        assertTrue(port.speak("hi") is Outcome.Failure)
        val status = (port.status() as Outcome.Success<VoiceStatus>).value
        assertTrue(!status.sttAvailable && !status.ttsAvailable)
    }

    @Test
    fun speakRecordsTextAndLang(): Unit = runBlocking {
        val port = InMemoryVoicePort()
        assertTrue(port.speak("  ") is Outcome.Failure)
        assertTrue(port.speak("สวัสดี", "th-TH") is Outcome.Success)
        assertEquals(listOf("สวัสดี" to "th-TH"), port.spoken)
        assertTrue(port.stop() is Outcome.Success)
        assertEquals(1, port.stopCalls)
    }
}
