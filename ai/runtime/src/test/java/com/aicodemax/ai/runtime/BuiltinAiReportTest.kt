package com.aicodemax.ai.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinAiReportTest {
    @Test
    fun healthyBuildShowsAllGreen() {
        val lines = BuiltinAiReport(
            nativeAvailable = true,
            nativeError = null,
            deviceAbis = listOf("arm64-v8a"),
            assetPresent = true,
            provisionedBytes = 491_400_032L,
        ).describe().joinToString("\n")
        assertTrue(lines, lines.contains("native lib พร้อม"))
        assertTrue(lines, lines.contains("มีโมเดล AI ใน APK"))
        assertTrue(lines, lines.contains("แตกไฟล์โมเดลแล้ว (491 MB)"))
        assertTrue(lines, !lines.contains("❌"))
    }

    @Test
    fun incompleteBuildWithoutSoSuggestsCleanRebuild() {
        val lines = BuiltinAiReport(
            nativeAvailable = false,
            nativeError = "Dalvik: couldn't find \"libaicode_jni.so\"",
            deviceAbis = listOf("arm64-v8a"),
            assetPresent = false,
            provisionedBytes = null,
        ).describe().joinToString("\n")
        assertTrue(lines, lines.contains("ไม่พบ native lib"))
        assertTrue(lines, lines.contains("clean แล้วบิลด์ใหม่"))
        assertTrue(lines, lines.contains("ไม่มีโมเดลใน APK"))
        // Never the old misleading "Android only" claim.
        assertTrue(lines, !lines.contains("บน Android เท่านั้น"))
    }

    @Test
    fun nonArm64DeviceWarnsAboutAbi() {
        val lines = BuiltinAiReport(
            nativeAvailable = false,
            nativeError = "couldn't find \"libaicode_jni.so\"",
            deviceAbis = listOf("armeabi-v7a", "armeabi"),
            assetPresent = true,
            provisionedBytes = null,
        ).describe().joinToString("\n")
        assertTrue(lines, lines.contains("armeabi-v8a".replace("v8a", "v7a")))
        assertTrue(lines, lines.contains("ไม่ใช่ arm64"))
    }

    @Test
    fun incompleteProvisionedFileReported() {
        val lines = BuiltinAiReport(
            nativeAvailable = true,
            nativeError = null,
            deviceAbis = listOf("arm64-v8a"),
            assetPresent = true,
            provisionedBytes = 12_000_000L,
        ).describe().joinToString("\n")
        assertTrue(lines, lines.contains("ไม่สมบูรณ์ (12 MB)"))
    }

    @Test
    fun unknownAssetSkippedOnJvm() {
        val lines = BuiltinAiReport(
            nativeAvailable = false,
            nativeError = null,
            deviceAbis = emptyList(),
            assetPresent = null,
            provisionedBytes = null,
        ).describe().joinToString("\n")
        assertTrue(lines, lines.contains("ไม่พบ native lib"))
        assertTrue(lines, !lines.contains("LOCAL_BUILD")) // no evidence -> no guess
        assertTrue(lines, !lines.contains("arm64"))
    }
}
