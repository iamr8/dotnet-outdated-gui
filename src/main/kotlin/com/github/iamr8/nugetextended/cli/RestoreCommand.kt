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
    ): List<List<String>> {
        val (apps, projects) = affected.distinct().partition { it.endsWith(".cs", ignoreCase = true) }
        val inSolution = solutionProjects.map(::norm).toSet()
        val targets = if (solutionPath != null && projects.isNotEmpty() && projects.all { norm(it) in inSolution }) {
            listOf(solutionPath)
        } else {
            projects
        }
        return (targets.map { it to true } + apps.map { it to false }).map { (target, isProject) ->
            buildList {
                add(dotnet); add("restore"); add(target)
                // Upgrades change versions on purpose; a locked-mode lock file must be allowed to follow.
                // A floating upgrade edits no file, so restore skips the project (no-op or lock file)
                // unless forced. Always forced: a lock file can have any name. Harmless after an edit.
                // A scan-time restore keeps the repository's lock-file rules.
                if (afterUpgrade) {
                    add("-p:RestoreLockedMode=false")
                    if (isProject) add("--force-evaluate")
                }
                if (runtime.isNotBlank()) { add("-r"); add(runtime.trim()) }
            }
        }
    }

    private fun norm(path: String): String = File(path).normalize().path
}
