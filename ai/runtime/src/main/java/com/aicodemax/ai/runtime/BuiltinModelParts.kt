package com.aicodemax.ai.runtime

/**
 * CP-147: model-parts protocol. AGP cannot buffer the historical 2.5GB
 * asset (`compressDebugAssets`: "Required array size too large"), so Gradle
 * ships the current 1.7B model as 512MiB parts + manifest and the app joins them on first launch
 * (streaming — never held in memory). Pure logic, unit-tested; the Android
 * [assets] calls live in the provisioner.
 */
object BuiltinModelParts {
    const val ASSET_DIR = "ai"
    const val PART_PREFIX = "builtin-model-part"
    const val PART_SUFFIX = ".gguf"
    const val MANIFEST_PATH = "ai/builtin-model.manifest"

    data class Manifest(
        val parts: Int,
        val totalBytes: Long,
        val modelId: String? = null,
        val sha256: String? = null,
    )

    fun parseManifest(text: String): Manifest? {
        val kv = text.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) null
            else t.substringBefore("=").trim() to t.substringAfter("=").trim()
        }.toMap()
        val parts = kv["parts"]?.toIntOrNull() ?: return null
        val total = kv["total"]?.toLongOrNull() ?: return null
        if (parts <= 0 || parts > 64 || total <= 0L) return null
        val modelId = kv["model"]?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }
        val sha256 = kv["sha256"]?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        if (("model" in kv && modelId == null) || ("sha256" in kv && sha256 == null)) return null
        return Manifest(parts, total, modelId, sha256)
    }

    fun partName(index: Int): String = "$PART_PREFIX%02d$PART_SUFFIX".format(index)

    /** Asset file names in join order, or null when any part is missing. */
    fun orderedParts(manifest: Manifest, listed: List<String>): List<String>? {
        val names = (0 until manifest.parts).map { partName(it) }
        if (!listed.containsAll(names)) return null
        return names
    }
}
