package com.github.iamr8.dotnetoutdated.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage of the scan-scope decision: whole-solution by default, per-project only for a
 * subset (or a tool that can't load unsupported projects), and the phase-1-only failure fallback.
 * This is the logic behind "no per-project crawl on a whole-solution failure".
 */
class ScanPlanTest {

    private val slnPath = "/repo/App.sln"

    private fun project(name: String, path: String = "/repo/$name/$name.csproj") = SolutionProject(name, path)

    private fun solution(
        projects: List<SolutionProject>,
        hasUnsupported: Boolean = false,
    ) = Solution(slnPath, "App", projects, hasUnsupported)

    private val threeProjects = listOf(project("A"), project("B"), project("C"))
    private val allNames = setOf("A", "B", "C")

    // --- allProjectsSelected -------------------------------------------------

    @Test
    fun allSelected_trueWhenNoSolution() {
        // No solution model -> treat as "everything", so the whole-solution call is the default.
        assertTrue(ScanPlan.allProjectsSelected(null, emptySet()))
    }

    @Test
    fun allSelected_falseWhenSolutionHasNoProjects() {
        assertFalse(ScanPlan.allProjectsSelected(solution(emptyList()), emptySet()))
    }

    @Test
    fun allSelected_trueWhenEveryProjectIncluded() {
        assertTrue(ScanPlan.allProjectsSelected(solution(threeProjects), allNames))
    }

    @Test
    fun allSelected_falseWhenSubsetIncluded() {
        assertFalse(ScanPlan.allProjectsSelected(solution(threeProjects), setOf("A", "B")))
    }

    // --- primaryUnits: whole-solution vs per-project -------------------------

    @Test
    fun primary_wholeSolutionWhenAllSelectedAndToleratesUnsupported() {
        val units = ScanPlan.primaryUnits(solution(threeProjects), allNames, "/repo", toleratesUnsupported = true)
        assertEquals(1, units.size)
        assertEquals(slnPath, units.single().path) // the one call targets the .sln
        assertEquals("App", units.single().label)
    }

    @Test
    fun primary_wholeSolutionEvenWithUnsupportedWhenTolerated() {
        // dotnet outdated tolerates .shproj etc. -> keep the single whole-solution call.
        val units = ScanPlan.primaryUnits(
            solution(threeProjects, hasUnsupported = true), allNames, "/repo", toleratesUnsupported = true,
        )
        assertEquals(listOf(slnPath), units.map { it.path })
    }

    @Test
    fun primary_perProjectWhenUnsupportedAndNotTolerated() {
        // dotnet list package can't load unsupported projects -> go per-project up front.
        val units = ScanPlan.primaryUnits(
            solution(threeProjects, hasUnsupported = true), allNames, "/repo", toleratesUnsupported = false,
        )
        assertEquals(listOf("A", "B", "C"), units.map { it.label })
        assertTrue(units.none { it.path == slnPath })
    }

    @Test
    fun primary_wholeSolutionWhenAllSelectedNoUnsupportedEvenIfNotTolerated() {
        val units = ScanPlan.primaryUnits(solution(threeProjects), allNames, "/repo", toleratesUnsupported = false)
        assertEquals(listOf(slnPath), units.map { it.path })
    }

    @Test
    fun primary_perProjectForSubsetSelection() {
        val units = ScanPlan.primaryUnits(solution(threeProjects), setOf("A", "C"), "/repo", toleratesUnsupported = true)
        assertEquals(listOf("A", "C"), units.map { it.label })
    }

    @Test
    fun primary_subsetMatchingNothingFallsBackToAllProjects() {
        // Defensive: a stale/empty inclusion set shouldn't scan nothing.
        val units = ScanPlan.primaryUnits(solution(threeProjects), setOf("Ghost"), "/repo", toleratesUnsupported = true)
        assertEquals(listOf("A", "B", "C"), units.map { it.label })
    }

    @Test
    fun primary_noSolutionUsesBaseDirUnit() {
        val units = ScanPlan.primaryUnits(null, emptySet(), "/repo/App", toleratesUnsupported = true)
        assertEquals(1, units.size)
        assertEquals("/repo/App", units.single().path)
        assertEquals("App", units.single().label) // base dir name
    }

    // --- perProjectUnits -----------------------------------------------------

    @Test
    fun perProject_filtersToIncludedProjects() {
        val units = ScanPlan.perProjectUnits(solution(threeProjects), setOf("B"), "/repo")
        assertEquals(listOf("B"), units.map { it.label })
        assertEquals(listOf("/repo/B/B.csproj"), units.map { it.path })
    }

    @Test
    fun perProject_noSolutionIsBaseDir() {
        val units = ScanPlan.perProjectUnits(null, emptySet(), "/repo/App")
        assertEquals(listOf("/repo/App"), units.map { it.path })
    }

    // --- shouldFallBackToPerProject: phase-1-only recovery -------------------

    private val wholeSolutionPrimary = listOf(ScanUnit("App", slnPath))
    private val perProjectPrimary = listOf(ScanUnit("A", "/repo/A/A.csproj"), ScanUnit("B", "/repo/B/B.csproj"))

    @Test
    fun fallback_phase2NeverFansOut() {
        // Phase 2 (dotnet outdated) passes fallbackEnabled=false: fail fast even on a whole-solution failure.
        assertFalse(
            ScanPlan.shouldFallBackToPerProject(
                fallbackEnabled = false,
                primary = wholeSolutionPrimary,
                solution = solution(threeProjects),
                rowsEmpty = true,
                hasFailures = true,
            ),
        )
    }

    @Test
    fun fallback_phase1RecoversOnWholeSolutionFailure() {
        assertTrue(
            ScanPlan.shouldFallBackToPerProject(
                fallbackEnabled = true,
                primary = wholeSolutionPrimary,
                solution = solution(threeProjects),
                rowsEmpty = true,
                hasFailures = true,
            ),
        )
    }

    @Test
    fun fallback_noneWhenRowsCameBack() {
        assertFalse(
            ScanPlan.shouldFallBackToPerProject(true, wholeSolutionPrimary, solution(threeProjects), rowsEmpty = false, hasFailures = true),
        )
    }

    @Test
    fun fallback_noneWithoutFailures() {
        assertFalse(
            ScanPlan.shouldFallBackToPerProject(true, wholeSolutionPrimary, solution(threeProjects), rowsEmpty = true, hasFailures = false),
        )
    }

    @Test
    fun fallback_noneWhenPrimaryWasAlreadyPerProject() {
        // Already fanned out -> don't fan out again.
        assertFalse(
            ScanPlan.shouldFallBackToPerProject(true, perProjectPrimary, solution(threeProjects), rowsEmpty = true, hasFailures = true),
        )
    }

    @Test
    fun fallback_noneWhenSinglePrimaryIsNotTheSolution() {
        // A single-project subset scan that failed is not a whole-solution run; nothing to fan out to.
        val singleProject = listOf(ScanUnit("A", "/repo/A/A.csproj"))
        assertFalse(
            ScanPlan.shouldFallBackToPerProject(true, singleProject, solution(threeProjects), rowsEmpty = true, hasFailures = true),
        )
    }

    @Test
    fun fallback_noneWhenNoSolution() {
        // No solution -> the base-dir unit's path never equals a (null) solution path.
        val baseUnit = listOf(ScanUnit("App", "/repo/App"))
        assertFalse(
            ScanPlan.shouldFallBackToPerProject(true, baseUnit, solution = null, rowsEmpty = true, hasFailures = true),
        )
    }
}
