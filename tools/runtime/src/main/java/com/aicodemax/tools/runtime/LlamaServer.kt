package com.aicodemax.tools.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * CP-120: serve the bootstrap model via the embedded llama-server binary.
 * Pure JVM — process control ([ProcCtl]) is injected; HTTP uses
 * java.net only. All failures are honest Outcome.Failure.
 */
object LlamaServer {
    const val DEFAULT_PORT = 8080
    const val DEFAULT_HOST = "127.0.0.1"

    data class ServeOpts(
        val port: Int = DEFAULT_PORT,
        val host: String = DEFAULT_HOST,
        val threads: Int = 4,
        val ctxSize: Int = 2048,
    ) {
        fun baseUrl(): String = "http://$host:$port"
    }

    /** Opaque handle to a started server process. */
    data class Handle(val id: Long, val baseUrl: String)

    fun interface ProcCtl {
        fun start(exe: String, args: List<String>): Long
        fun stop(id: Long) {}
        fun alive(id: Long): Boolean = true
    }

    fun argv(modelPath: String, opts: ServeOpts): List<String> = listOf(
        "-m", modelPath,
        "--host", opts.host,
        "--port", opts.port.toString(),
        "-t", opts.threads.toString(),
        "-c", opts.ctxSize.toString(),
    )

    fun serve(
        llamaExe: String?,
        modelPath: String,
        proc: ProcCtl,
        opts: ServeOpts = ServeOpts(),
    ): Outcome<Handle> = runOutcome("LLAMA_SERVE") {
        if (llamaExe.isNullOrBlank() || !File(llamaExe).isFile) {
            throw IllegalStateException("native llama-server ยังไม่ฝังในเครื่องนี้ (ต้อง build ที่ฝัง toolchain)")
        }
        if (!File(modelPath).isFile) throw IllegalArgumentException("ไม่พบไฟล์โมเดล: $modelPath (โหลดก่อนด้วย model.download)")
        val id = proc.start(llamaExe, argv(modelPath, opts))
        Handle(id, opts.baseUrl())
    }

    fun stop(handle: Handle, proc: ProcCtl): Outcome<Unit> = runOutcome("LLAMA_STOP") {
        proc.stop(handle.id)
    }

    private fun httpGet(url: String, timeoutMs: Int): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "Aicodemax/1.0")
        }
        return try {
            val code = conn.responseCode
            val body = try {
                conn.inputStream.readBytes().toString(Charsets.UTF_8)
            } catch (_: Exception) {
                try {
                    conn.errorStream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                } catch (_: Exception) {
                    ""
                }
            }
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(url: String, json: String, timeoutMs: Int): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Aicodemax/1.0")
        }
        return try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = try {
                conn.inputStream.readBytes().toString(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
            code to body
        } finally {
            conn.disconnect()
        }
    }

    /** llama-server /health returns {"status":"ok"} (or error/loading). */
    fun health(baseUrl: String, timeoutMs: Int = 5_000): Boolean = try {
        val (code, body) = httpGet("$baseUrl/health", timeoutMs)
        code == 200 && body.contains("\"ok\"")
    } catch (_: Exception) {
        false
    }

    /** Minimal JSON string extractor (handles \" \\ \n escapes) — no JSON dep. */
    fun extractJsonString(json: String, key: String): String? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        if (i < 0) return null
        i = json.indexOf(':', i + needle.length)
        if (i < 0) return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < json.length) {
                when (json[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = json.substring(i + 2, minOf(i + 6, json.length))
                        val cp = hex.toIntOrNull(16)
                        if (cp != null) {
                            sb.append(cp.toChar())
                            i += 4
                        } else sb.append(hex)
                    }
                    else -> sb.append(json[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return null
    }

    fun escapeJson(text: String): String = buildString {
        for (c in text) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }

    /**
     * Chat-style prompt through llama-server /chat/completions
     * (OpenAI-compatible). Returns the assistant text.
     */
    fun ask(
        baseUrl: String,
        prompt: String,
        system: String = "You are a helpful assistant. Reply in Thai.",
        timeoutMs: Int = 120_000,
    ): Outcome<String> = runOutcome("LLAMA_ASK") {
        if (prompt.isBlank()) throw IllegalArgumentException("missing prompt")
        val body = """{"messages":[{"role":"system","content":"${escapeJson(system)}"},""" +
            """{"role":"user","content":"${escapeJson(prompt)}"}],"stream":false}"""
        val (code, res) = try {
            httpPost("$baseUrl/v1/chat/completions", body, timeoutMs)
        } catch (e: Exception) {
            throw IllegalStateException("ต่อ llama-server ไม่ได้ ($baseUrl): ${e.message}")
        }
        if (code != 200) throw IllegalStateException("llama-server HTTP $code: ${res.take(200)}")
        extractJsonString(res, "content")
            ?: throw IllegalStateException("ตอบกลับผิดรูปแบบ: ${res.take(200)}")
    }
}
