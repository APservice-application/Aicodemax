package com.aicodemax.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.aicodemax.ai.runtime.DeviceResources
import com.aicodemax.ai.runtime.ResourceReader

/**
 * CP-127: Android [ResourceReader] — real RAM (ActivityManager), real free
 * storage (StatFs on filesDir), CPU cores, 64-bit ABI check.
 */
class AndroidResourceReader(private val context: Context) : ResourceReader {
    override fun read(): DeviceResources {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val stat = StatFs(context.filesDir.path)
        return DeviceResources(
            totalRamMb = mem.totalMem / (1024 * 1024),
            availRamMb = mem.availMem / (1024 * 1024),
            storageFreeMb = stat.availableBytes / (1024 * 1024),
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            arch64 = Build.SUPPORTED_ABIS.any { it.contains("64") },
        )
    }
}
