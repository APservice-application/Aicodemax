package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InterimLlamaServerRuntimeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fakeServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex ->
            val body = """{"status":"ok"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.readBytes()
            val body = """{"choices":[{"message":{"role":"assistant","content":"ผ่านเซิร์ฟเวอร์ชั่วคราว"}}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }

    @Test
    fun loadRequiresRunningServer() {
        val model = tmp.newFile("q.gguf").apply { writeBytes(ByteArray(8)) }
        val rt = InterimLlamaServerRuntime(baseUrl = { null })
        val outcome = rt.loadModel(model.path)
        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error.message.contains("ไม่รัน"))
    }

    @Test
    fun generateRoundTripsThroughFakeServer() {
        val server = fakeServer()
        try {
            val model = tmp.newFile("q.gguf").apply { writeBytes(ByteArray(8)) }
            val rt = InterimLlamaServerRuntime(baseUrl = { "http://127.0.0.1:${server.address.port}" })
            assertTrue(rt.loadModel(model.path) is Outcome.Success)
            val pieces = mutableListOf<String>()
            val gen = rt.generate("hi", onToken = TokenSink { pieces.add(it) }) as Outcome.Success
            assertEquals("ผ่านเซิร์ฟเวอร์ชั่วคราว", gen.value.text)
            assertEquals(1, pieces.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun unloadClearsModel() {
        val server = fakeServer()
        try {
            val model = tmp.newFile("q.gguf").apply { writeBytes(ByteArray(8)) }
            val rt = InterimLlamaServerRuntime(baseUrl = { "http://127.0.0.1:${server.address.port}" })
            rt.loadModel(model.path)
            assertTrue(rt.isModelLoaded())
            rt.unloadModel()
            assertTrue(!rt.isModelLoaded())
        } finally {
            server.stop(0)
        }
    }
}
