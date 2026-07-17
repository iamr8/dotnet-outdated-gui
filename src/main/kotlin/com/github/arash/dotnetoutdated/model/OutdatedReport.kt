package com.github.arash.dotnetoutdated.model

import com.google.gson.annotations.SerializedName

/**
 * Data classes matching the JSON report produced by
 * `dotnet outdated <path> -o <file> -of json`.
 *
 * Only the fields the plugin needs are mapped; unknown fields are ignored by Gson.
 */
data class OutdatedReport(
    @SerializedName("Projects") val projects: List<ReportProject> = emptyList(),
)

data class ReportProject(
    @SerializedName("Name") val name: String = "",
    @SerializedName("FilePath") val filePath: String? = null,
    @SerializedName("TargetFrameworks") val targetFrameworks: List<ReportFramework> = emptyList(),
)

data class ReportFramework(
    @SerializedName("Name") val name: String = "",
    @SerializedName("Dependencies") val dependencies: List<ReportDependency> = emptyList(),
)

data class ReportDependency(
    @SerializedName("Name") val name: String = "",
    @SerializedName("ResolvedVersion") val resolvedVersion: String = "",
    @SerializedName("LatestVersion") val latestVersion: String = "",
    @SerializedName("UpgradeSeverity") val upgradeSeverity: String? = null,
)
