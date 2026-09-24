package com.aicodemax.ai.agents

import com.aicodemax.ai.core.ChatBrain
import com.aicodemax.ai.core.LlmTurn
import com.aicodemax.ai.runtime.AiRuntimeManager
import com.aicodemax.ai.runtime.AiRuntimeState
import com.aicodemax.ai.runtime.FakeAiRuntime
import com.aicodemax.ai.runtime.ModelManager
import com.aicodemax.ai.runtime.ModelPack
import com.aicodemax.ai.runtime.ModelProfile
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.runtime.ModelStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalChatBrainTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ggufBytes(): ByteArray {
        fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun le64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        val out = java.io.ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        out.write(le32(3))
        out.write(le64(0))
        out.write(le64(1))
        val k = "general.architecture"
        out.write(le64(k.length.toLong()))
        out.write(k.toByteArray())
        out.write(le32(8))
        out.write(le64(5))
        out.write("qwen2".toByteArray())
        return out.toByteArray()
    }

    private fun readyManager(scope: CoroutineScope): AiRuntimeManager = runBlocking {
        val profile = ModelProfile("t", "t", "http://x/t.gguf", "t.gguf", "1.0", "Apache-2.0", "Q4_K_M", "qwen2", "0B", pack = ModelPack.DEFAULT)
        val models = ModelManager(
            File(tmp.root, "models/default").path,
            File(tmp.root, "models/optional").path,
            downloader = ModelStore.Downloader { _, dest, _ -> dest.writeBytes(ggufBytes()) },
            profiles = listOf(profile),
        )
        models.install(profile)
        AiRuntimeManager(scope, FakeAiRuntime(script = { "local:$it".take(80) }), models, File(tmp.root, "runtime").path).also {
            it.initialize().join()
        }
    }

    @Test
    fun repliesThroughLocalRuntime(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val brain = LocalChatBrain(readyManager(scope))
            val res = brain.reply("สวัสดี", listOf(LlmTurn("user", "hi"), LlmTurn("assistant", "hey")))
            assertTrue(res is Outcome.Success)
            assertTrue((res as Outcome.Success).value.startsWith("local:"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun offlineExplainsHonestlyWithoutDownloadGate(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val models = ModelManager(
                File(tmp.root, "models/default").path,
                File(tmp.root, "models/optional").path,
                profiles = listOf(ModelProfile("g", "g", "http://x/g.gguf", "g.gguf", "1.0", "Apache-2.0", "Q4_K_M", "qwen2", "0B", pack = ModelPack.DEFAULT)),
            )
            val mgr = AiRuntimeManager(scope, FakeAiRuntime(), models, File(tmp.root, "runtime").path)
            mgr.initialize().join()
            assertEquals(AiRuntimeState.OFFLINE, mgr.state.value)
            val res = LocalChatBrain(mgr).reply("hi", emptyList())
            assertTrue(res is Outcome.Failure)
            // CP-144: honest reason, never a download gate for the core AI.
            val message = (res as Outcome.Failure).error.message
            assertTrue(message.contains("AI ออฟไลน์"))
            assertTrue(!message.contains("model.download"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fallbackUsesSecondaryWhenPrimaryFails() = runBlocking {
        val failing = ChatBrain { _, _ -> Outcome.Failure(AppError("X", "down")) }
        val backup = ChatBrain { text, _ -> Outcome.Success("cloud:$text") }
        val res = FallbackChatBrain(failing, backup).reply("hi", emptyList())
        assertTrue(res is Outcome.Success)
        assertEquals("cloud:hi", (res as Outcome.Success).value)
    }

    @Test
    fun fallbackKeepsPrimarySuccess(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            var backupHit = false
            val backup = ChatBrain { _, _ ->
                backupHit = true
                Outcome.Success("cloud")
            }
            val res = FallbackChatBrain(LocalChatBrain(readyManager(scope)), backup).reply("hi", emptyList())
            assertTrue(res is Outcome.Success)
            assertTrue((res as Outcome.Success).value.startsWith("local:"))
            assertTrue(!backupHit)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fallbackWithoutSecondaryReturnsPrimaryError() = runBlocking {
        val failing = ChatBrain { _, _ -> Outcome.Failure(AppError("X", "down")) }
        val res = FallbackChatBrain(failing, null).reply("hi", emptyList())
        assertTrue(res is Outcome.Failure)
    }
}
