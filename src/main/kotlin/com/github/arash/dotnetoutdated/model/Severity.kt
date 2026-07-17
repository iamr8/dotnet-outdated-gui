package com.github.arash.dotnetoutdated.model

/** Upgrade severity as reported by `dotnet outdated`. */
enum class UpgradeSeverity {
    NONE, PATCH, MINOR, MAJOR, UNKNOWN;

    companion object {
        fun from(raw: String?): UpgradeSeverity = when (raw?.trim()?.lowercase()) {
            "none" -> NONE
            "patch" -> PATCH
            "minor" -> MINOR
            "major" -> MAJOR
            else -> UNKNOWN
        }
    }
}

/**
 * Semantic color bucket matching the CLI legend:
 *   red = major update or pre-release (possible breaking changes),
 *   yellow = minor, green = patch.
 * Kept UI-framework-free so it can be unit-tested; the UI maps these to JBColors.
 */
enum class SeverityColor { RED, YELLOW, GREEN, NONE }

/** True if a version string carries a pre-release label (e.g. `0.9.0-beta.4`). */
fun isPrerelease(version: String?): Boolean = version?.contains('-') == true

/**
 * Splits [latest] into (unchangedPrefix, changedSuffix) relative to [current], so only the part
 * that actually changed can be colored — matching the CLI (`4.2.1 -> 4.`**`3.0`**).
 */
fun changedVersionSuffix(current: String, latest: String): Pair<String, String> {
    val shared = current.commonPrefixWith(latest).length
    return latest.substring(0, shared) to latest.substring(shared)
}

/**
 * Maps a dependency to its legend color. Pre-release latest versions are always red,
 * regardless of the numeric severity, matching the CLI legend.
 */
fun severityColor(severity: UpgradeSeverity, latestVersion: String?): SeverityColor {
    if (isPrerelease(latestVersion)) return SeverityColor.RED
    return when (severity) {
        UpgradeSeverity.MAJOR, UpgradeSeverity.UNKNOWN -> SeverityColor.RED
        UpgradeSeverity.MINOR -> SeverityColor.YELLOW
        UpgradeSeverity.PATCH -> SeverityColor.GREEN
        UpgradeSeverity.NONE -> SeverityColor.NONE
    }
}
