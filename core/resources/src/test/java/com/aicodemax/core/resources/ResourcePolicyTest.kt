package com.aicodemax.core.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourcePolicyTest {
    private fun snap(
        ramAvail: Long = 2L * 1024 * 1024 * 1024,
        storageAvail: Long = 8L * 1024 * 1024 * 1024,
        battery: Int = 80,
        charging: Boolean = false,
        net: Boolean = true,
        unmetered: Boolean = true,
    ) = ResourceSnapshot(
        ramAvailableBytes = ramAvail,
        ramTotalBytes = 4L * 1024 * 1024 * 1024,
        storageAvailableBytes = storageAvail,
        storageTotalBytes = 64L * 1024 * 1024 * 1024,
        batteryPercent = battery,
        batteryCharging = charging,
        networkAvailable = net,
        networkUnmetered = unmetered,
    )

    @Test
    fun healthyAllows() {
        val (decision, reasons) = ResourcePrecheck.decide(snap(), ResourceRequirement(minRamBytes = 256L * 1024 * 1024))
        assertEquals(ResourceDecision.ALLOW, decision)
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun impossibleDenies() {
        val (d1, _) = ResourcePrecheck.decide(snap(ramAvail = 10), ResourceRequirement(minRamBytes = 1024))
        assertEquals(ResourceDecision.DENY, d1)

        val (d2, r2) = ResourcePrecheck.decide(snap(storageAvail = 10), ResourceRequirement())
        assertEquals(ResourceDecision.DENY, d2)
        assertTrue(r2.any { it.contains("storage") })

        val (d3, _) = ResourcePrecheck.decide(snap(battery = 3), ResourceRequirement())
        assertEquals(ResourceDecision.DENY, d3)
    }

    @Test
    fun tightDefers() {
        val min = 1024L * 1024 * 1024
        val (d1, _) = ResourcePrecheck.decide(snap(ramAvail = min + 100), ResourceRequirement(minRamBytes = min))
        assertEquals(ResourceDecision.DEFER, d1)

        val (d2, _) = ResourcePrecheck.decide(snap(battery = 10), ResourceRequirement())
        assertEquals(ResourceDecision.DEFER, d2)

        val (d3, r3) = ResourcePrecheck.decide(
            snap(unmetered = false),
            ResourceRequirement(requiresUnmetered = true),
        )
        assertEquals(ResourceDecision.DEFER, d3)
        assertTrue(r3.any { it.contains("metered") })
    }
}
