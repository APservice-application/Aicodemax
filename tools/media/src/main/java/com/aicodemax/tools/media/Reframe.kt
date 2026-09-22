package com.aicodemax.tools.media

import com.aicodemax.data.media.ClipTransform

/**
 * CP-88 §33: subject-aware pan-scan math (pure, JVM-tested).
 * Output is a [ClipTransform] crop window — the render pipeline already honors it.
 */
object Reframe {
    val ASPECTS: Map<String, Pair<Int, Int>> = mapOf(
        "16:9" to (16 to 9),
        "9:16" to (9 to 16),
        "1:1" to (1 to 1),
        "4:5" to (4 to 5),
    )

    /**
     * Cover-crop window for [aspect], centered on the subject point ([subjectX]/[subjectY]
     * in 0..1), clamped into frame. [punch] 1..3 zooms in further.
     */
    fun plan(
        srcW: Int,
        srcH: Int,
        aspect: String,
        subjectX: Double = 0.5,
        subjectY: Double = 0.5,
        punch: Double = 1.0,
    ): ClipTransform {
        val (aw, ah) = ASPECTS[aspect]
            ?: throw IllegalArgumentException("สัดส่วนไม่รองรับ $aspect (มี ${ASPECTS.keys.joinToString()})")
        if (srcW < 1 || srcH < 1) throw IllegalArgumentException("ขนาดต้นฉบับไม่ถูก")
        if (subjectX !in 0.0..1.0 || subjectY !in 0.0..1.0) {
            throw IllegalArgumentException("จุดสนใจต้องอยู่ 0..1")
        }
        if (punch < 1.0 || punch > 3.0) throw IllegalArgumentException("punch ต้องอยู่ 1..3")
        val target = aw.toDouble() / ah
        val srcAspect = srcW.toDouble() / srcH
        var cw: Double
        var ch: Double
        if (srcAspect > target) {
            ch = srcH.toDouble()
            cw = ch * target
        } else {
            cw = srcW.toDouble()
            ch = cw / target
        }
        cw /= punch
        ch /= punch
        var cx = subjectX * srcW - cw / 2
        var cy = subjectY * srcH - ch / 2
        cx = cx.coerceIn(0.0, (srcW - cw).coerceAtLeast(0.0))
        cy = cy.coerceIn(0.0, (srcH - ch).coerceAtLeast(0.0))
        return ClipTransform(
            cropX = (cx / srcW * 100).toInt().coerceIn(0, 99),
            cropY = (cy / srcH * 100).toInt().coerceIn(0, 99),
            cropW = (cw / srcW * 100).toInt().coerceIn(1, 100),
            cropH = (ch / srcH * 100).toInt().coerceIn(1, 100),
        )
    }
}
