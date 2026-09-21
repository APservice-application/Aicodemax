package com.aicodemax.tools.runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

data class PackageInfo(
    val name: String,
    val version: String,
    val sizeBytes: Long = 0,
)

/**
 * CP-17: package manager (MASTER_ARCHITECTURE §25).
 * Ecosystem backends (pip/npm/…) plug in behind [PackageBackend];
 * validation and honest failures live here, not in the AI.
 */
interface PackageBackend {
    val ecosystem: String
    fun search(query: String): Outcome<List<PackageInfo>>
    fun install(name: String, version: String? = null): Outcome<PackageInfo>
    fun remove(name: String): Outcome<Unit>
    fun installed(): Outcome<List<PackageInfo>>
}

class PackageManager(private val backend: PackageBackend) {
    val ecosystem: String get() = backend.ecosystem

    fun search(query: String): Outcome<List<PackageInfo>> {
        if (query.isBlank()) {
            return Outcome.Failure(AppError("PKG_NO_QUERY", "search query is blank"))
        }
        return backend.search(query.trim())
    }

    fun install(plan: InstallPlan): Outcome<List<PackageInfo>> {
        val done = mutableListOf<PackageInfo>()
        for (req in plan.toInstall) {
            when (val result = backend.install(req.name, req.minVersion)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> done.add(result.value)
            }
        }
        return Outcome.Success(done)
    }

    fun remove(name: String): Outcome<Unit> {
        if (name.isBlank()) {
            return Outcome.Failure(AppError("PKG_NO_NAME", "package name is blank"))
        }
        return backend.remove(name.trim())
    }

    fun installed(): Outcome<List<PackageInfo>> = backend.installed()
}

/** In-memory backend for tests, dev, and offline catalogs. */
class InMemoryPackageBackend(
    override val ecosystem: String = "dev",
    catalog: List<PackageInfo> = emptyList(),
) : PackageBackend {
    private val available = catalog.associateBy { it.name.lowercase() }.toMutableMap()
    private val installedPkgs = mutableMapOf<String, PackageInfo>()

    override fun search(query: String): Outcome<List<PackageInfo>> =
        Outcome.Success(available.values.filter { it.name.contains(query, ignoreCase = true) })

    override fun install(name: String, version: String?): Outcome<PackageInfo> {
        val pkg = available[name.lowercase()]
            ?: return Outcome.Failure(AppError("PKG_UNKNOWN", "package '$name' not in $ecosystem catalog"))
        val pinned = pkg.copy(version = version ?: pkg.version)
        installedPkgs[pinned.name.lowercase()] = pinned
        return Outcome.Success(pinned)
    }

    override fun remove(name: String): Outcome<Unit> {
        return if (installedPkgs.remove(name.lowercase()) != null) {
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(AppError("PKG_NOT_INSTALLED", "package '$name' is not installed"))
        }
    }

    override fun installed(): Outcome<List<PackageInfo>> =
        Outcome.Success(installedPkgs.values.sortedBy { it.name })
}
