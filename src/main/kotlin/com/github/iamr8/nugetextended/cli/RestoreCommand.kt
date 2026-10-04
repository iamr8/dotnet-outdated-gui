package com.github.iamr8.nugetextended.cli

import java.io.File

/** Pure: the `dotnet restore` calls needed after an upgrade or for stale projects. */
object RestoreCommand {

    fun plan(
        dotnet: String,
        affected: List<String>,
        solutionPath: String?,
        solutionProjects: Collection<String>,
        runtime: String,
        /** After an upgrade only: versions changed on purpose, so a locked-mode lock file must follow. */
        afterUpgrade: Boolean,
        /**
         * Projects with a floating (restore-only) upgrade, after an upgrade only: they change no file, so
         * restore skips them (no-op or lock file) unless forced. Each gets its own forced restore; a forced
         * solution restore would also move the floating versions of projects the user did not pick.
         */
        forceEvaluate: Collection<String> = emptyList(),
    ): List<List<String>> {
        val (apps, projects) = affected.distinct().partition { it.endsWith(".cs", ignoreCase = true) }
        val forced = forceEvaluate.map(::norm).toSet()
        val (floating, edited) = if (afterUpgrade) projects.partition { norm(it) in forced } else emptyList<String>() to projects
        val inSolution = solutionProjects.map(::norm).toSet()
        val targets = if (solutionPath != null && edited.isNotEmpty() && edited.all { norm(it) in inSolution }) {
            listOf(solutionPath)
        } else {
            edited
        }
        return (targets.map { it to false } + floating.map { it to true } + apps.map { it to false }).map { (target, force) ->
            buildList {
                add(dotnet); add("restore"); add(target)
                // Upgrades change versions on purpose; a locked-mode lock file must be allowed to follow.
                // A scan-time restore keeps the repository's lock-file rules.
                if (afterUpgrade) {
                    add("-p:RestoreLockedMode=false")
                    if (force) add("--force-evaluate")
                }
                if (runtime.isNotBlank()) { add("-r"); add(runtime.trim()) }
            }
        }
    }

    private fun norm(path: String): String = File(path).normalize().path
}
