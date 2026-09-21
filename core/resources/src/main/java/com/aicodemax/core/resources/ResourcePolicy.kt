package com.aicodemax.core.resources

data class ResourceSnapshot(
    val ramAvailableBytes: Long,
    val ramTotalBytes: Long,
    val storageAvailableBytes: Long,
    val storageTotalBytes: Long,
    val batteryPercent: Int,
    val batteryCharging: Boolean,
    val networkAvailable: Boolean,
    val networkUnmetered: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

data class ResourceRequirement(
    val minRamBytes: Long = 0,
    val minStorageBytes: Long = 0,
    val requiresCharging: Boolean = false,
    val requiresUnmetered: Boolean = false,
)

data class ResourceCheck(val ok: Boolean, val reasons: List<String> = emptyList())

/**
 * CP-04: precheck decisions (MASTER_ARCHITECTURE §31).
 * PRECHECK → ESTIMATE → ALLOW / DEFER / DENY. Pure logic; the monitor
 * supplies snapshots, callers escalate (reduce load → switch model →
 * pause → ask user) on DEFER/DENY.
 */
enum class ResourceDecision { ALLOW, DEFER, DENY }

data class ResourcePolicy(
    val minStorageBytes: Long = 100L * 1024 * 1024,
    val criticalBatteryPct: Int = 5,
    val lowBatteryPct: Int = 15,
    /** Headroom multiplier: RAM below min×this (but above min) means DEFER. */
    val headroomMultiplier: Double = 1.5,
)

object ResourcePrecheck {
    fun decide(
        snapshot: ResourceSnapshot,
        requirement: ResourceRequirement,
        policy: ResourcePolicy = ResourcePolicy(),
    ): Pair<ResourceDecision, List<String>> {
        val deny = mutableListOf<String>()
        val defer = mutableListOf<String>()

        if (snapshot.storageAvailableBytes < policy.minStorageBytes) {
            deny.add("storage critically low (${snapshot.storageAvailableBytes} bytes)")
        }
        if (snapshot.ramAvailableBytes < requirement.minRamBytes) {
            deny.add("RAM ${snapshot.ramAvailableBytes} < required ${requirement.minRamBytes}")
        }
        if (snapshot.batteryPercent in 0..policy.criticalBatteryPct && !snapshot.batteryCharging) {
            deny.add("battery critical (${snapshot.batteryPercent}%)")
        }
        if (deny.isNotEmpty()) return ResourceDecision.DENY to deny

        val headroom = (requirement.minRamBytes * policy.headroomMultiplier).toLong()
        if (requirement.minRamBytes > 0 && snapshot.ramAvailableBytes < headroom) {
            defer.add("RAM tight (${snapshot.ramAvailableBytes} bytes)")
        }
        if (snapshot.batteryPercent in 0..policy.lowBatteryPct && !snapshot.batteryCharging) {
            defer.add("battery low (${snapshot.batteryPercent}%)")
        }
        if (requirement.requiresCharging && !snapshot.batteryCharging) {
            defer.add("waiting for charger")
        }
        if (requirement.requiresUnmetered && snapshot.networkAvailable && !snapshot.networkUnmetered) {
            defer.add("metered network")
        }
        if (defer.isNotEmpty()) return ResourceDecision.DEFER to defer
        return ResourceDecision.ALLOW to emptyList()
    }
}
