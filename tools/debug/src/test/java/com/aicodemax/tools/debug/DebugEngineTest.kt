package com.aicodemax.tools.debug

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugEngineTest {
    private val trace = """
        java.lang.NullPointerException: user is null
            at com.example.app.UserStore.load(UserStore.kt:42)
            at com.example.app.Main.onStart(Main.kt:17)
            at android.app.Activity.performCreate(Activity.java:8000)
            at java.lang.reflect.Method.invoke(Native Method)
    """.trimIndent()

    @Test
    fun parsesFramesAndFindsSuspect() {
        val frames = StackTraceParser.parse(trace)
        assertEquals(4, frames.size)
        assertEquals("Native Method", frames[3].file)
        assertEquals(-1, frames[3].line)
        assertEquals("com.example.app.UserStore", frames[0].className)
        assertEquals(42, frames[0].line)

        val finding = (DebugSession().analyze(trace) as Outcome.Success<DebugFinding>).value
        assertEquals("com.example.app.UserStore", finding.suspect?.className)
        assertTrue(finding.summary.contains("NullPointerException"))
        assertEquals(2, finding.appFrames.size)
    }

    @Test
    fun frameworkOnlyTraceHasNoSuspect() {
        val text = "java.lang.RuntimeException: boom\n\tat java.lang.Thread.run(Thread.java:840)"
        val finding = (DebugSession().analyze(text) as Outcome.Success<DebugFinding>).value
        assertEquals(null, finding.suspect)
        assertTrue(finding.summary.contains("framework-only"))
    }

    @Test
    fun blankOrFramelessFailsHonestly() {
        assertTrue(DebugSession().analyze("   ") is Outcome.Failure)
        assertTrue(DebugSession().analyze("just a log line") is Outcome.Failure)
    }
}
