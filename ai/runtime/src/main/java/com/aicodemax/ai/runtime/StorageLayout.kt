package com.aicodemax.ai.runtime

import java.io.File

/**
 * CP-125 (spec §23): canonical on-device storage layout.
 *
 * filesDir/
 *   runtime/            native runtime state (active model marker, flags)
 *   models/default/     default model pack
 *   models/optional/    optional model packs
 *   tools/              tool data (reserved)
 *   workspaces/         user workspaces (migrated from legacy workspace/)
 *   cache/              disposable caches
 *   logs/               logs (reserved; audit log keeps its own dir)
 *   agent/              agent state (reserved)
 *   projects/           (reserved — media projects keep their own dir for now)
 */
class StorageLayout(val root: File) {
    val runtime: File get() = File(root, "runtime")
    val modelsDefault: File get() = File(root, "models/default")
    val modelsOptional: File get() = File(root, "models/optional")
    val tools: File get() = File(root, "tools")
    val workspaces: File get() = File(root, "workspaces")
    val cache: File get() = File(root, "cache")
    val logs: File get() = File(root, "logs")
    val agent: File get() = File(root, "agent")

    fun ensureDirs(): StorageLayout {
        listOf(runtime, modelsDefault, modelsOptional, tools, workspaces, cache, logs, agent)
            .forEach { it.mkdirs() }
        return this
    }

    data class Migration(val moved: List<String>)

    /**
     * One-way migration from the pre-§23 layout. Idempotent and
     * non-destructive (never deletes; skips when the target already exists).
     */
    fun migrate(): Migration {
        ensureDirs()
        val moved = mutableListOf<String>()
        // Legacy: models/<file>.gguf directly under models/ → models/default/.
        val legacyModels = File(root, "models")
        legacyModels.listFiles()
            ?.filter { it.isFile && it.extension == "gguf" }
            ?.forEach { file ->
                val target = File(modelsDefault, file.name)
                if (!target.exists() && file.renameTo(target)) {
                    moved.add("${file.name} → models/default/")
                }
            }
        // Legacy: workspace/ → workspaces/ (merge, keep both on conflict).
        val legacyWorkspace = File(root, "workspace")
        if (legacyWorkspace.isDirectory) {
            legacyWorkspace.listFiles()?.forEach { child ->
                val target = File(workspaces, child.name)
                if (!target.exists() && child.renameTo(target)) {
                    moved.add("workspace/${child.name} → workspaces/")
                }
            }
            if (legacyWorkspace.listFiles()?.isEmpty() == true) legacyWorkspace.delete()
        }
        return Migration(moved)
    }
}
