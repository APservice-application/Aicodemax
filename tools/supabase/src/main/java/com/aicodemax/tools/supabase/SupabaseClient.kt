package com.aicodemax.tools.supabase

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CP-68 Supabase connector (§156 "เชื่อม backend ผ่าน connector/tool").
 * The Project URL + API key are the USER's own; the client never stores
 * secrets — a [SupabaseConfigStore] holds them (app-private storage).
 * Devtool only, never app infra (§24).
 */
data class SupabaseConfig(val url: String, val key: String) {
    fun normalized(): SupabaseConfig = copy(url = url.trim().trimEnd('/'), key = key.trim())

    fun validate(): Outcome<Unit> {
        val n = normalized()
        if (n.url.isBlank() || n.key.isBlank()) {
            return Outcome.Failure(AppError("SUPA_NO_CONFIG", "ใส่ Project URL กับ API key ก่อนครับ"))
        }
        if (!n.url.startsWith("http://") && !n.url.startsWith("https://")) {
            return Outcome.Failure(AppError("SUPA_BAD_URL", "URL ต้องขึ้นต้นด้วย http(s)://"))
        }
        return Outcome.Success(Unit)
    }
}

interface SupabaseConfigStore {
    fun load(): SupabaseConfig?
    fun save(config: SupabaseConfig)
    fun clear()
}

class InMemorySupabaseConfigStore : SupabaseConfigStore {
    private var config: SupabaseConfig? = null
    override fun load(): SupabaseConfig? = config
    override fun save(config: SupabaseConfig) {
        this.config = config.normalized()
    }
    override fun clear() {
        config = null
    }
}

data class SupaResponse(val code: Int, val body: String)

/** Transport seam: real HTTPS in production, canned responses in tests. */
interface SupaTransport {
    fun get(baseUrl: String, path: String, key: String): Outcome<SupaResponse>
    fun post(
        baseUrl: String,
        path: String,
        key: String,
        jsonBody: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Outcome<SupaResponse>
}

class JavaNetSupaTransport(private val timeoutMs: Int = 15_000) : SupaTransport {
    override fun get(baseUrl: String, path: String, key: String): Outcome<SupaResponse> =
        runOutcome("SUPA_HTTP") { request("GET", baseUrl, path, key, null, emptyMap()) }

    override fun post(
        baseUrl: String,
        path: String,
        key: String,
        jsonBody: String,
        extraHeaders: Map<String, String>,
    ): Outcome<SupaResponse> =
        runOutcome("SUPA_HTTP") { request("POST", baseUrl, path, key, jsonBody, extraHeaders) }

    private fun request(
        method: String,
        baseUrl: String,
        path: String,
        key: String,
        body: String?,
        extraHeaders: Map<String, String>,
    ): SupaResponse {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("apikey", key)
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Accept", "application/json")
            for ((name, value) in extraHeaders) connection.setRequestProperty(name, value)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            return SupaResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }
}

@Serializable
data class SupaSession(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("refresh_token") val refreshToken: String = "",
)

class SupabaseClient(
    private val transport: SupaTransport,
    private val config: () -> SupabaseConfig? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun cfg(): Outcome<SupabaseConfig> {
        val c = config()?.normalized()
            ?: return Outcome.Failure(AppError("SUPA_NO_CONFIG", "ยังไม่ได้ตั้งค่า — ใส่ Project URL กับ API key ของคุณก่อนครับ"))
        return when (val v = c.validate()) {
            is Outcome.Failure -> v
            is Outcome.Success -> Outcome.Success(c)
        }
    }

    /** Connection test against the Auth health endpoint. */
    fun health(): Outcome<String> {
        val c = when (val r = cfg()) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return transport.get(c.url, "/auth/v1/health", c.key).mapBody("SUPA_HEALTH") {
            "Supabase ตอบกลับปกติ: ${it.take(200)}"
        }
    }

    /** Email/password sign-in; returns the session (caller truncates the token for display). */
    fun signIn(email: String, password: String): Outcome<SupaSession> {
        val c = when (val r = cfg()) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (email.isBlank() || password.isBlank()) {
            return Outcome.Failure(AppError("SUPA_NO_CREDENTIALS", "ใส่อีเมลกับรหัสผ่านก่อนครับ"))
        }
        val payload = "{\"email\":\"${jsonEscape(email.trim())}\",\"password\":\"${jsonEscape(password)}\"}"
        return transport.post(c.url, "/auth/v1/token?grant_type=password", c.key, payload)
            .mapBody("SUPA_AUTH") { json.decodeFromString(SupaSession.serializer(), it) }
    }

    /** PostgREST row query; returns the raw JSON body (devtool: user inspects it). */
    fun query(table: String, select: String = "*", limit: Int = 20): Outcome<String> {
        val c = when (val r = cfg()) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (!TABLE_RE.matches(table.trim())) {
            return Outcome.Failure(AppError("SUPA_BAD_TABLE", "ชื่อตารางใช้ได้แค่ a-z 0-9 _: $table"))
        }
        if (limit !in 1..1000) {
            return Outcome.Failure(AppError("SUPA_BAD_LIMIT", "limit ต้องอยู่ระหว่าง 1-1000"))
        }
        val sel = select.ifBlank { "*" }
        return transport.get(c.url, "/rest/v1/${table.trim()}?select=$sel&limit=$limit", c.key)
            .mapBody("SUPA_QUERY") { it }
    }

    /** PostgREST row insert; [jsonRow] must be one JSON object. */
    fun insert(table: String, jsonRow: String): Outcome<String> {
        val c = when (val r = cfg()) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (!TABLE_RE.matches(table.trim())) {
            return Outcome.Failure(AppError("SUPA_BAD_TABLE", "ชื่อตารางใช้ได้แค่ a-z 0-9 _: $table"))
        }
        if (jsonRow.isBlank()) {
            return Outcome.Failure(AppError("SUPA_NO_ROW", "ใส่ข้อมูล JSON 1 แถวก่อนครับ"))
        }
        return transport.post(
            c.url, "/rest/v1/${table.trim()}", c.key, jsonRow.trim(),
            mapOf("Prefer" to "return=representation"),
        ).mapBody("SUPA_INSERT") {
            if (it.isBlank()) "เพิ่มแถวแล้ว (เซิร์ฟเวอร์ไม่ส่งข้อมูลกลับ)" else it
        }
    }

    private fun jsonEscape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun <T> Outcome<SupaResponse>.mapBody(code: String, parse: (String) -> T): Outcome<T> {
        return when (this) {
            is Outcome.Failure -> this
            is Outcome.Success -> runOutcome(code) {
                val response = value
                if (response.code == 401 || response.code == 403) {
                    throw IllegalStateException("คีย์ไม่ถูกต้องหรือไม่มีสิทธิ์ (${response.code}) — ตรวจ API key ครับ")
                }
                if (response.code !in 200..299) {
                    throw IllegalStateException("Supabase HTTP ${response.code}: ${response.body.take(300)}")
                }
                parse(response.body)
            }
        }
    }

    companion object {
        private val TABLE_RE = Regex("[A-Za-z0-9_]+")
    }
}
