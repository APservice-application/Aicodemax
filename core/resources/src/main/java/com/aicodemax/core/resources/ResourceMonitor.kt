package com.aicodemax.core.resources

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.StatFs
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ResourceMonitor {
    suspend fun snapshot(): Outcome<ResourceSnapshot>
    suspend fun canRun(requirement: ResourceRequirement): Outcome<ResourceCheck>
}

class AndroidResourceMonitor(private val context: Context) : ResourceMonitor {
    override suspend fun snapshot(): Outcome<ResourceSnapshot> =
        withContext(Dispatchers.IO) {
            runOutcome("RESOURCE_SNAPSHOT") {
                val activityManager =
                    context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)

                val statFs = StatFs(context.filesDir.absolutePath)
                val storageAvailable = statFs.availableBytes
                val storageTotal = statFs.totalBytes

                val batteryIntent: Intent? =
                    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

                val connectivity =
                    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = connectivity.activeNetwork
                val caps = network?.let { connectivity.getNetworkCapabilities(it) }
                val netAvailable =
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val unmetered =
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

                ResourceSnapshot(
                    ramAvailableBytes = memInfo.availMem,
                    ramTotalBytes = memInfo.totalMem,
                    storageAvailableBytes = storageAvailable,
                    storageTotalBytes = storageTotal,
                    batteryPercent = batteryPercent,
                    batteryCharging = charging,
                    networkAvailable = netAvailable,
                    networkUnmetered = unmetered,
                )
            }
        }

    override suspend fun canRun(requirement: ResourceRequirement): Outcome<ResourceCheck> {
        return when (val snap = snapshot()) {
            is Outcome.Failure -> snap
            is Outcome.Success -> {
                val s = snap.value
                val reasons = mutableListOf<String>()
                if (s.ramAvailableBytes < requirement.minRamBytes) reasons.add("low RAM")
                if (s.storageAvailableBytes < requirement.minStorageBytes) reasons.add("low storage")
                if (requirement.requiresCharging && !s.batteryCharging) reasons.add("not charging")
                if (requirement.requiresUnmetered && !s.networkUnmetered) reasons.add("metered network")
                Outcome.Success(ResourceCheck(reasons.isEmpty(), reasons))
            }
        }
    }
}
