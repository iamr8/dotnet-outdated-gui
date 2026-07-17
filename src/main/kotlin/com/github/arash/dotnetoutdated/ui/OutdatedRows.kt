package com.github.arash.dotnetoutdated.ui

import com.github.arash.dotnetoutdated.model.ListPackagesReport
import com.github.arash.dotnetoutdated.model.OutdatedReport
import com.github.arash.dotnetoutdated.model.SeverityColor
import com.github.arash.dotnetoutdated.model.UpgradeSeverity
import com.github.arash.dotnetoutdated.model.severityColor
import java.io.File

/** One package row under a project/target-framework section. */
class DepRow(
    val name: String,
    val current: String,
    /** Newer version if one exists, otherwise empty (until an update check runs). */
    val newVersion: String,
    val color: SeverityColor,
    val outdated: Boolean,
)

/** A project + target framework group of packages, e.g. "Sahelanthropus.Data · net10.0". */
class PackageSection(
    val projectName: String,
    val framework: String,
    /** Path passed to `dotnet outdated -u` for this project (its .csproj if known, else the scan target). */
    val upgradeTarget: String,
    val deps: List<DepRow>,
)

object OutdatedRows {
    /**
     * Flattens a `dotnet outdated` report into per-project, per-framework sections. New Version is
     * the reported latest when it is a genuine upgrade, otherwise empty; only genuine upgrades are
     * colored / marked outdated.
     */
    fun build(report: OutdatedReport, fallbackTargetPath: String): List<PackageSection> =
        report.projects.flatMap { project ->
            val target = project.filePath?.takeIf { it.isNotBlank() } ?: fallbackTargetPath
            project.targetFrameworks.mapNotNull { tf ->
                val byName = LinkedHashMap<String, DepRow>()
                for (dep in tf.dependencies) {
                    val current = dep.resolvedVersion
                    val latest = dep.latestVersion
                    val severity = UpgradeSeverity.from(dep.upgradeSeverity)
                    val outdated = severity != UpgradeSeverity.NONE && latest.isNotBlank() && latest != current
                    byName.getOrPut(dep.name) {
                        DepRow(
                            name = dep.name,
                            current = current,
                            newVersion = if (outdated) latest else "",
                            color = if (outdated) severityColor(severity, latest) else SeverityColor.NONE,
                            outdated = outdated,
                        )
                    }
                }
                if (byName.isEmpty()) null
                else PackageSection(project.name, tf.name, target, byName.values.sortedBy { it.name.lowercase() })
            }
        }

    /**
     * Builds sections from `dotnet list package` output — packages with their current version only.
     * New Version is empty and nothing is outdated until an update check runs.
     */
    fun buildFromListing(report: ListPackagesReport, fallbackTargetPath: String): List<PackageSection> =
        report.projects.flatMap { project ->
            val target = project.path.takeIf { it.isNotBlank() } ?: fallbackTargetPath
            val name = project.path.takeIf { it.isNotBlank() }?.let { File(it).nameWithoutExtension } ?: fallbackTargetPath
            project.frameworks.mapNotNull { framework ->
                val byName = LinkedHashMap<String, DepRow>()
                for (pkg in framework.topLevelPackages + framework.transitivePackages) {
                    val current = pkg.resolvedVersion?.takeIf { it.isNotBlank() } ?: pkg.requestedVersion.orEmpty()
                    byName.getOrPut(pkg.id) {
                        DepRow(pkg.id, current, newVersion = "", color = SeverityColor.NONE, outdated = false)
                    }
                }
                if (byName.isEmpty()) null
                else PackageSection(name, framework.name, target, byName.values.sortedBy { it.name.lowercase() })
            }
        }
}
