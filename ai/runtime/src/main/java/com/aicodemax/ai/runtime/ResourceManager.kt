package com.aicodemax.ai.runtime

/**
 * CP-127 (spec Phase 9 + §18–19): device resources, adaptive load options,
 * and memory-pressure levels. Readings come from an injected [ResourceReader]
 * (Android in the app, JVM default here) so everything is unit-testable.
 */
data class DeviceResources(
    val totalRamMb: Long,
    val availRamMb: Long,
    val storageFreeMb: Long,
    val cpuCores: Int,
    val arch64: Boolean,
)

fun interface ResourceReader {
    fun read(): DeviceResources
}

/** Plain-JVM reader (heap stats + disk space + cores). */
class JvmResourceReader(private val probeDir: String = System.getProperty("java.io.tmpdir")) : ResourceReader {
    override fun read(): DeviceResources {
        val rt = Runtime.getRuntime()
        val heapMb = (rt.maxMemory() / (1024 * 1024)).coerceAtLeast(256)
        val heapFreeMb = ((rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / (1024 * 1024)).coerceAtLeast(0)
        val diskMb = try {
            java.io.File(probeDir).usableSpace / (1024 * 1024)
        } catch (_: Exception) {
            0L
        }
        return DeviceResources(
            totalRamMb = heapMb,
            availRamMb = heapFreeMb,
            storageFreeMb = diskMb,
            cpuCores = rt.availableProcessors().coerceAtLeast(1),
            arch64 = System.getProperty("os.arch", "").contains("64"),
        )
    }
}

enum class Pressure { NORMAL, WARNING, CRITICAL }

sealed interface LoadVerdict {
    data class Ok(val opts: LoadOpts) : LoadVerdict
    /** Loadable only with reduced options (low RAM). */
    data class Degrade(val opts: LoadOpts, val reason: String) : LoadVerdict
    data class Refuse(val reason: String) : LoadVerdict
}

class ResourceManager(
    private val reader: ResourceReader,
    private val minCtx: Int = 1024,
    private val maxCtx: Int = 8192,
) {
    fun snapshot(): DeviceResources = reader.read()

    /**
     * Memory-pressure level (§19): CRITICAL when available RAM is below 8%
     * of total or under 300MB; WARNING below 20% or under 800MB.
     */
    fun pressure(res: DeviceResources = snapshot()): Pressure {
        val ratio = if (res.totalRamMb > 0) res.availRamMb.toDouble() / res.totalRamMb else 0.0
        return when {
            res.availRamMb < 300 || ratio < 0.08 -> Pressure.CRITICAL
            res.availRamMb < 800 || ratio < 0.20 -> Pressure.WARNING
            else -> Pressure.NORMAL
        }
    }

    /**
     * Rough working-set estimate (MB): weights × 1.3 (mmap + compute buffers)
     * + ~40MB per 1K context (KV cache for 0.5–1.5B models at Q8).
     */
    fun estimateNeedMb(modelBytes: Long, ctxSize: Int): Long =
        (modelBytes / (1024 * 1024) * 1.3).toLong() + (ctxSize / 1024) * 40

    /** Adaptive options for the device class (before checking a model). */
    fun recommend(res: DeviceResources = snapshot(), wantCtx: Int = 2048): LoadOpts {
        val threads = when {
            res.cpuCores <= 2 -> 2
            res.cpuCores <= 4 -> 4
            else -> minOf(res.cpuCores - 1, 8).coerceAtLeast(4)
        }
        val ctx = when {
            res.totalRamMb < 3072 -> minOf(wantCtx, 1024)
            res.totalRamMb < 6144 -> minOf(wantCtx, 2048)
            else -> wantCtx.coerceIn(minCtx, maxCtx)
        }
        return LoadOpts(ctxSize = ctx, threads = threads)
    }

    /**
     * Can [modelBytes] load with [want]? Returns Ok / Degrade (reduced ctx)
     * / Refuse (even minimal ctx doesn't fit, or non-64-bit CPU).
     */
    fun canLoad(modelBytes: Long, want: LoadOpts, res: DeviceResources = snapshot()): LoadVerdict {
        if (!res.arch64) return LoadVerdict.Refuse("ต้องใช้ CPU 64-bit (arm64)")
        if (estimateNeedMb(modelBytes, want.ctxSize) <= res.availRamMb) return LoadVerdict.Ok(want)
        val minimal = want.copy(ctxSize = minCtx)
        if (estimateNeedMb(modelBytes, minCtx) <= res.availRamMb) {
            return LoadVerdict.Degrade(minimal, "RAM เหลือน้อย — ลด context เหลือ $minCtx")
        }
        val need = estimateNeedMb(modelBytes, minCtx)
        return LoadVerdict.Refuse("RAM ไม่พอ (ต้องการ ~${need}MB เหลือ ${res.availRamMb}MB)")
    }
}
