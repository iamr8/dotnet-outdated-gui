package com.github.arash.dotnetoutdated.settings

import com.github.arash.dotnetoutdated.cli.CredLogLevel
import com.github.arash.dotnetoutdated.cli.OutdatedOptions
import com.github.arash.dotnetoutdated.cli.PreRelease
import com.github.arash.dotnetoutdated.cli.VersionLock
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

/** "dotnet outdated GUI" settings page under Settings | Tools. Persists to [OutdatedOptionsService]. */
class OutdatedConfigurable(project: Project) :
    BoundSearchableConfigurable("dotnet outdated GUI", "nuget.extended") {

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
                    .comment("Off by default. Lists every package via <code>dotnet list package</code> — can be a massive, slow operation on large solutions.")
            }
            row { checkBox("Include auto-referenced packages (-i)").bindSelected(work::includeAutoReferences) }
            row { checkBox("Include transitive dependencies (-t)").bindSelected(work::transitive) }
            row("Transitive depth (-td):") { intField(work::transitiveDepth, 1) }
        }
        group("Version Policy") {
            row("Pre-release (-pre):") {
                comboBox(PreRelease.entries).bindItem({ work.preRelease }, { work.preRelease = it ?: PreRelease.Auto })
            }
            row("Pre-release label (-prl):") { textField().bindText(work::preReleaseLabel).columns(14) }
            row("Version lock (-vl):") {
                comboBox(VersionLock.entries).bindItem({ work.versionLock }, { work.versionLock = it ?: VersionLock.None })
            }
            row("Maximum version (-mv):") { textField().bindText(work::maximumVersion).columns(14) }
            row("Only versions older than (days) (-ot):") { intField(work::olderThanDays, 0) }
        }
        group("Discovery") {
            row { checkBox("Recurse directory for projects (-r)").bindSelected(work::recursive) }
            row { checkBox("Include file-based apps when recursing (-fba)").bindSelected(work::includeFileBasedApps) }
            row("Include only (names contain), comma-separated (-inc):") {
                textField().bindText({ work.includeFilters.joinToString(", ") }, { work.includeFilters = splitCsv(it) }).columns(30)
            }
            row("Exclude (names contain), comma-separated (-exc):") {
                textField().bindText({ work.excludeFilters.joinToString(", ") }, { work.excludeFilters = splitCsv(it) }).columns(30)
            }
        }
        group("Sources & Reliability") {
            row { checkBox("Skip restore preview / compat check (-n)").bindSelected(work::noRestore) }
            row { checkBox("Ignore failed package sources (-ifs)").bindSelected(work::ignoreFailedSources) }
            row("Idle timeout, seconds (-it):") { intField(work::idleTimeoutSeconds, 120) }
            row("Runtime identifier (-rt):") { textField().bindText(work::runtime).columns(14) }
            row("NuGet credential log level (-ncll):") {
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
