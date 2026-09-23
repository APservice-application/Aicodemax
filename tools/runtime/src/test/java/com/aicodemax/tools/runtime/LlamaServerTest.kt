package com.aicodemax.tools.runtime

import com.aicodemax.core.common.Outcome
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LlamaServerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun argvHasModelAndPort() {
        val args = LlamaServer.argv("/m/q.gguf", LlamaServer.ServeOpts(port = 9999, threads = 2))
        assertEquals(listOf("-m", "/m/q.gguf", "--host", "127.0.0.1", "--port", "9999", "-t", "2", "-c", "2048"), args)
    }

    @Test
    fun extractJsonStringUnescapes() {
        val json = """{"content":"สวัสดี\n\"ครับ\" \\ \u0e01"}"""
        assertEquals("สวัสดี\n\"ครับ\" \\ ก", LlamaServer.extractJsonString(json, "content"))
    }

    @Test
    fun extractJsonStringMissingKeyIsNull() {
        assertEquals(null, LlamaServer.extractJsonString("""{"a":1}""", "content"))
    }

    @Test
    fun escapeJsonRoundTrips() {
        val text = "a\"b\\c\nd\te"
        assertEquals(text, LlamaServer.extractJsonString("""{"x":"${LlamaServer.escapeJson(text)}"}""", "x"))
    }

    @Test
    fun serveWithoutBinaryIsHonest() {
        val model = tmp.newFile("q.gguf")
        val outcome = LlamaServer.serve(null, model.path, LlamaServer.ProcCtl { _, _ -> 1 })
        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error.message.contains("llama-server"))
    }

    @Test
    fun serveWithoutModelIsHonest() {
        val exe = tmp.newFile("libllama-server.so")
        val outcome = LlamaServer.serve(exe.path, tmp.root.path + "/no.gguf", LlamaServer.ProcCtl { _, _ -> 1 })
        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error.message.contains("model.download"))
    }

    @Test
    fun healthFalseWhenNobodyHome() {
        assertTrue(!LlamaServer.health("http://127.0.0.1:9", timeoutMs = 500))
    }

    private fun fakeServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex ->
            val body = """{"status":"ok"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.readBytes()
            val body = """{"choices":[{"message":{"role":"assistant","content":"สวัสดีครับ"}}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }

    @Test
    fun healthTrueAgainstFakeServer() {
        val server = fakeServer()
        try {
            assertTrue(LlamaServer.health("http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun askParsesAssistantText() {
        val server = fakeServer()
        try {
            val outcome = LlamaServer.ask("http://127.0.0.1:${server.address.port}", "hello")
            assertTrue(outcome is Outcome.Success)
            assertEquals("สวัสดีครับ", (outcome as Outcome.Success).value)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun askBlankPromptIsHonest() {
        val outcome = LlamaServer.ask("http://127.0.0.1:9", "  ")
        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error.message.contains("prompt"))
    }
}
