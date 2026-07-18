package com.github.iamr8.dotnetoutdated.cli

/**
 * Pure builders for `dotnet outdated` command lines. No process execution here so the
 * argument construction can be unit-tested.
 *
 * Flags are emitted only when they differ from the CLI default, keeping commands minimal and
 * deterministic. [analysisArgs] is shared by scan and upgrade since version-resolution flags
 * (pre-release, version lock, transitive, etc.) affect both.
 */
object OutdatedCommand {

    /**
     * `dotnet outdated <targetPath> [analysis flags] [-utd] [-inc/-exc filters] -o <file> -of json`
     *
     * `-utd` (include up-to-date) makes the report list *every* dependency so the UI can show all
     * packages with their current version.
     */
    fun scan(dotnetPath: String, targetPath: String, outputFile: String, options: OutdatedOptions): List<String> {
        val args = mutableListOf(dotnetPath, "outdated", targetPath)
        args += analysisArgs(options)
        if (options.includeUpToDate) args += "-utd"
        for (f in options.includeFilters) { args += "-inc"; args += f }
        for (f in options.excludeFilters) { args += "-exc"; args += f }
        args += listOf("-o", outputFile, "-of", "json")
        return args
    }

    /**
     * `dotnet outdated <targetPath> -u [version-policy flags] -inc <pkg>… [-exc filters]`
     *
     * Only version-policy + timeout flags are passed here — NOT the scan/analysis/source flags.
     * `dotnet outdated -u` forwards some of those (notably `-ifs`/`--ignore-failed-sources`) to a
     * nested restore/add command that rejects them, which fails the whole upgrade.
     *
     * Selected packages are passed as `-inc` filters. Note `-inc` is a case-insensitive *substring*
     * match, so a name that is a prefix of another may upgrade siblings too; callers re-scan after.
     */
    fun upgrade(dotnetPath: String, targetPath: String, packageNames: List<String>, options: OutdatedOptions): List<String> {
        val args = mutableListOf(dotnetPath, "outdated", targetPath, "-u")
        args += upgradeArgs(options)
        for (name in packageNames) { args += "-inc"; args += name }
        for (f in options.excludeFilters) { args += "-exc"; args += f }
        return args
    }

    /** All analysis, version-policy, discovery and reliability flags — for the scan only. */
    private fun analysisArgs(o: OutdatedOptions): List<String> = buildList {
        if (o.includeAutoReferences) add("-i")
        if (o.transitive) {
            add("-t")
            if (o.transitiveDepth != 1) { add("-td"); add(o.transitiveDepth.toString()) }
        }
        addAll(versionPolicyArgs(o))
        if (o.recursive) add("-r")
        if (o.includeFileBasedApps) add("-fba")
        if (o.noRestore) add("-n")
        if (o.ignoreFailedSources) add("-ifs")
        if (o.idleTimeoutSeconds != 120) { add("-it"); add(o.idleTimeoutSeconds.toString()) }
        if (o.runtime.isNotBlank()) { add("-rt"); add(o.runtime.trim()) }
        if (o.credLogLevel != CredLogLevel.Warning) { add("-ncll"); add(o.credLogLevel.name) }
    }

    /** Flags safe for `-u` (upgrade): they influence the target version and the CLI timeout only. */
    private fun upgradeArgs(o: OutdatedOptions): List<String> = buildList {
        addAll(versionPolicyArgs(o))
        if (o.idleTimeoutSeconds != 120) { add("-it"); add(o.idleTimeoutSeconds.toString()) }
    }

    /** Which version dotnet outdated resolves as the target (used by scan and upgrade). */
    private fun versionPolicyArgs(o: OutdatedOptions): List<String> = buildList {
        if (o.preRelease != PreRelease.Auto) { add("-pre"); add(o.preRelease.name) }
        if (o.preReleaseLabel.isNotBlank()) { add("-prl"); add(o.preReleaseLabel.trim()) }
        if (o.versionLock != VersionLock.None) { add("-vl"); add(o.versionLock.name) }
        if (o.maximumVersion.isNotBlank()) { add("-mv"); add(o.maximumVersion.trim()) }
        if (o.olderThanDays > 0) { add("-ot"); add(o.olderThanDays.toString()) }
    }
}
