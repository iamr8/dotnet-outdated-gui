package com.github.iamr8.dotnetoutdated.ui

import com.github.iamr8.dotnetoutdated.engine.PackageRow
import com.github.iamr8.dotnetoutdated.engine.ScanResult
import com.github.iamr8.dotnetoutdated.model.SeverityColor
import com.github.iamr8.dotnetoutdated.model.UpgradeSeverity
import com.github.iamr8.dotnetoutdated.model.severityColor

/** One package row under a project/target-framework section. */
class DepRow(
    val name: String,
    val current: String,
    /** Newer version if one exists, otherwise empty (until an update check runs). */
    val newVersion: String,
    val color: SeverityColor,
    val outdated: Boolean,
    /** The version text as the project asks for it (evaluated), e.g. `[7.0.0,8.0.0)`. */
    val requested: String = "",
    /** Floating version: the upgrade is a restore, no file edit. */
    val restoreOnly: Boolean = false,
    /** Tooltip: why a row is not checkable, or what the upgrade does. */
    val note: String? = null,
)

/** A project + target framework group of packages, e.g. "Sahelanthropus.Data · net10.0". */
class PackageSection(
    val projectName: String,
    val framework: String,
    /** The project file; upgrade rows are sent to the engine with this path. */
    val upgradeTarget: String,
    val deps: List<DepRow>,
)

object OutdatedRows {
    /** Per-project, per-framework sections from an engine scan. Include/exclude: case-insensitive substrings. */
    fun fromScan(result: ScanResult, include: List<String>, exclude: List<String>): List<PackageSection> =
        result.projects.flatMap { project ->
            project.frameworks.mapNotNull { fw ->
                val deps = fw.packages
                    .filter { keep(it.id, include, exclude) }
                    .map(::toDep)
                    .distinctBy { it.name.lowercase() }
                    .sortedBy { it.name.lowercase() }
                if (deps.isEmpty()) null else PackageSection(project.name, fw.framework, project.path, deps)
            }
        }

    fun keep(id: String, include: List<String>, exclude: List<String>): Boolean {
        val lower = id.lowercase()
        if (include.isNotEmpty() && include.none { lower.contains(it.lowercase()) }) return false
        return exclude.none { lower.contains(it.lowercase()) }
    }

    /** Only rows with a target are checkable. A newer version outside the range shows gray. */
    fun toDep(raw: PackageRow): DepRow {
        // Child-element and property values keep the file's spacing and line breaks; trim for display.
        val row = raw.copy(requested = raw.requested.trim())
        val current = row.resolved?.takeIf { it.isNotBlank() } ?: row.requested
        val target = row.target?.takeIf { it.isNotBlank() }
        val capped = row.capped?.takeIf { it.isNotBlank() }
        return when {
            target != null -> DepRow(
                row.id, current, target, severityColor(UpgradeSeverity.from(row.severity), target), outdated = true,
                requested = row.requested,
                restoreOnly = row.restoreOnly,
                note = when {
                    row.restoreOnly -> "Floating version ${row.requested}: restore picks $target."
                    capped != null -> "$capped is newer but outside ${row.requested}."
                    else -> null
                },
            )
            capped != null -> DepRow(
                row.id, current, capped, SeverityColor.NONE, outdated = false,
                requested = row.requested,
                note = "${row.reason ?: "capped by range"}: ${row.requested}",
            )
            else -> DepRow(
                row.id, current, sourceFailureMarker(row.reason), SeverityColor.NONE, outdated = false,
                requested = row.requested, note = row.reason,
            )
        }
    }

    /** A short gray marker for a source failure reason; empty when the row has no such reason. */
    private fun sourceFailureMarker(reason: String?): String = when {
        reason == null -> ""
        reason.startsWith("sign-in needed") -> "sign-in needed"
        reason.startsWith("package source") -> "source failed"
        else -> ""
    }
}
