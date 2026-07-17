package com.github.iamr8.dotnetoutdated.cli

/**
 * Pure builder for `dotnet list <target> package --format json`, used to discover the packages
 * and their current versions WITHOUT contacting NuGet (no `dotnet outdated`, no network).
 */
object ListPackagesCommand {

    fun list(dotnetPath: String, targetPath: String, includeTransitive: Boolean): List<String> {
        val args = mutableListOf(dotnetPath, "list", targetPath, "package", "--format", "json")
        if (includeTransitive) args += "--include-transitive"
        return args
    }
}
