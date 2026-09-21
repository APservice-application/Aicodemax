package com.aicodemax.tools.github

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubClientTest {
    private class FakeTransport(
        private val responses: Map<String, HttpResponse>,
    ) : HttpTransport {
        val seenTokens = mutableListOf<String?>()
        val posts = mutableListOf<Pair<String, String>>()
        override fun get(path: String, token: String?): Outcome<HttpResponse> {
            seenTokens.add(token)
            return Outcome.Success(responses[path] ?: HttpResponse(404, "{\"message\":\"not found\"}"))
        }
        override fun post(path: String, token: String?, jsonBody: String): Outcome<HttpResponse> {
            seenTokens.add(token)
            posts.add(path to jsonBody)
            return Outcome.Success(responses[path] ?: HttpResponse(404, "{\"message\":\"not found\"}"))
        }
    }

    @Test
    fun repoAndIssuesParse() {
        val transport = FakeTransport(
            mapOf(
                "/repos/o/r" to HttpResponse(200, "{\"full_name\":\"o/r\",\"default_branch\":\"main\",\"private\":false}"),
                "/repos/o/r/issues?state=open" to HttpResponse(
                    200,
                    "[{\"number\":1,\"title\":\"bug\",\"state\":\"open\"},{\"number\":2,\"title\":\"feat\",\"state\":\"open\"}]",
                ),
            ),
        )
        val client = GitHubClient(transport) { "tok-123" }
        val repo = (client.repo("o", "r") as Outcome.Success<GitHubRepo>).value
        assertEquals("o/r", repo.fullName)
        val issues = (client.listIssues("o", "r") as Outcome.Success<List<GitHubIssue>>).value
        assertEquals(2, issues.size)
        assertEquals("bug", issues[0].title)
        assertTrue(transport.seenTokens.all { it == "tok-123" })
    }

    @Test
    fun createIssuePostsJson() {
        val transport = FakeTransport(
            mapOf("/repos/o/r/issues" to HttpResponse(201, "{\"number\":7,\"title\":\"hi\",\"state\":\"open\"}")),
        )
        val client = GitHubClient(transport)
        val issue = (client.createIssue("o", "r", "hi", "body") as Outcome.Success<GitHubIssue>).value
        assertEquals(7, issue.number)
        assertEquals("/repos/o/r/issues", transport.posts.single().first)
        assertTrue(transport.posts.single().second.contains("\"title\":\"hi\""))
        assertTrue(client.createIssue("o", "r", "  ") is Outcome.Failure)
    }

    @Test
    fun httpErrorsSurfaceHonestly() {
        val transport = FakeTransport(mapOf("/repos/o/r" to HttpResponse(401, "{\"message\":\"bad credentials\"}")))
        val client = GitHubClient(transport)
        val result = client.repo("o", "r")
        assertTrue(result is Outcome.Failure)
        assertTrue((result as Outcome.Failure).error.message.contains("401"))
        assertTrue(client.repo("", "") is Outcome.Failure)
    }
}
