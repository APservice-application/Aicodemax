package com.aicodemax.ai.core

import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleVerifierTest {
    private val verifier: Verifier = RuleVerifier()

    @Test
    fun allOkPasses() = runBlocking {
        val result = verifier.verify(listOf(StepResult(true, "a"), StepResult(true, "b")))
        assertTrue(result is Outcome.Success)
    }

    @Test
    fun anyFailureFails() = runBlocking {
        val result = verifier.verify(listOf(StepResult(true, "a"), StepResult(false, error = "nope")))
        assertTrue(result is Outcome.Failure)
        assertEquals("VERIFY_FAILED", (result as Outcome.Failure).error.code)
    }
}
