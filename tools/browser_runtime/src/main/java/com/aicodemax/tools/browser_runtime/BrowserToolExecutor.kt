package com.aicodemax.tools.browser_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.browser.BrowserPort
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for browser (tabs + CP-115 page automation: read/click/type/probe). */
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
                "read" -> {
                    val tabId = call.args["tabId"] ?: latestTab()
                        ?: return@withContext done(false, error = "ยังไม่มีแท็บ — เปิดเว็บก่อน")
                    browser.pageText(tabId).fold(
                        onSuccess = { done(true, it.ifBlank { "(หน้าว่าง)" }) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "click" -> {
                    val tabId = call.args["tabId"] ?: latestTab()
                        ?: return@withContext done(false, error = "ยังไม่มีแท็บ — เปิดเว็บก่อน")
                    val selector = call.args["selector"]
                        ?: return@withContext done(false, error = "missing arg: selector (เช่น #btn หรือ input[name=q])")
                    browser.click(tabId, selector).fold(
                        onSuccess = { done(true, it) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "type" -> {
                    val tabId = call.args["tabId"] ?: latestTab()
                        ?: return@withContext done(false, error = "ยังไม่มีแท็บ — เปิดเว็บก่อน")
                    val selector = call.args["selector"]
                        ?: return@withContext done(false, error = "missing arg: selector")
                    val text = call.args["text"]
                        ?: return@withContext done(false, error = "missing arg: text")
                    browser.typeText(tabId, selector, text).fold(
                        onSuccess = { done(true, it) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "probe" -> {
                    val tabId = call.args["tabId"] ?: latestTab()
                        ?: return@withContext done(false, error = "ยังไม่มีแท็บ — เปิดเว็บก่อน")
                    browser.probeLogin(tabId).fold(
                        onSuccess = { done(true, if (it) "LOGGED_IN" else "UNKNOWN") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}'")
            }
        }

    private suspend fun latestTab(): String? =
        (browser.listTabs() as? Outcome.Success)?.value?.lastOrNull()?.id

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
