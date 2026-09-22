package com.aicodemax.ai.models

import com.aicodemax.core.common.Outcome
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEndpointDetectorTest {
    /** Minimal one-shot HTTP server on plain ServerSocket (Android-safe, no com.sun.*). */
    private fun oneShotJsonServer(body: String): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "oneshot-http") {
            try {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    var line: String?
                    do {
                        line = reader.readLine()
                    } while (line != null && line.isNotEmpty())
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().use { out ->
                        out.write(head.toByteArray(StandardCharsets.UTF_8))
                        out.write(bytes)
                        out.flush()
                    }
                }
            } catch (_: Exception) {
                // Test teardown or refused connection — ignored.
            }
        }
        return server
    }

    @Test
    fun probeFindsRealLocalServer() = runBlocking {
        oneShotJsonServer("""{"data":[{"id":"tiny"}]}""").use { server ->
            val base = "http://127.0.0.1:${server.localPort}/v1"
            val result = LocalEndpointDetector.probe(JavaNetLlmTransport(), base)
            assertTrue(result is Outcome.Success)
            assertTrue((result as Outcome.Success<String>).value.contains("1 models"))
        }
    }

    @Test
    fun unreachableIsHonest() = runBlocking {
        // Port 9 (discard) is virtually never listening — fast refuse, honest failure.
        val result = LocalEndpointDetector.probe(JavaNetLlmTransport(), "http://127.0.0.1:9/v1")
        assertTrue(result is Outcome.Failure)
    }
}
