package com.github.iamr8.dotnetoutdated.cli

/** `dotnet outdated --pre-release` values. */
enum class PreRelease { Auto, Always, Never }

/** `dotnet outdated --version-lock` values. */
enum class VersionLock { None, Major, Minor }

/** `dotnet outdated --nuget-cred-log-level` values. */
enum class CredLogLevel { Debug, Verbose, Information, Minimal, Warning, Error }

/**
 * Every configurable `dotnet outdated` argument the plugin exposes. Plain, mutable, pure — so
 * command building is unit-testable and the type can be bound to the options form and persisted
 * directly (a no-arg constructor is synthesized because all parameters have defaults).
 *
 * Defaults differ from the CLI where it makes the plugin safer or matches its list-everything UX:
 *  - [includeUpToDate] = true  (the tree shows all packages, not just outdated ones)
 *  - [ignoreFailedSources] = true  (a single flaky feed shouldn't abort the whole scan)
 *  - [idleTimeoutSeconds] = 300  (CLI default 120 can trip on slow/private feeds)
 */
data class OutdatedOptions(
    // Which packages are analyzed
    var includeAutoReferences: Boolean = false,
    var transitive: Boolean = false,
    var transitiveDepth: Int = 1,
    /** List every package (adds `-utd`, enables offline listing). Off by default — heavy on big solutions. */
    var includeUpToDate: Boolean = false,

    // Version policy
    var preRelease: PreRelease = PreRelease.Auto,
    var preReleaseLabel: String = "",
    var versionLock: VersionLock = VersionLock.None,
    var maximumVersion: String = "",
    var olderThanDays: Int = 0,

    // Discovery
    var recursive: Boolean = false,
    var includeFileBasedApps: Boolean = false,
    var includeFilters: MutableList<String> = mutableListOf(),
    var excludeFilters: MutableList<String> = mutableListOf(),

    // Reliability / sources
    var noRestore: Boolean = false,
    var ignoreFailedSources: Boolean = true,
    var idleTimeoutSeconds: Int = 300,
    var runtime: String = "",
    var credLogLevel: CredLogLevel = CredLogLevel.Warning,
) {
    /** Copies every field from [other] in place (keeps this instance's identity for UI bindings). */
    fun assignFrom(other: OutdatedOptions) {
        includeAutoReferences = other.includeAutoReferences
        transitive = other.transitive
        transitiveDepth = other.transitiveDepth
        includeUpToDate = other.includeUpToDate
        preRelease = other.preRelease
        preReleaseLabel = other.preReleaseLabel
        versionLock = other.versionLock
        maximumVersion = other.maximumVersion
        olderThanDays = other.olderThanDays
        recursive = other.recursive
        includeFileBasedApps = other.includeFileBasedApps
        includeFilters = other.includeFilters.toMutableList()
        excludeFilters = other.excludeFilters.toMutableList()
        noRestore = other.noRestore
        ignoreFailedSources = other.ignoreFailedSources
        idleTimeoutSeconds = other.idleTimeoutSeconds
        runtime = other.runtime
        credLogLevel = other.credLogLevel
    }

    /** Independent deep copy (lists not shared). */
    fun deepCopy(): OutdatedOptions =
        copy(includeFilters = includeFilters.toMutableList(), excludeFilters = excludeFilters.toMutableList())
}
