package com.github.iamr8.dotnetoutdated.ui

import com.github.iamr8.dotnetoutdated.model.SeverityColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageListLogicTest {

    private fun dep(name: String, outdated: Boolean = true, current: String = "1.0.0", newV: String = "2.0.0") =
        DepRow(
            name = name,
            current = current,
            newVersion = if (outdated) newV else "",
            color = if (outdated) SeverityColor.RED else SeverityColor.NONE,
            outdated = outdated,
        )

    private fun section(project: String, framework: String, target: String, deps: List<DepRow>) =
        PackageSection(project, framework, target, deps)

    @Test
    fun buildEntriesSortsSectionsAndPutsHeaderThenOutdatedFirst() {
        val sections = listOf(
            section("Zeta", "net8.0", "/z.csproj", listOf(dep("Bravo", outdated = false), dep("Alpha"))),
            section("Alpha", "net8.0", "/a.csproj", listOf(dep("Solo"))),
        )
        val entries = PackageListLogic.buildEntries(sections)

        // Section order: Alpha before Zeta.
        val headers = entries.filterIsInstance<HeaderEntry>().map { it.title }
        assertEquals(listOf("Alpha  ·  net8.0", "Zeta  ·  net8.0"), headers)

        // First entry is the Alpha header, then its package.
        assertTrue(entries[0] is HeaderEntry)
        assertEquals("Solo", (entries[1] as PackageEntry).dep.name)

        // Zeta section: outdated (Alpha) before up-to-date (Bravo).
        val zetaPkgs = entries.dropWhile { !(it is HeaderEntry && it.title.startsWith("Zeta")) }
            .filterIsInstance<PackageEntry>().map { it.dep.name }
        assertEquals(listOf("Alpha", "Bravo"), zetaPkgs)
    }

    @Test
    fun hasCheckedReflectsCheckedOutdatedRows() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A"), dep("B", outdated = false)))),
        )
        assertFalse(PackageListLogic.hasChecked(entries))

        (entries.first { it is PackageEntry && it.dep.name == "A" } as PackageEntry).checked = true
        assertTrue(PackageListLogic.hasChecked(entries))
    }

    @Test
    fun checkedByTargetGroupsAndDedupes() {
        val entries = PackageListLogic.buildEntries(
            listOf(
                section("P1", "net8.0", "/p1.csproj", listOf(dep("Foo"), dep("Bar"))),
                section("P2", "net8.0", "/p2.csproj", listOf(dep("Foo"))),
            ),
        )
        entries.filterIsInstance<PackageEntry>().forEach { it.checked = true }

        val byTarget = PackageListLogic.checkedByTarget(entries)
        assertEquals(setOf("/p1.csproj", "/p2.csproj"), byTarget.keys)
        assertEquals(listOf("Bar", "Foo"), byTarget.getValue("/p1.csproj").sorted())
        assertEquals(listOf("Foo"), byTarget.getValue("/p2.csproj"))
    }

    @Test
    fun checkedByTargetIgnoresUpToDateEvenIfFlagged() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("UpToDate", outdated = false)))),
        )
        (entries.first { it is PackageEntry } as PackageEntry).checked = true // shouldn't happen via UI, but guard anyway
        assertTrue(PackageListLogic.checkedByTarget(entries).isEmpty())
    }

    @Test
    fun nextToggleStateChecksWhenAnyUnchecked_elseUnchecks() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A"), dep("B")))),
        )
        val pkgs = entries.filterIsInstance<PackageEntry>()
        assertTrue(PackageListLogic.nextToggleState(pkgs)) // none checked -> check
        pkgs.forEach { it.checked = true }
        assertFalse(PackageListLogic.nextToggleState(pkgs)) // all checked -> uncheck
    }
}
