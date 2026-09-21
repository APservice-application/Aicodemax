package com.aicodemax.core.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceModesTest {
    private fun snapshot(
        ram: Long = 2L * 1024 * 1024 * 1024,
        storage: Long = 8L * 1024 * 1024 * 1024,
        battery: Int = 80,
        charging: Boolean = false,
        network: Boolean = true,
    ) = ResourceSnapshot(
        ramAvailableBytes = ram,
        ramTotalBytes = 8L * 1024 * 1024 * 1024,
        storageAvailableBytes = storage,
        storageTotalBytes = 64L * 1024 * 1024 * 1024,
        batteryPercent = battery,
        batteryCharging = charging,
        networkAvailable = network,
        networkUnmetered = true,
    )

    @Test
    fun fullPowerWhenHealthy() {
        val mode = ResourceModes.derive(snapshot())
        assertTrue(mode.fullPower)
        assertTrue(mode.reasons.isEmpty())
    }

    @Test
    fun offlineAndLowResourceDetected() {
        val offline = ResourceModes.derive(snapshot(network = false))
        assertTrue(offline.offline)
        assertTrue(!offline.fullPower)

        val lowRam = ResourceModes.derive(snapshot(ram = 100L * 1024 * 1024))
        assertTrue(lowRam.lowResource)

        val lowBattery = ResourceModes.derive(snapshot(battery = 10))
        assertTrue(lowBattery.lowResource)

        val chargingIgnoresBattery = ResourceModes.derive(snapshot(battery = 10, charging = true))
        assertTrue(!chargingIgnoresBattery.lowResource)

        val lowStorage = ResourceModes.derive(snapshot(storage = 100L * 1024 * 1024))
        assertTrue(lowStorage.lowResource)
        assertEquals(1, lowStorage.reasons.size)
    }
}
