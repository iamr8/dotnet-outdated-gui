package com.github.iamr8.nugetextended.settings

import com.github.iamr8.nugetextended.PluginText
import com.github.iamr8.nugetextended.cli.CredLogLevel
import com.github.iamr8.nugetextended.cli.OutdatedOptions
import com.github.iamr8.nugetextended.cli.PreRelease
import com.github.iamr8.nugetextended.cli.VersionLock
import com.github.iamr8.nugetextended.cli.isValidMaximumVersion
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

/** "NuGet (Extended)" settings page under Settings | Tools. Persists to [OutdatedOptionsService]. */
class OutdatedConfigurable(project: Project) :
    BoundSearchableConfigurable(PluginText.NAME, "nuget.extended") {

    private val service = OutdatedOptionsService.getInstance(project)
    private val work: OutdatedOptions = service.options.deepCopy()

    override fun isModified(): Boolean = super.isModified() || work != service.options

    override fun apply() {
        super.apply() // write UI -> work
        service.options = work.deepCopy()
    }

    override fun reset() {
        work.assignFrom(service.options) // in place: keeps bindings valid
        super.reset()
    }

    override fun createPanel(): DialogPanel = panel {
        group("Packages Analyzed") {
            row {
                checkBox("List all packages (including up-to-date)").bindSelected(work::includeUpToDate)
                    .comment("Off by default. Lists every package from the last restore, with no update check.")
            }
            row {
                checkBox("Show versions outside the range").bindSelected(work::showCappedVersions)
                    .comment("Gray and not checkable: the range you wrote does not allow them.")
            }
            row { checkBox("Include auto-referenced packages").bindSelected(work::includeAutoReferences) }
            lateinit var transitive: Cell<JBCheckBox>
            row { transitive = checkBox("Include transitive dependencies (upgrade adds a direct reference)").bindSelected(work::transitive) }
            row("Transitive depth:") { intField(work::transitiveDepth, 1) }.enabledIf(transitive.selected)
        }
        group("Version Policy") {
            row("Pre-release:") {
                comboBox(PreRelease.entries).bindItem({ work.preRelease }, { work.preRelease = it ?: PreRelease.Auto })
            }
            row("Pre-release label:") { textField().bindText(work::preReleaseLabel).columns(14) }
            row("Version lock:") {
                comboBox(VersionLock.entries).bindItem({ work.versionLock }, { work.versionLock = it ?: VersionLock.None })
            }
            row("Maximum version (never above a range's upper bound):") {
                textField().bindText(work::maximumVersion).columns(14)
                    .comment("For example 8, 8.0 or 8.0.1. A missing part means any: 8 allows every 8.x.")
                    .validationOnInput { if (isValidMaximumVersion(it.text)) null else error("Use numbers only, like 8, 8.0 or 8.0.1.") }
                    .validationOnApply { if (isValidMaximumVersion(it.text)) null else error("Use numbers only, like 8, 8.0 or 8.0.1.") }
            }
            row("Only versions older than (days):") { intField(work::olderThanDays, 0) }
        }
        group("Discovery") {
            row { checkBox("Find projects in subfolders (no solution open)").bindSelected(work::recursive) }
            row {
                checkBox("Include file-based apps (#:package, .NET 10 SDK)").bindSelected(work::includeFileBasedApps)
                    .comment("Searches the solution folder and its subfolders for .cs files with #:package.")
            }
            row("Show only (names contain), comma-separated:") {
                textField().bindText({ work.includeFilters.joinToString(", ") }, { work.includeFilters = splitCsv(it) }).columns(30)
            }
            row("Hide (names contain), comma-separated:") {
                textField().bindText({ work.excludeFilters.joinToString(", ") }, { work.excludeFilters = splitCsv(it) }).columns(30)
            }
        }
        group("Sources & Reliability") {
            row { checkBox("Never run dotnet restore (unrestored projects are skipped)").bindSelected(work::noRestore) }
            row { checkBox("Ignore failed package sources").bindSelected(work::ignoreFailedSources) }
            row("Engine timeout without progress, seconds:") { intField(work::idleTimeoutSeconds, 300) }
            row("Runtime identifier:") {
                textField().bindText(work::runtime).columns(14)
                    .comment("For example linux-x64. Leave empty unless your projects restore for a runtime.")
            }
            row("NuGet credential log level:") {
                comboBox(CredLogLevel.entries).bindItem({ work.credLogLevel }, { work.credLogLevel = it ?: CredLogLevel.Warning })
            }
        }
    }

    private fun com.intellij.ui.dsl.builder.Row.intField(
        prop: kotlin.reflect.KMutableProperty0<Int>,
        fallback: Int,
    ) {
        textField()
            .bindText({ prop.get().toString() }, { prop.set(it.trim().toIntOrNull() ?: fallback) })
            .columns(6)
    }

    private fun splitCsv(raw: String): MutableList<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
}
