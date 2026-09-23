package com.aicodemax.tools.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallValidatorTest {
    private val bindings = StandardCapabilities.bindings()

    @Test
    fun validCallPasses() {
        val r = ToolCallValidator.validate("files", "read", mapOf("path" to "a.txt"), bindings)
        assertEquals(ToolCallValidator.Result.Valid, r)
    }

    @Test
    fun optionalArgMayBeOmitted() {
        val r = ToolCallValidator.validate("files", "write", mapOf("path" to "a.txt"), bindings)
        assertEquals(ToolCallValidator.Result.Valid, r)
    }

    @Test
    fun unknownActionSuggestsSameToolActions() {
        val r = ToolCallValidator.validate("files", "frobnicate", emptyMap(), bindings) as ToolCallValidator.Result.Invalid
        assertTrue(r.errors.single().contains("read"))
    }

    @Test
    fun unknownToolListsTools() {
        val r = ToolCallValidator.validate("nope", "x", emptyMap(), bindings) as ToolCallValidator.Result.Invalid
        assertTrue(r.errors.single().contains("files"))
    }

    @Test
    fun missingRequiredArgReported() {
        val r = ToolCallValidator.validate("files", "read", emptyMap(), bindings) as ToolCallValidator.Result.Invalid
        assertTrue(r.errors.single().contains("missing arg: path"))
    }

    @Test
    fun unmetPreconditionBlocks() {
        val r = ToolCallValidator.validate("media", "asset.probe", mapOf("path" to "v.mp4"), bindings) { false }
        val invalid = r as ToolCallValidator.Result.Invalid
        assertTrue(invalid.errors.single().contains("native.ffmpeg"))
    }

    @Test
    fun metPreconditionPasses() {
        val r = ToolCallValidator.validate("media", "asset.probe", mapOf("path" to "v.mp4"), bindings) { true }
        assertEquals(ToolCallValidator.Result.Valid, r)
    }

    @Test
    fun highRiskNeedsApproval() {
        // files.delete carries fs.delete → high.
        val r = ToolCallValidator.validate("files", "delete", mapOf("path" to "a.txt"), bindings)
        val approval = r as ToolCallValidator.Result.NeedsApproval
        assertTrue(approval.reason.contains("files.delete"))
    }

    @Test
    fun retryPolicyStopsAfterMax() {
        assertTrue(ValidationRetry.shouldRetry(1))
        assertTrue(ValidationRetry.shouldRetry(3))
        assertTrue(!ValidationRetry.shouldRetry(4))
        val invalid = ToolCallValidator.Result.Invalid(listOf("missing arg: path"))
        assertTrue(ValidationRetry.feedback(invalid, 1).contains("ลองใหม่"))
        assertTrue(ValidationRetry.feedback(invalid, 4).contains("หยุด"))
    }
}
