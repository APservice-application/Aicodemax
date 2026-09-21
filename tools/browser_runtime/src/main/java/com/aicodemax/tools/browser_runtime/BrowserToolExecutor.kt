package com.aicodemax.tools.browser_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.browser.BrowserPort
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for browser (actions: open/close/list/navigate). */
class BrowserToolExecutor(private val browser: BrowserPort) : ToolExecutor {
    override val toolId: String = "browser"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "open" -> {
                    val url = call.args["url"]
                        ?: return@withContext done(false, error = "missing arg: url")
                    browser.openTab(url).fold(
                        onSuccess = { done(true, "opened ${it.id} ${it.url}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "close" -> {
                    val tabId = call.args["tabId"]
                        ?: return@withContext done(false, error = "missing arg: tabId")
                    browser.closeTab(tabId).fold(
                        onSuccess = { done(true, "closed $tabId") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "list" -> browser.listTabs().fold(
                    onSuccess = { tabs ->
                        done(true, tabs.joinToString("\n") { "${it.id} ${it.url} ${it.title}" }
                            .ifBlank { "(no tabs)" })
                    },
                    onFailure = { done(false, error = it.message) },
                )
                "navigate" -> {
                    val tabId = call.args["tabId"]
                        ?: return@withContext done(false, error = "missing arg: tabId")
                    val url = call.args["url"]
                        ?: return@withContext done(false, error = "missing arg: url")
                    browser.navigate(tabId, url).fold(
                        onSuccess = { done(true, "navigated ${it.id} ${it.url}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}'")
            }
        }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
