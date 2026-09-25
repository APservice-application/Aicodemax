package com.aicodemax.tools.supabase_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.supabase.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for supabase (actions: health/auth/query/insert). */
class SupabaseToolExecutor(private val client: SupabaseClient) : ToolExecutor {
    override val toolId: String = "supabase"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "health", "status" -> {
                    client.health().fold(
                        onSuccess = { done(true, it) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "auth" -> {
                    val email = call.args["email"].orEmpty()
                    val password = call.args["password"].orEmpty()
                    if (email.isBlank() || password.isBlank()) {
                        return@withContext done(false, error = "missing args: email + password")
                    }
                    client.signIn(email, password).fold(
                        onSuccess = {
                            // Never print the full token; prefix + expiry is enough to verify.
                            done(true, "ล็อกอินสำเร็จ (token ${it.accessToken.take(12)}… หมดอายุใน ${it.expiresIn}s)")
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "query" -> {
                    val table = call.args["table"].orEmpty()
                    if (table.isBlank()) {
                        return@withContext done(false, error = "missing arg: table")
                    }
                    val select = call.args["select"] ?: "*"
                    val limit = call.args["limit"]?.toIntOrNull() ?: 20
                    client.query(table, select, limit).fold(
                        onSuccess = { done(true, "แถวจาก $table (${it.length} ตัวอักษร):\n${it.take(2000)}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "insert" -> {
                    val table = call.args["table"].orEmpty()
                    val row = call.args["row"].orEmpty()
                    if (table.isBlank() || row.isBlank()) {
                        return@withContext done(false, error = "missing args: table + row (JSON object)")
                    }
                    client.insert(table, row).fold(
                        onSuccess = { done(true, "เพิ่มแถวใน $table แล้ว: ${it.take(1000)}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}' (have: health/auth/query/insert)")
            }
        }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
