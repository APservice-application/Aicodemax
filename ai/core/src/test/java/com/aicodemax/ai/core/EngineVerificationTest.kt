package com.aicodemax.ai.core

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineVerificationTest {
    private fun check(id: String, result: Outcome<String>) = object : EngineCheck {
        override val engineId = id
        override suspend fun check(): Outcome<String> = result
    }

    @Test
    fun collectsPassAndFail() = runBlocking {
        val engine = VerificationEngine(
            listOf(
                check("files", Outcome.Success("3 files listed")),
                check("git", Outcome.Failure(AppError("GIT_X", "not a repo"))),
            ),
        )
        val report = (engine.verifyAll() as Outcome.Success<VerificationReport>).value
        assertTrue(!report.allOk)
        assertEquals(1, report.failures.size)
        assertEquals("git", report.failures[0].engineId)
        assertTrue(report.failures[0].evidence.contains("not a repo"))
    }

    @Test
    fun singleEngineAndUnknown() = runBlocking {
        val engine = VerificationEngine(listOf(check("files", Outcome.Success("ok"))))
        val one = (engine.verifyEngine("files") as Outcome.Success<EngineCheckResult>).value
        assertTrue(one.passed)
        assertTrue(engine.verifyEngine("ghost") is Outcome.Failure)
        val empty = (VerificationEngine().verifyAll() as Outcome.Success<VerificationReport>).value
        assertTrue(empty.allOk)
    }
}
