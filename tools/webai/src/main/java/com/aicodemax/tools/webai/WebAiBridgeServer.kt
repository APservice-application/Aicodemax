package com.aicodemax.tools.webai

import com.aicodemax.ai.agents.AgentLoop
import com.aicodemax.ai.agents.AgentRegistry
import com.aicodemax.ai.core.Plan
import com.aicodemax.ai.core.PlanStep
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RunStepDto(
    val toolId: String = "",
    val action: String = "",
    val args: Map<String, String> = emptyMap(),
    val needsPermission: Boolean = false,
)

@Serializable
data class RunRequestDto(
    val agentId: String = "",
    val taskId: String = "",
    val steps: List<RunStepDto> = emptyList(),
)

@Serializable
data class StepResultDto(val ok: Boolean, val output: String = "", val error: String = "")

@Serializable
data class RunResponseDto(
    val ok: Boolean,
    val sessionId: String,
    val completed: Boolean,
    val stepsExecuted: Int,
    val stopReason: String,
    val results: List<StepResultDto>,
)

@Serializable
data class AgentDto(val id: String, val name: String, val capabilities: List<String>)

@Serializable
data class AgentsResponseDto(val agents: List<AgentDto>)

@Serializable
data class LogEntryDto(
    val ts: Long,
    val method: String,
    val path: String,
    val agentId: String = "",
    val sessionId: String = "",
    val code: Int,
    val note: String = "",
)

@Serializable
data class LogResponseDto(val entries: List<LogEntryDto>)

@Serializable
data class ErrorDto(val error: String)

data class BridgeLogEntry(
    val ts: Long,
    val method: String,
    val path: String,
    val agentId: String = "",
    val sessionId: String = "",
    val code: Int,
    val note: String = "",
)

/**
 * CP-69 WebAI bridge (§31): lets a browser/web environment drive local
 * agents. Binds 127.0.0.1 ONLY; every endpoint needs a Bearer token
 * ([BridgeTokenManager]); Origin/Referer must be localhost; each run is
 * capability-checked, sessioned, and logged (ring buffer, last 100).
 */
class WebAiBridgeServer(
    private val agents: AgentRegistry,
    private val tokens: BridgeTokenManager,
    private val maxSteps: Int = 25,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "webai-conn").apply { isDaemon = true }
    }
    private val logLock = Any()
    private val logEntries = ArrayDeque<BridgeLogEntry>()

    val port: Int get() = socket?.localPort ?: -1
    fun isRunning(): Boolean = running.get()

    fun start(port: Int = 0): Outcome<Int> {
        if (running.get()) {
            return Outcome.Failure(AppError("WEBAI_RUNNING", "bridge รันอยู่แล้ว (port ${this.port})"))
        }
        return try {
            val s = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
            socket = s
            running.set(true)
            acceptThread = Thread({ acceptLoop(s) }, "webai-accept").apply { isDaemon = true; start() }
            Outcome.Success(s.localPort)
        } catch (e: Exception) {
            Outcome.Failure(AppError("WEBAI_BIND", "เปิดพอร์ตไม่ได้: ${e.message}"))
        }
    }

    fun stop() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    fun logSnapshot(): List<BridgeLogEntry> = synchronized(logLock) { logEntries.toList() }

    private fun record(entry: BridgeLogEntry) = synchronized(logLock) {
        logEntries.addLast(entry)
        while (logEntries.size > 100) logEntries.removeFirst()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running.get()) {
            try {
                val conn = s.accept()
                pool.execute { handle(conn) }
            } catch (_: Exception) {
                if (!running.get()) return
            }
        }
    }

    private fun handle(conn: Socket) {
        conn.use { socket ->
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            // Read headers byte-wise (bodies may be multi-byte UTF-8, so no Reader here).
            val head = ByteArrayOutputStream()
            val one = ByteArray(1)
            val term = byteArrayOf(13, 10, 13, 10)
            var matched = 0
            var total = 0
            while (total < 65_536) {
                val n = try {
                    input.read(one)
                } catch (_: Exception) {
                    return
                }
                if (n <= 0) return
                head.write(one[0].toInt())
                total++
                matched = if (one[0] == term[matched]) matched + 1 else if (one[0] == term[0]) 1 else 0
                if (matched == 4) break
            }
            if (matched != 4) {
                respond(socket, 400, ErrorDto("headers too large"))
                return
            }
            val lines = head.toString(Charsets.UTF_8.name()).split("\r\n")
            val parts = lines.firstOrNull().orEmpty().trim().split(" ")
            if (parts.size < 2) {
                respond(socket, 400, ErrorDto("bad request"))
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")
            val headers = mutableMapOf<String, String>()
            for (line in lines.drop(1)) {
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0
            val bodyBytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = try {
                    input.read(bodyBytes, read, contentLength - read)
                } catch (_: Exception) {
                    -1
                }
                if (n <= 0) break
                read += n
            }
            route(socket, method, path, headers, String(bodyBytes, 0, read, Charsets.UTF_8))
        }
    }

    private fun route(socket: Socket, method: String, path: String, headers: Map<String, String>, body: String) {
        // Origin restriction: browsers must come from localhost; non-browser clients send no Origin.
        val origin = headers["origin"] ?: headers["referer"]
        if (origin != null && !isLocalOrigin(origin)) {
            record(BridgeLogEntry(clock(), method, path, code = 403, note = "origin rejected"))
            respond(socket, 403, ErrorDto("origin not allowed (localhost only)"))
            return
        }
        val secret = headers["authorization"]?.removePrefix("Bearer")?.trim().orEmpty()
        val grant = when (val v = tokens.validate(secret)) {
            is Outcome.Failure -> {
                record(BridgeLogEntry(clock(), method, path, code = 401, note = "bad token"))
                respond(socket, 401, ErrorDto(v.error.message))
                return
            }
            is Outcome.Success -> v.value
        }
        when {
            method == "GET" && path == "/agents" -> {
                record(BridgeLogEntry(clock(), method, path, code = 200, note = grant.label))
                respond(
                    socket, 200,
                    AgentsResponseDto(agents.list().map { AgentDto(it.id, it.name, it.capabilities) }),
                )
            }
            method == "GET" && path == "/log" -> {
                record(BridgeLogEntry(clock(), method, path, code = 200, note = grant.label))
                respond(
                    socket, 200,
                    LogResponseDto(
                        logSnapshot().map {
                            LogEntryDto(it.ts, it.method, it.path, it.agentId, it.sessionId, it.code, it.note)
                        },
                    ),
                )
            }
            method == "POST" && path == "/agent/run" -> runAgent(socket, grant, body)
            else -> {
                record(BridgeLogEntry(clock(), method, path, code = 404, note = grant.label))
                respond(socket, 404, ErrorDto("unknown endpoint: $method $path"))
            }
        }
    }

    private fun isLocalOrigin(origin: String): Boolean {
        val lower = origin.lowercase()
        return lower.startsWith("http://localhost") || lower.startsWith("https://localhost") ||
            lower.startsWith("http://127.0.0.1") || lower.startsWith("https://127.0.0.1")
    }

    private fun runAgent(socket: Socket, grant: BridgeGrant, body: String) {
        val req = try {
            json.decodeFromString(RunRequestDto.serializer(), body)
        } catch (e: Exception) {
            record(BridgeLogEntry(clock(), "POST", "/agent/run", code = 400, note = "bad json"))
            respond(socket, 400, ErrorDto("body ไม่ใช่ JSON ที่ถูกต้อง"))
            return
        }
        val registered = agents.get(req.agentId)
        if (registered == null) {
            record(BridgeLogEntry(clock(), "POST", "/agent/run", code = 404, note = "no agent"))
            respond(socket, 404, ErrorDto("ไม่พบเอเจนต์: ${req.agentId}"))
            return
        }
        if (req.steps.isEmpty() || req.steps.size > maxSteps) {
            record(BridgeLogEntry(clock(), "POST", "/agent/run", code = 400, note = "bad steps"))
            respond(socket, 400, ErrorDto("steps ต้องมี 1..$maxSteps ขั้น"))
            return
        }
        // Capability restriction: token grant AND agent allowlist must both permit each step.
        for (step in req.steps) {
            if (!grant.allows(step.toolId) || step.toolId !in registered.capabilities) {
                record(
                    BridgeLogEntry(clock(), "POST", "/agent/run", req.agentId, code = 403, note = step.toolId),
                )
                respond(socket, 403, ErrorDto("token/เอเจนต์นี้ใช้ ${step.toolId} ไม่ได้"))
                return
            }
        }
        val sessionId = Ids.newId("wai")
        val taskId = req.taskId.ifBlank { sessionId }
        val plan = Plan(
            req.steps.mapIndexed { i, s ->
                PlanStep("s$i", s.toolId, s.action, s.args, s.needsPermission, "bridge")
            },
        )
        val outcome = try {
            runBlocking { AgentLoop(registered.executor, maxSteps).run(taskId, plan) }
        } catch (e: Exception) {
            record(BridgeLogEntry(clock(), "POST", "/agent/run", req.agentId, sessionId, 500, "crash"))
            respond(socket, 500, ErrorDto("รันเอเจนต์ล้มเหลว: ${e.message}"))
            return
        }
        when (outcome) {
            is Outcome.Failure -> {
                record(BridgeLogEntry(clock(), "POST", "/agent/run", req.agentId, sessionId, 500, "failed"))
                respond(socket, 500, ErrorDto(outcome.error.message))
            }
            is Outcome.Success -> {
                val r = outcome.value
                record(
                    BridgeLogEntry(
                        clock(), "POST", "/agent/run", req.agentId, sessionId, 200,
                        "${r.stepsExecuted} steps ${r.stopReason}",
                    ),
                )
                respond(
                    socket, 200,
                    RunResponseDto(
                        ok = true, sessionId = sessionId, completed = r.completed,
                        stepsExecuted = r.stepsExecuted, stopReason = r.stopReason,
                        results = r.results.map { sr -> StepResultDto(sr.ok, sr.output, sr.error) },
                    ),
                )
            }
        }
    }

    private inline fun <reified T> respond(socket: Socket, code: Int, value: T) {
        val bytes = json.encodeToString(kotlinx.serialization.serializer<T>(), value).toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        else -> "Error"
    }
}
