package com.aicodemax.core.common

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Single-owner zip primitive (shared by File Engine archive + BackupManager).
 * Unzip is zip-slip guarded: entries can never escape [destDir].
 */
object ZipArchive {
    fun zipDir(sourceDir: File, outFile: File): Outcome<Int> = runOutcome("ZIP_WRITE") {
        outFile.parentFile?.mkdirs()
        var count = 0
        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = sourceDir.toPath().relativize(file.toPath()).toString()
                    .replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
                count += 1
            }
        }
        count
    }

    /** Zips several roots; each root's entries are prefixed with its [sources] key. */
    fun zipDirs(sources: Map<String, File>, outFile: File): Outcome<Int> = runOutcome("ZIP_WRITE") {
        outFile.parentFile?.mkdirs()
        var count = 0
        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            for ((name, sourceDir) in sources) {
                check(!name.contains('/') && !name.contains('\\') && name.isNotBlank()) {
                    "bad archive section: '$name'"
                }
                if (!sourceDir.isDirectory) continue
                sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = sourceDir.toPath().relativize(file.toPath()).toString()
                        .replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry("$name/$relative"))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count += 1
                }
            }
        }
        count
    }

    fun unzip(zipFile: File, destDir: File): Outcome<Int> = runOutcome("ZIP_READ") {
        check(zipFile.isFile) { "zip not found: ${zipFile.path}" }
        destDir.mkdirs()
        val base = destDir.canonicalPath
        var count = 0
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val target = File(destDir, entry.name)
                check(target.canonicalPath == base || target.canonicalPath.startsWith("$base/")) {
                    "unsafe zip entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).buffered().use { input ->
                        target.outputStream().buffered().use { input.copyTo(it) }
                    }
                    count += 1
                }
            }
        }
        count
    }
}
