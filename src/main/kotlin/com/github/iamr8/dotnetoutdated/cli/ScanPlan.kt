package com.github.iamr8.dotnetoutdated.cli

import java.io.File

/** A single thing to scan: the whole solution, one project, or the base dir — display label + CLI path. */
data class ScanUnit(val label: String, val path: String)

/**
 * Pure decisions for how a scan is scoped over the open solution. No Swing, no process — so the
 * whole-solution-vs-per-project choice and the per-project fallback rule are unit-testable.
 */
object ScanPlan {

    /** True when every project in the open solution is included (the default = one whole-solution call). */
    fun allProjectsSelected(solution: Solution?, includedProjects: Set<String>): Boolean {
        val sln = solution ?: return true
        return sln.projects.isNotEmpty() && includedProjects.size == sln.projects.size
    }

    /** One unit covering the whole solution (null when there is no solution). */
    fun solutionUnit(solution: Solution?): ScanUnit? =
        solution?.let { ScanUnit(it.name, it.solutionPath) }

    /** One unit per included project; the base dir when there is no solution. */
    fun perProjectUnits(solution: Solution?, includedProjects: Set<String>, basePath: String): List<ScanUnit> {
        if (solution != null && solution.projects.isNotEmpty()) {
            return solution.projects.filter { it.name in includedProjects }
                .ifEmpty { solution.projects }
                .map { ScanUnit(it.name, it.path) }
        }
        return listOf(ScanUnit(File(basePath).name, basePath))
    }

    /**
     * Primary units: the whole solution in one call when all projects are selected; per-project for a
     * subset. [toleratesUnsupported] = false forces per-project when the solution has project types
     * the tool can't load (e.g. `dotnet list package` on `.shproj`); `dotnet outdated` tolerates them,
     * so it keeps the fast single call.
     */
    fun primaryUnits(
        solution: Solution?,
        includedProjects: Set<String>,
        basePath: String,
        toleratesUnsupported: Boolean,
    ): List<ScanUnit> {
        val wholeSolutionOk = allProjectsSelected(solution, includedProjects) &&
            (toleratesUnsupported || solution?.hasUnsupportedProjects != true)
        return if (wholeSolutionOk) {
            listOfNotNull(solutionUnit(solution)).ifEmpty { perProjectUnits(solution, includedProjects, basePath) }
        } else {
            perProjectUnits(solution, includedProjects, basePath)
        }
    }

    /**
     * Whether a [primary] run that produced no rows but did fail should be retried per-project.
     *
     * Only phase 1 (`dotnet list package`) enables it: it hard-fails on any single unrestored or
     * unsupported project, and each per-project call is offline and cheap, so recover the rest.
     * Phase 2 (`dotnet outdated`) passes `false` — it fails fast and names the broken project, and
     * re-running every project one-by-one is a minutes-long crawl that fixes nothing. Never fans out
     * a run that was already per-project (only a single whole-solution unit is retried).
     */
    fun shouldFallBackToPerProject(
        fallbackEnabled: Boolean,
        primary: List<ScanUnit>,
        solution: Solution?,
        rowsEmpty: Boolean,
        hasFailures: Boolean,
    ): Boolean {
        if (!fallbackEnabled || !rowsEmpty || !hasFailures) return false
        return primary.size == 1 && primary.first().path == solution?.solutionPath
    }
}
