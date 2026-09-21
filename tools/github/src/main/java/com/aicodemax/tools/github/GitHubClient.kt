package com.aicodemax.tools.github

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** CP-25: GitHub engine (MASTER_ARCHITECTURE — GitHub section). Token comes from a provider (SecretManager wires it); the engine never stores secrets. */
@Serializable
data class GitHubRepo(
    @SerialName("full_name") val fullName: String = "",
    @SerialName("default_branch") val defaultBranch: String = "main",
    val private: Boolean = false,
)

@Serializable
data class GitHubIssue(
    val number: Int = 0,
    val title: String = "",
    val state: String = "",
)

data class HttpResponse(val code: Int, val body: String)

/** Transport seam: real HTTP in production, canned responses in tests. */
interface HttpTransport {
    fun get(path: String, token: String?): Outcome<HttpResponse>
    fun post(path: String, token: String?, jsonBody: String): Outcome<HttpResponse>
}

class JavaNetHttpTransport(
    private val apiBase: String = "https://api.github.com",
    private val timeoutMs: Int = 15_000,
) : HttpTransport {
    override fun get(path: String, token: String?): Outcome<HttpResponse> =
        runOutcome("GITHUB_HTTP") { request("GET", path, token, null) }

    override fun post(path: String, token: String?, jsonBody: String): Outcome<HttpResponse> =
        runOutcome("GITHUB_HTTP") { request("POST", path, token, jsonBody) }

    private fun request(method: String, path: String, token: String?, body: String?): HttpResponse {
        val connection = URL(apiBase.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            return HttpResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }
}

class GitHubClient(
    private val transport: HttpTransport,
    private val token: () -> String? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun repo(owner: String, name: String): Outcome<GitHubRepo> {
        if (owner.isBlank() || name.isBlank()) {
            return Outcome.Failure(AppError("GITHUB_NO_REPO", "owner/name is blank"))
        }
        return transport.get("/repos/$owner/$name", token()).mapBody("GITHUB_REPO") {
            json.decodeFromString(GitHubRepo.serializer(), it)
        }
    }

    fun listIssues(owner: String, name: String, state: String = "open"): Outcome<List<GitHubIssue>> {
        if (owner.isBlank() || name.isBlank()) {
            return Outcome.Failure(AppError("GITHUB_NO_REPO", "owner/name is blank"))
        }
        return transport.get("/repos/$owner/$name/issues?state=$state", token()).mapBody("GITHUB_ISSUES") {
            json.decodeFromString(ListSerializer(GitHubIssue.serializer()), it)
        }
    }

    fun createIssue(owner: String, name: String, title: String, body: String = ""): Outcome<GitHubIssue> {
        if (owner.isBlank() || name.isBlank()) {
            return Outcome.Failure(AppError("GITHUB_NO_REPO", "owner/name is blank"))
        }
        if (title.isBlank()) {
            return Outcome.Failure(AppError("GITHUB_NO_TITLE", "issue title is blank"))
        }
        val payload = "{\"title\":\"${jsonEscape(title)}\",\"body\":\"${jsonEscape(body)}\"}"
        return transport.post("/repos/$owner/$name/issues", token(), payload).mapBody("GITHUB_ISSUE") {
            json.decodeFromString(GitHubIssue.serializer(), it)
        }
    }

    private fun jsonEscape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun <T> Outcome<HttpResponse>.mapBody(code: String, parse: (String) -> T): Outcome<T> {
        return when (this) {
            is Outcome.Failure -> this
            is Outcome.Success -> runOutcome(code) {
                val response = value
                if (response.code !in 200..299) {
                    throw IllegalStateException("GitHub HTTP ${response.code}: ${response.body.take(300)}")
                }
                parse(response.body)
            }
        }
    }
}
