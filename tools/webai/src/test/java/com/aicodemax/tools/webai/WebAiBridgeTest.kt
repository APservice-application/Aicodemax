package com.aicodemax.tools.webai

import com.aicodemax.ai.agents.AgentRegistry
import com.aicodemax.ai.agents.RegisteredAgent
import com.aicodemax.ai.core.AgentExecutor
import com.aicodemax.ai.core.PlanStep
import com.aicodemax.ai.core.StepResult
import com.aicodemax.core.common.Outcome
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAiBridgeTest {
    private class FakeAgentExecutor : AgentExecutor {
        val seen = mutableListOf<PlanStep>()
        override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> {
            seen += step
            return Outcome.Success(StepResult(ok = true, output = "did:${step.toolId}:${step.action}"))
        }
    }

    private data class Fixture(
        val server: WebAiBridgeServer,
        val tokens: BridgeTokenManager,
        val port: Int,
        val secret: String,
        val fake: FakeAgentExecutor,
    )

    private fun fixture(caps: Set<String> = setOf("*")): Fixture {
        val tokens = BridgeTokenManager()
        val fake = FakeAgentExecutor()
        val registry = AgentRegistry()
        registry.register(RegisteredAgent("local", "Local", listOf("files", "terminal"), fake))
        registry.register(RegisteredAgent("files", "File Agent", listOf("files"), fake))
        val server = WebAiBridgeServer(registry, tokens)
        val port = (server.start(0) as Outcome.Success<Int>).value
        val secret = (tokens.issue("test", caps) as Outcome.Success<IssuedBridgeToken>).value.secret
        return Fixture(server, tokens, port, secret, fake)
    }

    private fun http(
        port: Int,
        method: String,
        path: String,
        token: String? = null,
        body: String? = null,
        origin: String? = null,
    ): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (origin != null) conn.setRequestProperty("Origin", origin)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.bufferedWriter().use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader()?.readText().orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun agentsRequiresAuth() {
        val f = fixture()
        try {
            val (c1, _) = http(f.port, "GET", "/agents")
            assertEquals(401, c1)
            val (c2, b2) = http(f.port, "GET", "/agents", token = f.secret)
            assertEquals(200, c2)
            assertTrue(b2.contains("\"local\"") && b2.contains("File Agent"))
        } finally {
            f.server.stop()
        }
    }

    @Test
    fun runOkThroughFake() {
        val f = fixture()
        try {
            val body = "{\"agentId\":\"local\",\"steps\":[{\"toolId\":\"files\",\"action\":\"read\",\"args\":{\"path\":\"a.txt\"}}]}"
            val (code, text) = http(f.port, "POST", "/agent/run", token = f.secret, body = body)
            assertEquals(200, code)
            assertTrue(text, text.contains("\"completed\":true"))
            assertTrue(text, text.contains("did:files:read"))
            assertTrue(text, text.contains("wai_"))
            assertEquals(1, f.fake.seen.size)
            assertEquals("a.txt", f.fake.seen.first().args["path"])
        } finally {
            f.server.stop()
        }
    }

    @Test
    fun capabilityDeniedByToken() {
        val f = fixture(caps = setOf("files"))
        try {
            val body = "{\"agentId\":\"local\",\"steps\":[{\"toolId\":\"terminal\",\"action\":\"exec\"}]}"
            val (code, text) = http(f.port, "POST", "/agent/run", token = f.secret, body = body)
            assertEquals(403, code)
            assertTrue(text.contains("terminal"))
            assertTrue(f.fake.seen.isEmpty())
        } finally {
            f.server.stop()
        }
    }

    @Test
    fun capabilityDeniedByAgentAllowlist() {
        val f = fixture() // token allows all, but File Agent only allows files
        try {
            val body = "{\"agentId\":\"files\",\"steps\":[{\"toolId\":\"terminal\",\"action\":\"exec\"}]}"
            val (code, _) = http(f.port, "POST", "/agent/run", token = f.secret, body = body)
            assertEquals(403, code)
            assertTrue(f.fake.seen.isEmpty())
        } finally {
            f.server.stop()
        }
    }

    /** Raw socket: HttpURLConnection silently drops the Origin header, so origin tests bypass it. */
    private fun rawHttp(port: Int, headLines: String, body: String = ""): Pair<Int, String> {
        val s = java.net.Socket("127.0.0.1", port)
        s.use {
            it.soTimeout = 5_000
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val req = headLines + "\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
            it.getOutputStream().write(req.toByteArray(Charsets.UTF_8) + bodyBytes)
            it.getOutputStream().flush()
            val text = it.getInputStream().readBytes().toString(Charsets.UTF_8)
            val code = text.substringAfter("HTTP/1.1 ").substringBefore(" ").trim().toIntOrNull() ?: -1
            return code to text
        }
    }

    @Test
    fun originRejected() {
        val f = fixture()
        try {
            val evil = "GET /agents HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${f.secret}\r\nOrigin: https://evil.com"
            val (code, _) = rawHttp(f.port, evil)
            assertEquals(403, code)
            val local = "GET /agents HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${f.secret}\r\nOrigin: http://localhost:3000"
            val (code2, _) = rawHttp(f.port, local)
            assertEquals(200, code2)
        } finally {
            f.server.stop()
        }
    }

    @Test
    fun badRequestsAreHonest() {
        val f = fixture()
        try {
            val (c1, _) = http(f.port, "POST", "/agent/run", token = f.secret, body = "not-json")
            assertEquals(400, c1)
            val (c2, t2) = http(f.port, "POST", "/agent/run", token = f.secret, body = "{\"agentId\":\"ghost\",\"steps\":[]}")
            assertEquals(404, c2)
            assertTrue(t2.contains("ghost"))
            val (c3, _) = http(f.port, "GET", "/nope", token = f.secret)
            assertEquals(404, c3)
        } finally {
            f.server.stop()
        }
    }

    @Test
    fun logRecordsRuns() {
        val f = fixture()
        try {
            val body = "{\"agentId\":\"local\",\"steps\":[{\"toolId\":\"files\",\"action\":\"read\"}]}"
            http(f.port, "POST", "/agent/run", token = f.secret, body = body)
            val (code, text) = http(f.port, "GET", "/log", token = f.secret)
            assertEquals(200, code)
            assertTrue(text.contains("/agent/run") && text.contains("\"code\":200"))
            assertTrue(f.server.logSnapshot().any { it.path == "/agent/run" })
        } finally {
            f.server.stop()
        }
    }

    @Test
    fun tokenLifecycle() {
        var now = 1_000_000L
        val tokens = BridgeTokenManager(clock = { now })
        assertTrue(tokens.issue("", setOf("*")) is Outcome.Failure)
        assertTrue(tokens.issue("x", emptySet()) is Outcome.Failure)
        assertTrue(tokens.issue("x", setOf("*"), ttlMs = 1_000) is Outcome.Failure)
        val issued = (tokens.issue("dev", setOf("files")) as Outcome.Success<IssuedBridgeToken>).value
        assertTrue(issued.secret.startsWith("wai_"))
        assertTrue(tokens.validate(issued.secret) is Outcome.Success)
        assertTrue(!tokens.list().any { it.tokenPrefix == issued.secret })
        now += 3_601_000
        val expired = tokens.validate(issued.secret)
        assertTrue(expired is Outcome.Failure)
        assertTrue((expired as Outcome.Failure).error.message.contains("หมดอายุ"))
    }

    @Test
    fun revokeWorks() {
        val tokens = BridgeTokenManager()
        val issued = (tokens.issue("dev", setOf("*")) as Outcome.Success<IssuedBridgeToken>).value
        assertTrue(tokens.validate(issued.secret) is Outcome.Success)
        assertTrue(tokens.revoke(issued.grant.tokenPrefix))
        assertTrue(tokens.validate(issued.secret) is Outcome.Failure)
        assertTrue(!tokens.revoke("wai_deadbeef"))
    }
}
