package com.github.iamr8.dotnetoutdated.cli

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
        hasLockFile: (String) -> Boolean,
    ): List<List<String>> {
        val (apps, projects) = affected.distinct().partition { it.endsWith(".cs", ignoreCase = true) }
        val inSolution = solutionProjects.map(::norm).toSet()
        val targets = if (solutionPath != null && projects.isNotEmpty() && projects.all { norm(it) in inSolution }) {
            listOf(solutionPath to projects.any(hasLockFile))
        } else {
            projects.map { it to hasLockFile(it) }
        }
        return (targets + apps.map { it to false }).map { (target, lock) ->
            buildList {
                add(dotnet); add("restore"); add(target)
                // Upgrades change versions on purpose; a locked-mode lock file must be allowed to follow.
                // A scan-time restore keeps the repository's lock-file rules.
                if (afterUpgrade) {
                    add("-p:RestoreLockedMode=false")
                    if (lock) add("--force-evaluate")
                }
                if (runtime.isNotBlank()) { add("-r"); add(runtime.trim()) }
            }
        }
    }

    private fun norm(path: String): String = File(path).normalize().path
}
