package com.aicodemax.ai.models

import com.aicodemax.core.common.Outcome
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEndpointDetectorTest {
    @Test
    fun probeFindsRealLocalServer() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/v1/models") { exchange ->
                val body = """{"data":[{"id":"tiny"}]}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}/v1"
            val result = LocalEndpointDetector.probe(JavaNetLlmTransport(), base)
            assertTrue(result is Outcome.Success)
            assertTrue((result as Outcome.Success<String>).value.contains("1 models"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun unreachableIsHonest() = runBlocking {
        // Port 9 (discard) is virtually never listening — fast refuse, honest failure.
        val result = LocalEndpointDetector.probe(JavaNetLlmTransport(), "http://127.0.0.1:9/v1")
        assertTrue(result is Outcome.Failure)
    }
}
