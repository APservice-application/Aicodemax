package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.ClipColor

/**
 * CP-81 §42 AI-lite: honest no-ML auto color (gray-world white balance +
 * histogram levels) from a sampled frame. Offline-first, dependency-free.
 */
data class ColorRequest(
    val assetPath: String,
    /** Sample position in source ms. */
    val atMs: Long,
)

data class ColorAnalysis(
    /** Suggested grade (only nonzero fields were detected). */
    val suggest: ClipColor,
    /** Thai explanation of what was detected. */
    val notes: String,
)

interface ColorPort {
    suspend fun analyze(request: ColorRequest): Outcome<ColorAnalysis>
}

/** JVM/test double: always suggests neutral with a sample note. */
class InMemoryColorPort : ColorPort {
    override suspend fun analyze(request: ColorRequest): Outcome<ColorAnalysis> =
        Outcome.Success(
            ColorAnalysis(
                ClipColor(temperature = 5, whites = 8, blacks = -5),
                "ตัวอย่าง: ปรับอุ่น+5 ขาว+8 ดำ-5 (ตัวอย่างทดสอบ ไม่ได้วัดจากเฟรมจริง)",
            ),
        )
}
