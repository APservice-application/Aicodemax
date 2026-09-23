package com.aicodemax.tools.debug

/**
 * CP-109: honest on-device micro-benchmarks. Self-contained kernels (no
 * module deps) that mirror real pipeline loops: histogram scan (scopes),
 * sort (timelines/markers), SRT formatting (subtitle export).
 */
data class BenchResult(
    val name: String,
    val iters: Int,
    val medianMs: Double,
) {
    val opsPerSec: Double get() = if (medianMs <= 0) 0.0 else iters * 1000.0 / medianMs
    fun line(): String = "%s: %d รอบ มัธยฐาน %.1fms (%,.0f รอบ/วินาที)".format(name, iters, medianMs, opsPerSec)
}

object PerfBench {
    fun median(samples: List<Double>): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    fun run(name: String, iters: Int, warmup: Int = 1, repeat: Int = 5, fn: (Int) -> Unit): BenchResult {
        repeat(warmup) { fn(iters) }
        val samples = mutableListOf<Double>()
        repeat(repeat) {
            val t0 = System.nanoTime()
            fn(iters)
            samples.add((System.nanoTime() - t0) / 1_000_000.0)
        }
        return BenchResult(name, iters, median(samples))
    }

    /** Kernel suite. [quick] shrinks sizes for chat-speed answers. */
    fun suite(quick: Boolean): List<BenchResult> {
        val scale = if (quick) 4 else 1
        val hist = run("ฮิสโตแกรม", 1_000_000 / scale) { n ->
            val bins = IntArray(256)
            var x = 123456789
            for (i in 0 until n) {
                x = x * 1103515245 + 12345
                bins[(x ushr 16) and 0xFF] += 1
            }
            check(bins.sum() == n)
        }
        val sort = run("เรียงลำดับ", 50_000 / scale) { n ->
            val data = IntArray(n) { (it * 7919) % 100003 }
            data.sort()
            check(data[0] <= data[n - 1])
        }
        val srt = run("ฟอร์แมตซับ", 2_000 / scale) { n ->
            val sb = StringBuilder()
            for (i in 0 until n) {
                sb.append(i + 1).append('\n')
                sb.append("00:00:01,000 --> 00:00:02,000\n")
                sb.append("บรรทัดทดสอบ ").append(i).append("\n\n")
            }
            check(sb.length > n * 10)
        }
        return listOf(hist, sort, srt)
    }

    fun format(results: List<BenchResult>): String =
        "เบนช์มาร์กเครื่องนี้:\n" + results.joinToString("\n") { "• " + it.line() }
}
