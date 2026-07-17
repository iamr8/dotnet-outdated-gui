package com.github.arash.dotnetoutdated.cli

import java.io.File

/**
 * Finds the `dotnet` executable. GUI apps on macOS often don't inherit the shell PATH,
 * so fall back to well-known install locations before giving up on the bare command.
 */
object DotnetLocator {

    private val candidates: List<String> = buildList {
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) add(File(dir, exeName()).path)
        }
        add("/usr/local/share/dotnet/dotnet")
        add("/usr/local/bin/dotnet")
        add("/opt/homebrew/bin/dotnet")
        System.getProperty("user.home")?.let { add(File(it, ".dotnet/dotnet").path) }
        add("C:\\Program Files\\dotnet\\dotnet.exe")
    }

    private fun exeName(): String =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "dotnet.exe" else "dotnet"

    /** First existing candidate, or the bare `dotnet` command as a last resort. */
    fun resolve(): String =
        candidates.firstOrNull { it.isNotBlank() && File(it).canExecute() } ?: exeName()
}
