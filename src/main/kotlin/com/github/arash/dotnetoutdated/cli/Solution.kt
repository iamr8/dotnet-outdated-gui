package com.github.arash.dotnetoutdated.cli

import java.io.File

/** A project belonging to the open solution. */
data class SolutionProject(val name: String, val path: String)

/** The currently open solution and the projects loaded in it. */
data class Solution(
    val solutionPath: String,
    val name: String,
    val projects: List<SolutionProject>,
    /** True if the solution references project types the dotnet CLI can't load (e.g. `.shproj`). */
    val hasUnsupportedProjects: Boolean = false,
)

/**
 * Locates the open solution and parses the projects it contains. Only the current solution is
 * considered (not every `.sln` sitting in the folder); its projects become the selectable scope.
 */
object SolutionModel {

    private val projectExts = setOf("csproj", "fsproj", "vbproj")
    private val skipDirs = setOf("bin", "obj", ".git", ".idea", "node_modules")

    // Classic .sln:  Project("{type-guid}") = "Name", "Rel\Path.csproj", "{proj-guid}"
    private val slnProjectRegex =
        Regex("""Project\("\{[0-9A-Fa-f-]+}"\)\s*=\s*"([^"]+)",\s*"([^"]+)",\s*"\{[0-9A-Fa-f-]+}"""")

    // .slnx:  <Project Path="src/Foo/Foo.csproj" ... />
    private val slnxPathRegex = Regex("""Path\s*=\s*"([^"]+)"""")

    /**
     * @param preferredName usually the IDE project name; when several solution files sit in the
     * directory, the one whose name matches is chosen (Rider names the project after the solution).
     */
    fun discover(baseDir: File, preferredName: String?): Solution? {
        val file = findSolutionFile(baseDir, preferredName) ?: return null
        val (projects, hasUnsupported) = parseEntries(file)
        return Solution(file.absolutePath, file.nameWithoutExtension, projects, hasUnsupported)
    }

    private fun findSolutionFile(baseDir: File, preferredName: String?): File? {
        if (!baseDir.isDirectory) return null
        val topLevel = baseDir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("slnx", "sln") }
            ?.sortedBy { it.extension.lowercase() } // slnx before sln
            ?: emptyList()
        topLevel.firstOrNull { it.nameWithoutExtension == preferredName }?.let { return it }
        topLevel.firstOrNull()?.let { return it }
        return firstSolutionRecursive(baseDir, 0, maxDepth = 4)
    }

    private fun firstSolutionRecursive(dir: File, depth: Int, maxDepth: Int): File? {
        if (depth > maxDepth) return null
        val entries = dir.listFiles() ?: return null
        entries.filter { it.isFile && it.extension.lowercase() in setOf("slnx", "sln") }
            .minByOrNull { it.extension.lowercase() }
            ?.let { return it }
        for (sub in entries) {
            if (sub.isDirectory && sub.name.lowercase() !in skipDirs && !sub.name.startsWith(".")) {
                firstSolutionRecursive(sub, depth + 1, maxDepth)?.let { return it }
            }
        }
        return null
    }

    /** Parses the (dotnet-loadable) projects referenced by a solution file. Pure given the file. */
    fun parseProjects(solutionFile: File): List<SolutionProject> = parseEntries(solutionFile).first

    /** @return supported projects, plus whether any unsupported (non-CLI) project type is present. */
    fun parseEntries(solutionFile: File): Pair<List<SolutionProject>, Boolean> {
        if (!solutionFile.isFile) return emptyList<SolutionProject>() to false
        val text = solutionFile.readText()
        val dir = solutionFile.parentFile ?: File(".")
        val result = LinkedHashMap<String, SolutionProject>()
        var hasUnsupported = false

        fun consider(name: String, rawRelPath: String) {
            val relPath = rawRelPath.replace('\\', File.separatorChar)
            val ext = File(relPath).extension.lowercase()
            when {
                ext in projectExts -> result.putIfAbsent(name, SolutionProject(name, File(dir, relPath).path))
                ext.endsWith("proj") -> hasUnsupported = true // e.g. .shproj, .vcxproj
            }
        }

        when (solutionFile.extension.lowercase()) {
            "slnx" -> slnxPathRegex.findAll(text).forEach { m ->
                val p = m.groupValues[1]
                consider(File(p).nameWithoutExtension, p)
            }
            else -> slnProjectRegex.findAll(text).forEach { m ->
                consider(m.groupValues[1], m.groupValues[2])
            }
        }
        return result.values.sortedBy { it.name.lowercase() } to hasUnsupported
    }
}
