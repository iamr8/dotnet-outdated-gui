package com.github.iamr8.nugetextended.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScanPlanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun project(name: String, path: String = "/repo/$name/$name.csproj") = SolutionProject(name, path)
    private fun solution(projects: List<SolutionProject>) = Solution("/repo/App.sln", "App", projects)
    private val threeProjects = listOf(project("A"), project("B"), project("C"))

    private fun write(rel: String, text: String = "<Project />"): File =
        File(tmp.root, rel).apply { parentFile.mkdirs(); writeText(text) }

    // --- projectPaths ------------------------------------------------------

    @Test
    fun solutionGivesIncludedProjects() {
        val paths = ScanPlan.projectPaths(solution(threeProjects), setOf("A", "C"), tmp.root, recursive = false, includeFileBasedApps = false)
        assertEquals(listOf("/repo/A/A.csproj", "/repo/C/C.csproj"), paths)
    }

    @Test
    fun emptySelectionMeansAll() {
        val paths = ScanPlan.projectPaths(solution(threeProjects), emptySet(), tmp.root, recursive = false, includeFileBasedApps = false)
        assertEquals(3, paths.size)
    }

    @Test
    fun noSolutionTopLevelOnlyUnlessRecursive() {
        val top = write("Top.csproj")
        val nested = write("src/Lib/Lib.fsproj")
        write("src/Lib/obj/Gen.csproj")
        write("src/.hidden/H.csproj")

        assertEquals(listOf(top.path), ScanPlan.projectPaths(null, emptySet(), tmp.root, recursive = false, includeFileBasedApps = false))
        assertEquals(
            listOf(nested.path, top.path).sorted(),
            ScanPlan.projectPaths(null, emptySet(), tmp.root, recursive = true, includeFileBasedApps = false).sorted(),
        )
    }

    @Test
    fun fileBasedAppsWhenEnabledEvenWithoutSubfolderProjects() {
        val app = write("tools/app.cs", "#!/usr/bin/env dotnet\n#:package Humanizer.Core@2.14.1\nSystem.Console.WriteLine();\n")
        write("src/Plain.cs", "class Plain { }\n")

        assertFalse(ScanPlan.projectPaths(null, emptySet(), tmp.root, recursive = true, includeFileBasedApps = false).contains(app.path))
        // The app setting alone searches subfolders; "projects in subfolders" is about .csproj files.
        assertEquals(listOf(app.path), ScanPlan.projectPaths(null, emptySet(), tmp.root, recursive = false, includeFileBasedApps = true))
        assertEquals(listOf(app.path), ScanPlan.projectPaths(null, emptySet(), tmp.root, recursive = true, includeFileBasedApps = true))
    }

    @Test
    fun anUnreadableCsFileIsSkippedNotThrown() {
        // Break: remove the IOException catch in ScanPlan.isFileBasedApp, so one bad file fails the whole scan.
        val app = write("tools/app.cs", "#:package X@1.0.0\n")
        val bad = write("tools/bad.cs", "#:package Y@1.0.0\n")
        bad.setReadable(false)
        try {
            assumeFalse("this OS still lets us read the file (e.g. running as root)", bad.canRead())
            assertEquals(listOf(app.path), ScanPlan.projectPaths(null, emptySet(), tmp.root, recursive = true, includeFileBasedApps = true))
            assertFalse(ScanPlan.isFileBasedApp(bad))
        } finally {
            bad.setReadable(true)
        }
    }

    @Test
    fun isFileBasedAppNeedsAPackageDirective() {
        assertTrue(ScanPlan.isFileBasedApp(write("a.cs", "#:package X@1.0.0\n")))
        assertFalse(ScanPlan.isFileBasedApp(write("b.cs", "#:property LangVersion=preview\nclass B { }\n")))
        assertFalse(ScanPlan.isFileBasedApp(write("c.txt", "#:package X@1.0.0\n")))
    }
}
