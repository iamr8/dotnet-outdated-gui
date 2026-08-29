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
    fun outdatedEntriesKeepsOnlyCheckableRows() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A"), dep("B", outdated = false)))),
        )
        assertEquals(listOf("A"), PackageListLogic.outdatedEntries(entries).map { it.dep.name })
    }

    @Test
    fun hasOutdatedTrueOnlyWhenACheckableRowExists() {
        val none = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A", outdated = false)))),
        )
        assertFalse(PackageListLogic.hasOutdated(none))

        val some = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A")))),
        )
        assertTrue(PackageListLogic.hasOutdated(some))
    }

    @Test
    fun allOutdatedCheckedIgnoresUpToDateRowsAndNeedsAtLeastOne() {
        val empty = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A", outdated = false)))),
        )
        assertFalse(PackageListLogic.allOutdatedChecked(empty)) // no checkable row

        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A"), dep("B"), dep("Up", outdated = false)))),
        )
        assertFalse(PackageListLogic.allOutdatedChecked(entries)) // none checked
        PackageListLogic.outdatedEntries(entries).forEach { it.checked = true }
        assertTrue(PackageListLogic.allOutdatedChecked(entries)) // up-to-date row doesn't block
    }

    @Test
    fun sectionCheckStateReflectsNonePartialAll() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A"), dep("B"), dep("Up", outdated = false)))),
        )
        val header = entries.filterIsInstance<HeaderEntry>().single()
        val outdated = PackageListLogic.outdatedEntries(entries)

        assertEquals(CheckState.NONE, PackageListLogic.sectionCheckState(header))
        outdated.first().checked = true
        assertEquals(CheckState.SOME, PackageListLogic.sectionCheckState(header))
        outdated.forEach { it.checked = true }
        assertEquals(CheckState.ALL, PackageListLogic.sectionCheckState(header)) // up-to-date row ignored
    }

    @Test
    fun sectionWithNoOutdatedIsNoneAndHasNoOutdated() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("Up", outdated = false)))),
        )
        val header = entries.filterIsInstance<HeaderEntry>().single()
        assertFalse(PackageListLogic.sectionHasOutdated(header))
        assertEquals(CheckState.NONE, PackageListLogic.sectionCheckState(header))
    }

    @Test
    fun headerPackagesShareInstancesWithFlatRows() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A")))),
        )
        val header = entries.filterIsInstance<HeaderEntry>().single()
        val row = entries.filterIsInstance<PackageEntry>().single { it.dep.name == "A" }
        header.packages.first { it.dep.name == "A" }.checked = true
        assertTrue(row.checked) // toggling via the header mutates the same row object
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

    @Test
    fun searchTextIsPackageNameForRows() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("MyProj", "net8.0", "/p.csproj", listOf(dep("Newtonsoft.Json")))),
        )
        val row = entries.filterIsInstance<PackageEntry>().single()
        assertEquals("Newtonsoft.Json", PackageListLogic.searchText(row))
    }

    @Test
    fun searchTextIsSectionTitleForHeaders() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("MyProj", "net8.0", "/p.csproj", listOf(dep("A")))),
        )
        val header = entries.filterIsInstance<HeaderEntry>().single()
        // Header title is also what the renderer highlights, so it must match the section label.
        assertEquals("MyProj  ·  net8.0", header.title)
        assertEquals("MyProj  ·  net8.0", PackageListLogic.searchText(header))
    }

    @Test
    fun searchTextIsEmptyForNull() {
        // ListSpeedSearch may hand the accessor a null element (e.g. an empty model); must not throw.
        assertEquals("", PackageListLogic.searchText(null))
    }
}
