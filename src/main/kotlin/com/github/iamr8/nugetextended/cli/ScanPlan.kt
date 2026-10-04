package com.github.iamr8.nugetextended.cli

import java.io.File

/** Pure: which project files a scan covers. The engine takes a list of paths, so there is no per-tool split any more. */
object ScanPlan {

    private val projectExts = setOf("csproj", "fsproj", "vbproj")
    private val skipDirs = setOf("bin", "obj", ".git", ".idea", "node_modules")
    private const val DIRECTIVE_LINES = 64

    /** True when every project in the open solution is included. */
    fun allProjectsSelected(solution: Solution?, includedProjects: Set<String>): Boolean {
        val sln = solution ?: return true
        return sln.projects.isNotEmpty() && includedProjects.size == sln.projects.size
    }

    fun projectPaths(
        solution: Solution?,
        /** [SolutionProject.key]s of the projects to scan (not names: two projects can share a name). */
        includedProjects: Set<String>,
        baseDir: File,
        recursive: Boolean,
        includeFileBasedApps: Boolean,
    ): List<String> {
        val projects = if (solution != null && solution.projects.isNotEmpty()) {
            solution.projects.filter { it.key in includedProjects }.ifEmpty { solution.projects }.map { it.path }
        } else {
            find(baseDir, recursive) { it.extension.lowercase() in projectExts }
        }
        // File-based apps live anywhere under the solution folder, so this search always recurses.
        val apps = if (includeFileBasedApps) {
            find(solution?.solutionPath?.let { File(it).parentFile } ?: baseDir, recursive = true, ::isFileBasedApp)
        } else {
            emptyList()
        }
        return (projects + apps).distinct()
    }

    /** A `.cs` file with a `#:package` directive near the top (SDK 10 file-based app). */
    fun isFileBasedApp(file: File): Boolean {
        if (!file.isFile || !file.extension.equals("cs", ignoreCase = true)) return false
        return file.bufferedReader().useLines { lines ->
            lines.take(DIRECTIVE_LINES).any { it.trimStart().startsWith("#:package ") }
        }
    }

    private fun find(dir: File, recursive: Boolean, accept: (File) -> Boolean): List<String> {
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .maxDepth(if (recursive) Int.MAX_VALUE else 1)
            .onEnter { it == dir || (it.name.lowercase() !in skipDirs && !it.name.startsWith(".")) }
            .filter { it.isFile && accept(it) }
            .map { it.path }
            .sorted()
            .toList()
    }
}
