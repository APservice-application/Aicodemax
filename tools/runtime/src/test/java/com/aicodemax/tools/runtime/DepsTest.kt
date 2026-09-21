package com.aicodemax.tools.runtime

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepsTest {
    @Test
    fun versionsCompareNumerically() {
        assertTrue(DependencyResolver.compareVersions("1.10", "1.9") > 0)
        assertTrue(DependencyResolver.compareVersions("2.0", "2.0.0") == 0)
        assertTrue(DependencyResolver.compareVersions("1.2", "1.10") < 0)
    }

    @Test
    fun resolvePlansOnlyGaps() {
        val plan = DependencyResolver.resolve(
            requirements = listOf(
                DepRequirement("a", "1.0"),
                DepRequirement("b", "2.0"),
                DepRequirement("c"),
            ),
            installed = listOf(
                InstalledDep("a", "1.5"),
                InstalledDep("b", "1.0"),
            ),
        )
        assertEquals(listOf("a"), plan.satisfied)
        assertEquals(listOf("b", "c"), plan.toInstall.map { it.name })
    }

    @Test
    fun packageManagerRoundtrip() {
        val backend = InMemoryPackageBackend(
            ecosystem = "test",
            catalog = listOf(PackageInfo("alpha", "1.0"), PackageInfo("beta", "2.1")),
        )
        val manager = PackageManager(backend)

        val found = (manager.search("alp") as Outcome.Success<List<PackageInfo>>).value
        assertEquals(listOf("alpha"), found.map { it.name })

        val plan = DependencyResolver.resolve(listOf(DepRequirement("alpha")), emptyList())
        val installed = (manager.install(plan) as Outcome.Success<List<PackageInfo>>).value
        assertEquals("1.0", installed.single().version)

        val listed = (manager.installed() as Outcome.Success<List<PackageInfo>>).value
        assertEquals(listOf("alpha"), listed.map { it.name })

        assertTrue(manager.remove("alpha") is Outcome.Success)
        assertTrue(manager.remove("alpha") is Outcome.Failure)
        assertTrue(manager.search("  ") is Outcome.Failure)
    }
}
