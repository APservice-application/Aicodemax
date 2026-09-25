package com.aicodemax.ai.runtime

/**
 * CP-145: precise built-in-AI diagnostics.
 *
 * A locally-built APK can miss the CI-packed pieces (native `.so` and/or the
 * model asset). Instead of the misleading "Android only" message, the status
 * card shows this checklist so the user knows exactly what is missing and
 * how to fix it (clean + rebuild — embedding is automatic since CP-146).
 *
 * Pure Kotlin (inputs collected by the app layer) — JVM-tested.
 */
data class BuiltinAiReport(
    val nativeAvailable: Boolean,
    val nativeError: String?,
    val deviceAbis: List<String>,
    /** Null when the app layer did not check (e.g. plain JVM). */
    val assetPresent: Boolean?,
    /** Null when no provisioned file exists. */
    val provisionedBytes: Long?,
) {
    fun describe(): List<String> {
        val lines = mutableListOf<String>()
        if (nativeAvailable) {
            lines += "✅ native lib พร้อม"
        } else {
            lines += "❌ ไม่พบ native lib (libaicode_jni.so) ใน APK นี้"
            val err = nativeError.orEmpty()
            if (err.contains("couldn't find", ignoreCase = true) ||
                err.contains("find library", ignoreCase = true)
            ) {
                // CP-146: any normal Gradle build embeds the .so automatically, so a
                // missing .so means an incomplete/broken build — clean + rebuild.
                lines += "→ บิลด์ไม่สมบูรณ์ (ปกติ Gradle ฝัง .so ให้อัตโนมัติ) — ลอง clean แล้วบิลด์ใหม่"
            }
            val abi = deviceAbis.firstOrNull().orEmpty()
            if (deviceAbis.isNotEmpty() && "arm64-v8a" !in deviceAbis) {
                lines += "⚠️ เครื่องนี้ ($abi) ไม่ใช่ arm64 — native รองรับ arm64 เท่านั้น"
            }
        }
        when (assetPresent) {
            true -> lines += "✅ มีโมเดล AI ใน APK"
            false -> lines += "❌ ไม่มีโมเดลใน APK (assets/ai/builtin-model-part*.gguf)"
            null -> {}
        }
        val bytes = provisionedBytes
        if (bytes == null) {
            lines += "· ยังไม่แตกไฟล์โมเดล"
        } else if (bytes < 100_000_000L) {
            lines += "❌ ไฟล์โมเดลที่แตกไว้ไม่สมบูรณ์ (${bytes / 1_000_000} MB)"
        } else {
            lines += "✅ แตกไฟล์โมเดลแล้ว (${bytes / 1_000_000} MB)"
        }
        return lines
    }
}
