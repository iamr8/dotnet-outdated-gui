package com.github.arash.dotnetoutdated.ui

import com.github.arash.dotnetoutdated.model.ListPackagesReport
import com.github.arash.dotnetoutdated.model.OutdatedReport
import com.github.arash.dotnetoutdated.model.SeverityColor
import com.github.arash.dotnetoutdated.model.UpgradeSeverity
import com.github.arash.dotnetoutdated.model.severityColor
import java.io.File

/** One package under a project (deduplicated across target frameworks). */
class DepRow(
    val name: String,
    val current: String,
    /** Newer version if one exists, otherwise the current version. */
    val newVersion: String,
    val color: SeverityColor,
    /** True when [newVersion] is a genuine upgrade over [current]. */
    val outdated: Boolean,
    var checked: Boolean = false,
) {
    override fun toString(): String = name
}

/** A project node with its outdated packages and the path to upgrade against. */
class ProjectRow(
    val name: String,
    /** Path passed to `dotnet outdated -u` for this project (its .csproj if known, else the scan target). */
    val upgradeTarget: String,
    val deps: List<DepRow>,
) {
    override fun toString(): String = name
}

object OutdatedRows {
    /**
     * Flattens a report into project rows for every package (the report is produced with
     * `-utd`, so up-to-date packages are included). Packages that repeat across target
     * frameworks are deduplicated. Projects with no packages are dropped.
     *
     * New Version is the reported latest when it is a genuine upgrade, otherwise the current
     * version. Only genuine upgrades are colored / marked [DepRow.outdated].
     *
     * @param fallbackTargetPath used as the upgrade target when a project has no file path in the JSON.
     */
    fun build(report: OutdatedReport, fallbackTargetPath: String): List<ProjectRow> =
        report.projects.mapNotNull { project ->
            val byKey = LinkedHashMap<String, DepRow>()
            for (tf in project.targetFrameworks) {
                for (dep in tf.dependencies) {
                    val current = dep.resolvedVersion
                    val latest = dep.latestVersion
                    val severity = UpgradeSeverity.from(dep.upgradeSeverity)
                    val outdated = severity != UpgradeSeverity.NONE && latest.isNotBlank() && latest != current
                    val key = "${dep.name}|$current|$latest"
                    byKey.getOrPut(key) {
                        DepRow(
                            name = dep.name,
                            current = current,
                            newVersion = if (outdated) latest else current,
                            color = if (outdated) severityColor(severity, latest) else SeverityColor.NONE,
                            outdated = outdated,
                        )
                    }
                }
            }
            if (byKey.isEmpty()) {
                null
            } else {
                ProjectRow(
                    name = project.name,
                    upgradeTarget = project.filePath?.takeIf { it.isNotBlank() } ?: fallbackTargetPath,
                    deps = byKey.values.sortedBy { it.name.lowercase() },
                )
            }
        }

    /**
     * Builds project rows from `dotnet list package` output — packages with their current version
     * only. New Version equals current and nothing is marked outdated, since no update check ran yet.
     * A later `dotnet outdated` refresh replaces these rows with real New Version data.
     */
    fun buildFromListing(report: ListPackagesReport, fallbackTargetPath: String): List<ProjectRow> =
        report.projects.mapNotNull { project ->
            val byKey = LinkedHashMap<String, DepRow>()
            for (framework in project.frameworks) {
                for (pkg in framework.topLevelPackages + framework.transitivePackages) {
                    val current = pkg.resolvedVersion?.takeIf { it.isNotBlank() }
                        ?: pkg.requestedVersion.orEmpty()
                    val key = "${pkg.id}|$current"
                    byKey.getOrPut(key) {
                        DepRow(
                            name = pkg.id,
                            current = current,
                            newVersion = "", // empty until dotnet outdated is run
                            color = SeverityColor.NONE,
                            outdated = false,
                        )
                    }
                }
            }
            if (byKey.isEmpty()) {
                null
            } else {
                val path = project.path.takeIf { it.isNotBlank() }
                ProjectRow(
                    name = path?.let { File(it).nameWithoutExtension } ?: fallbackTargetPath,
                    upgradeTarget = path ?: fallbackTargetPath,
                    deps = byKey.values.sortedBy { it.name.lowercase() },
                )
            }
        }
}
