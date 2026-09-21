package com.aicodemax.ai.tasks

import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryLadderTest {
    private val policy = RecoveryLadderPolicy(maxAttempts = 3)

    private fun failure(attempt: Int, capability: String = "files.read") =
        StepFailure("s1", capability, "boom", attempt)

    @Test
    fun ladderOrder() {
        assertTrue(policy.decide(failure(0)) is RecoveryStep.Retry)
        // files.read has no fallback -> Repair.
        assertTrue(policy.decide(failure(1)) is RecoveryStep.Repair)
        // terminal.exec has a fallback -> SwitchEngine.
        val switched = policy.decide(failure(1, "terminal.exec"))
        assertTrue(switched is RecoveryStep.SwitchEngine)
        assertTrue(policy.decide(failure(2)) is RecoveryStep.RestoreLatest)
    }

    @Test
    fun neverRetriesForever() {
        // attempt 3 == maxAttempts -> Abort (Escalate only when maxAttempts > 3).
        assertTrue(policy.decide(failure(3)) is RecoveryStep.Abort)
        assertTrue(policy.decide(failure(99)) is RecoveryStep.Abort)
        val generous = RecoveryLadderPolicy(maxAttempts = 5)
        assertTrue(generous.decide(failure(3)) is RecoveryStep.Escalate)
        assertTrue(generous.decide(failure(5)) is RecoveryStep.Abort)
    }
}
