package com.github.iamr8.dotnetoutdated

import com.github.iamr8.dotnetoutdated.model.ListFramework
import com.github.iamr8.dotnetoutdated.model.ListPackage
import com.github.iamr8.dotnetoutdated.model.ListPackagesReport
import com.github.iamr8.dotnetoutdated.model.ListProject
import com.github.iamr8.dotnetoutdated.model.OutdatedReport
import com.github.iamr8.dotnetoutdated.model.ReportDependency
import com.github.iamr8.dotnetoutdated.model.ReportFramework
import com.github.iamr8.dotnetoutdated.model.ReportProject
import com.github.iamr8.dotnetoutdated.ui.OutdatedRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutdatedRowsTest {

    private fun dep(name: String, resolved: String, latest: String, severity: String) =
        ReportDependency(name, resolved, latest, severity)

    @Test
    fun eachTargetFrameworkBecomesItsOwnSection() {
        val report = OutdatedReport(
            listOf(
                ReportProject(
                    name = "App",
                    filePath = "/repo/App.csproj",
                    targetFrameworks = listOf(
                        ReportFramework("net8.0", listOf(dep("Foo", "1.0.0", "2.0.0", "Major"))),
                        ReportFramework("net9.0", listOf(dep("Foo", "1.0.0", "2.0.0", "Major"))),
                    ),
                ),
            ),
        )
        val sections = OutdatedRows.build(report, "/repo/App.sln")
        assertEquals(2, sections.size)
        assertEquals(setOf("net8.0", "net9.0"), sections.map { it.framework }.toSet())
        assertTrue(sections.all { it.projectName == "App" && it.upgradeTarget == "/repo/App.csproj" })
    }

    @Test
    fun duplicatePackagesInAFrameworkAreDeduped() {
        val report = OutdatedReport(
            listOf(
                ReportProject(
                    "App", "/repo/App.csproj",
                    listOf(
                        ReportFramework(
                            "net8.0",
                            listOf(
                                dep("Foo", "1.0.0", "2.0.0", "Major"),
                                dep("Foo", "1.0.0", "2.0.0", "Major"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val section = OutdatedRows.build(report, "/x").single()
        assertEquals(1, section.deps.size)
    }

    @Test
    fun upToDatePackageIsIncludedButNotOutdated() {
        val report = OutdatedReport(
            listOf(
                ReportProject(
                    "App", "/repo/App.csproj",
                    listOf(
                        ReportFramework(
                            "net8.0",
                            listOf(
                                dep("Current", "1.2.3", "1.2.3", "None"),
                                dep("Stale", "1.0.0", "1.1.0", "Minor"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val deps = OutdatedRows.build(report, "/x").single().deps.associateBy { it.name }
        assertFalse(deps.getValue("Current").outdated)
        assertEquals("", deps.getValue("Current").newVersion)
        assertTrue(deps.getValue("Stale").outdated)
        assertEquals("1.1.0", deps.getValue("Stale").newVersion)
    }

    @Test
    fun listingBuildsSectionPerFramework() {
        val report = ListPackagesReport(
            listOf(
                ListProject(
                    path = "/repo/App.csproj",
                    frameworks = listOf(
                        ListFramework("net8.0", topLevelPackages = listOf(ListPackage("Foo", "1.0.0", "1.0.0"))),
                        ListFramework("net9.0", topLevelPackages = listOf(ListPackage("Foo", "1.0.0", "1.0.0"))),
                    ),
                ),
            ),
        )
        val sections = OutdatedRows.buildFromListing(report, "/repo/App.sln")
        assertEquals(2, sections.size)
        assertTrue(sections.all { it.projectName == "App" })
        assertEquals("1.0.0", sections.first().deps.single().current)
        assertFalse(sections.first().deps.single().outdated)
    }
}
