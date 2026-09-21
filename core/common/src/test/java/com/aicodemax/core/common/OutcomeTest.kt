package com.aicodemax.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeTest {
    @Test
    fun successHelpers() {
        val ok: Outcome<Int> = Outcome.Success(41)
        assertTrue(ok.isSuccess())
        assertFalse(ok.isFailure())
        assertEquals(42, ok.map { it + 1 }.getOrNull())
        assertEquals("v=41", ok.fold({ "v=$it" }, { "err" }))
    }

    @Test
    fun failurePropagatesThroughMap() {
        val err = AppError("E1", "boom")
        val fail: Outcome<Int> = Outcome.Failure(err)
        assertTrue(fail.isFailure())
        assertNull(fail.getOrNull())
        val mapped = fail.map { it + 1 }
        assertEquals(err, (mapped as Outcome.Failure).error)
        assertEquals("boom", fail.fold({ "v=$it" }, { it.message }))
    }

    @Test
    fun runOutcomeCatchesThrowable() {
        val result = runOutcome("TEST") { throw IllegalStateException("kaput") }
        assertTrue(result.isFailure())
        val error = (result as Outcome.Failure).error
        assertEquals("TEST", error.code)
        assertEquals("kaput", error.message)
    }
}
