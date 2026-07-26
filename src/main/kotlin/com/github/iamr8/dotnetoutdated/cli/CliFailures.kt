package com.github.iamr8.dotnetoutdated.cli

/**
 * Turns raw `dotnet` / `dotnet outdated` output into a short, actionable sentence for the user.
 *
 * These failures are almost always about the *user's* solution (missing CLI, unrestored project,
 * a package version that doesn't exist) — not a plugin bug — so they are surfaced as notifications,
 * never through the IDE error reporter. Pure and unit-tested.
 */
object CliFailures {

    /** Summaries are shown in a balloon; keep them one-line-ish. */
    private const val MAX_SUMMARY = 240

    fun describe(stderr: String, stdout: String): String {
        val combined = (stderr + "\n" + stdout).lowercase()
        return when {
            "no executable found matching command" in combined || "is not a dotnet command" in combined ->
                "dotnet-outdated tool not found. Install: dotnet tool install -g dotnet-outdated-tool"

            "command not found" in combined || combined.isBlank() ->
                "Could not run dotnet. Ensure the .NET SDK is installed and on PATH."

            // NU1102/NU1101: a referenced version (or package) doesn't exist on the feeds, so
            // restore fails and dotnet can't report packages for the project at all.
            "nu1102" in combined || "nu1101" in combined || "unable to find package" in combined ->
                "Restore failed: a referenced package version doesn't exist on the configured NuGet feeds. " +
                    "Fix the version in the project file (or Directory.Packages.props) and try again."

            "no assets" in combined || "run a restore" in combined || "project.assets.json" in combined ->
                "The project isn't restored. Run 'dotnet restore' (or build) and try again."

            "unable to process the project" in combined || "failed to restore" in combined ->
                "Restore failed for this project, so its packages can't be read. " +
                    "Fix the restore errors (Copy Details for the full output) and try again."

            else -> firstMeaningfulLine(stderr.ifBlank { stdout }) ?: "dotnet exited with a non-zero status."
        }
    }

    /** The first line that looks like an error, else the first non-blank line, trimmed to length. */
    private fun firstMeaningfulLine(raw: String): String? {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val line = lines.firstOrNull { it.contains(": error ", ignoreCase = true) }
            ?: lines.firstOrNull()
            ?: return null
        return if (line.length <= MAX_SUMMARY) line else line.take(MAX_SUMMARY).trimEnd() + "…"
    }
}
