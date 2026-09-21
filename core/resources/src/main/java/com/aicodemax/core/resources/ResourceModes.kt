package com.aicodemax.core.resources

/** CP-48: offline + low-resource mode derivation (pure policy; UI and engines read it). */
data class ResourceMode(
    val offline: Boolean,
    val lowResource: Boolean,
    val reasons: List<String> = emptyList(),
) {
    val fullPower: Boolean get() = !offline && !lowResource
}

object ResourceModes {
    const val LOW_RAM_BYTES = 300L * 1024 * 1024
    const val LOW_STORAGE_BYTES = 500L * 1024 * 1024
    const val LOW_BATTERY_PERCENT = 15

    fun derive(snapshot: ResourceSnapshot): ResourceMode {
        val reasons = mutableListOf<String>()
        val offline = !snapshot.networkAvailable
        if (offline) reasons.add("ออฟไลน์")
        var low = false
        if (snapshot.ramAvailableBytes < LOW_RAM_BYTES) {
            low = true
            reasons.add("RAM เหลือน้อย")
        }
        if (snapshot.storageAvailableBytes < LOW_STORAGE_BYTES) {
            low = true
            reasons.add("พื้นที่เหลือน้อย")
        }
        if (!snapshot.batteryCharging && snapshot.batteryPercent < LOW_BATTERY_PERCENT) {
            low = true
            reasons.add("แบตต่ำ")
        }
        return ResourceMode(offline, low, reasons)
    }
}
