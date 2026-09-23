package com.aicodemax.app

import android.webkit.WebView
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.browser.BrowserPort
import com.aicodemax.tools.browser.InMemoryBrowserPort
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray

/**
 * CP-115: page automation on the visible WebView (§166 Website Automation).
 *
 * Delegates tab state to [InMemoryBrowserPort]; page ops (read/click/type/probe)
 * run JavaScript on the live WebView that BrowserScreen registers. No WebView
 * attached (Browser screen closed) → honest BROWSER_NEEDS_WEBVIEW.
 *
 * Human-auth boundary (§30): password fields are refused (both Kotlin-side and
 * JS-side checks); probeLogin returns a boolean only (old-app WebAI pattern).
 * Selectors/text travel URL-encoded so quotes can never break out of the script.
 */
class AndroidBrowserPort(
    private val inner: InMemoryBrowserPort,
    private val view: () -> WebView?,
    private val jsTimeoutMs: Long = 15_000,
) : BrowserPort by inner {

    override suspend fun pageText(tabId: String): Outcome<String> {
        if (checkTab(tabId) == null) return tabError(tabId)
        return eval("(function(){var b=document.body;return b?b.innerText.slice(0,20000):'';})()")
    }

    override suspend fun click(tabId: String, selector: String): Outcome<String> {
        if (checkTab(tabId) == null) return tabError(tabId)
        if (selector.isBlank()) {
            return Outcome.Failure(AppError("BROWSER_NO_SELECTOR", "selector ว่าง — เช่น #search-btn หรือ input[name=q]"))
        }
        // CSS selector first, then button/link text fallback (Thai labels work too).
        val script = "(function(){var q=decodeURIComponent('" + enc(selector) + "');" +
            "var el=null;try{el=document.querySelector(q);}catch(e){el=null;}" +
            "if(!el){var cands=document.querySelectorAll('a,button,input[type=submit],input[type=button]');" +
            "for(var i=0;i<cands.length;i++){var tx=(cands[i].innerText||cands[i].value||'');" +
            "if(tx&&tx.indexOf(q)>=0){el=cands[i];break;}}}" +
            "if(!el)return 'NOT_FOUND';el.click();return 'CLICKED:'+el.tagName;})()"
        return eval(script)
    }

    override suspend fun typeText(tabId: String, selector: String, text: String): Outcome<String> {
        if (checkTab(tabId) == null) return tabError(tabId)
        if (selector.isBlank()) {
            return Outcome.Failure(AppError("BROWSER_NO_SELECTOR", "selector ว่าง — เช่น input[name=q]"))
        }
        // Human-auth boundary: never type into password fields (§30).
        if (selector.lowercase().contains("password") || selector.contains("pass")) {
            return Outcome.Failure(
                AppError("BROWSER_AUTH_REFUSED", "ไม่พิมพ์ในช่องรหัสผ่าน — ให้มนุษย์ล็อกอินเอง (§30)"),
            )
        }
        val script = "(function(){var q=decodeURIComponent('" + enc(selector) + "');" +
            "var el=null;try{el=document.querySelector(q);}catch(e){el=null;}" +
            "if(!el){var cands=document.querySelectorAll('input,textarea');" +
            "for(var i=0;i<cands.length;i++){var lb=(cands[i].placeholder||cands[i].name||'');" +
            "if(lb&&lb.indexOf(q)>=0){el=cands[i];break;}}}" +
            "if(!el)return 'NOT_FOUND';" +
            "if(el.type==='password')return 'REFUSED_PASSWORD';" +
            "el.focus();el.value=decodeURIComponent('" + enc(text) + "');" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));return 'TYPED:'+el.tagName;})()"
        return eval(script).fold(
            onSuccess = {
                if (it == "REFUSED_PASSWORD") {
                    Outcome.Failure(
                        AppError("BROWSER_AUTH_REFUSED", "ไม่พิมพ์ในช่องรหัสผ่าน — ให้มนุษย์ล็อกอินเอง (§30)"),
                    )
                } else {
                    Outcome.Success(it)
                }
            },
            onFailure = { Outcome.Failure(it) },
        )
    }

    override suspend fun probeLogin(tabId: String): Outcome<Boolean> {
        if (checkTab(tabId) == null) return tabError(tabId)
        val script = "(function(){var t=document.body?document.body.innerText:'';" +
            "var logged=/logout|sign out|ออกจากระบบ/i.test(t);" +
            "var hasPass=!!document.querySelector('input[type=password]');" +
            "return (logged&&!hasPass)?'LOGGED_IN':'UNKNOWN';})()"
        return eval(script).fold(
            onSuccess = { Outcome.Success(it == "LOGGED_IN") },
            onFailure = { Outcome.Failure(it) },
        )
    }

    private fun tabError(tabId: String): Outcome<Nothing> =
        Outcome.Failure(AppError("TAB_UNKNOWN", "tab '$tabId' not found"))

    private suspend fun checkTab(tabId: String): Boolean? {
        val tabs = (inner.listTabs() as? Outcome.Success)?.value.orEmpty()
        return if (tabs.any { it.id == tabId }) true else null
    }

    /** URL-encode for safe embedding inside decodeURIComponent('...'). */
    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * Run [script] on the UI thread, wait for the result (IO-thread caller).
     * evaluateJavascript returns a JSON-encoded value — decode via org.json.
     */
    private suspend fun eval(script: String): Outcome<String> {
        val webView = view()
            ?: return Outcome.Failure(
                AppError("BROWSER_NEEDS_WEBVIEW", "เปิดจอ Browser ก่อน — ระบบอัตโนมัติทำงานบนหน้าเว็บที่เห็นจริงเท่านั้น"),
            )
        var result: String? = null
        val done = CountDownLatch(1)
        webView.post {
            runCatching {
                webView.evaluateJavascript(script) { value ->
                    result = decodeJsValue(value)
                    done.countDown()
                }
            }.onFailure {
                done.countDown()
            }
        }
        val finished = try {
            done.await(jsTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            return Outcome.Failure(AppError("BROWSER_JS_TIMEOUT", "JS เกิน ${jsTimeoutMs}ms"))
        }
        return Outcome.Success(result.orEmpty())
    }

    private fun decodeJsValue(value: String?): String {
        if (value.isNullOrBlank() || value == "null") return ""
        return runCatching {
            JSONArray("[$value]").optString(0, "")
        }.getOrDefault(value)
    }
}
