package com.aicodemax.tools.runtime

/**
 * CP-16: dependency resolver (MASTER_ARCHITECTURE §24).
 * Pure requirement matching — the AI never guesses dependencies:
 * REQUEST → check installed → check version → install plan → (CP-17 executes).
 */
data class DepRequirement(val name: String, val minVersion: String? = null)
data class InstalledDep(val name: String, val version: String)
data class InstallPlan(val toInstall: List<DepRequirement>, val satisfied: List<String>)

object DependencyResolver {
    fun resolve(requirements: List<DepRequirement>, installed: List<InstalledDep>): InstallPlan {
        val byName = installed.associateBy { it.name.lowercase() }
        val toInstall = mutableListOf<DepRequirement>()
        val satisfied = mutableListOf<String>()
        for (req in requirements) {
            val found = byName[req.name.lowercase()]
            if (found == null) {
                toInstall.add(req)
            } else if (req.minVersion != null && compareVersions(found.version, req.minVersion) < 0) {
                toInstall.add(req)
            } else {
                satisfied.add(req.name)
            }
        }
        return InstallPlan(toInstall, satisfied)
    }

    /** Numeric dot-segment comparison ("1.10" > "1.9"); non-numeric parts compare lexically. */
    fun compareVersions(a: String, b: String): Int {
        val left = a.split('.', '-')
        val right = b.split('.', '-')
        for (i in 0 until maxOf(left.size, right.size)) {
            // Missing segments count as "0" ("2.0" == "2.0.0").
            val l = left.getOrNull(i) ?: "0"
            val r = right.getOrNull(i) ?: "0"
            val ln = l.toIntOrNull()
            val rn = r.toIntOrNull()
            val cmp = if (ln != null && rn != null) ln.compareTo(rn) else l.compareTo(r)
            if (cmp != 0) return cmp
        }
        return 0
    }
}
