package com.aicodemax.tools.webai

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import java.security.SecureRandom

/** A validated grant: what one bridge token may do, and until when. */
data class BridgeGrant(
    val tokenPrefix: String,
    val label: String,
    /** Tool ids this token may call, or "*" for all. */
    val capabilities: Set<String>,
    val expiresAtMs: Long,
    val createdAtMs: Long,
) {
    fun allows(toolId: String): Boolean = "*" in capabilities || toolId in capabilities
}

/** Issued once: the secret is shown to the user a single time, never stored in plain text elsewhere. */
data class IssuedBridgeToken(val secret: String, val grant: BridgeGrant)

/** CP-69 §31 token manager: issue / validate / revoke / expire. Default-deny, thread-safe. */
class BridgeTokenManager(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val lock = Any()
    private val secrets = mutableMapOf<String, BridgeGrant>()
    private val random = SecureRandom()

    fun issue(label: String, capabilities: Set<String>, ttlMs: Long = 3_600_000): Outcome<IssuedBridgeToken> {
        if (label.isBlank()) {
            return Outcome.Failure(AppError("WEBAI_NO_LABEL", "ตั้งชื่อ token ก่อนครับ"))
        }
        val caps = capabilities.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (caps.isEmpty()) {
            return Outcome.Failure(AppError("WEBAI_NO_CAPS", "ระบุความสามารถอย่างน้อย 1 อย่าง (เช่น files หรือ *)"))
        }
        if (ttlMs !in 60_000..86_400_000) {
            return Outcome.Failure(AppError("WEBAI_BAD_TTL", "อายุ token ต้องอยู่ระหว่าง 1 นาที - 24 ชั่วโมง"))
        }
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val secret = "wai_" + bytes.joinToString("") { "%02x".format(it) }
        val now = clock()
        val grant = BridgeGrant(secret.take(12), label.trim(), caps, now + ttlMs, now)
        synchronized(lock) { secrets[secret] = grant }
        return Outcome.Success(IssuedBridgeToken(secret, grant))
    }

    fun validate(secret: String): Outcome<BridgeGrant> {
        // Look up BEFORE purging so an expired (but once-valid) token reports
        // "expired" honestly instead of "invalid".
        val grant = synchronized(lock) { secrets[secret] }
        if (grant == null) {
            purgeExpired()
            return Outcome.Failure(AppError("WEBAI_AUTH", "token ไม่ถูกต้อง"))
        }
        if (clock() >= grant.expiresAtMs) {
            synchronized(lock) { secrets.remove(secret) }
            return Outcome.Failure(AppError("WEBAI_EXPIRED", "token หมดอายุแล้ว — ออก token ใหม่ครับ"))
        }
        return Outcome.Success(grant)
    }

    fun revoke(tokenPrefix: String): Boolean {
        synchronized(lock) {
            val key = secrets.keys.firstOrNull { it.startsWith(tokenPrefix) } ?: return false
            secrets.remove(key)
            return true
        }
    }

    /** Grant metadata only — secrets are never listed. */
    fun list(): List<BridgeGrant> {
        purgeExpired()
        return synchronized(lock) { secrets.values.sortedBy { it.createdAtMs } }
    }

    private fun purgeExpired() {
        val now = clock()
        synchronized(lock) { secrets.keys.removeAll { secrets[it]!!.expiresAtMs <= now } }
    }
}
