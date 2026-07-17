package com.github.iamr8.dotnetoutdated.model

import com.google.gson.annotations.SerializedName

/**
 * Data classes matching `dotnet list <target> package --format json`.
 * Only fields the plugin needs are mapped.
 */
data class ListPackagesReport(
    @SerializedName("projects") val projects: List<ListProject> = emptyList(),
)

data class ListProject(
    @SerializedName("path") val path: String = "",
    @SerializedName("frameworks") val frameworks: List<ListFramework> = emptyList(),
)

data class ListFramework(
    @SerializedName("framework") val name: String = "",
    @SerializedName("topLevelPackages") val topLevelPackages: List<ListPackage> = emptyList(),
    @SerializedName("transitivePackages") val transitivePackages: List<ListPackage> = emptyList(),
)

data class ListPackage(
    @SerializedName("id") val id: String = "",
    @SerializedName("requestedVersion") val requestedVersion: String? = null,
    @SerializedName("resolvedVersion") val resolvedVersion: String? = null,
)
