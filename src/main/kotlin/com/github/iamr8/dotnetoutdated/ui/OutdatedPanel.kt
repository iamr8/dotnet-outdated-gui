package com.github.iamr8.dotnetoutdated.ui

import com.github.iamr8.dotnetoutdated.cli.CliFailures
import com.github.iamr8.dotnetoutdated.cli.DotnetOutdatedRunner
import com.github.iamr8.dotnetoutdated.cli.Solution
import com.github.iamr8.dotnetoutdated.cli.SolutionModel
import com.github.iamr8.dotnetoutdated.parse.ListPackagesParser
import com.github.iamr8.dotnetoutdated.parse.OutdatedReportParser
import com.github.iamr8.dotnetoutdated.settings.OutdatedConfigurable
import com.github.iamr8.dotnetoutdated.settings.OutdatedOptionsService
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GridBagLayout
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/** Root component of the "dotnet outdated GUI" tool window. */
class OutdatedPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val runner = DotnetOutdatedRunner()
    private val optionsService = OutdatedOptionsService.getInstance(project)
    private val listView = PackageListView(onSelectionChanged = { toolbar.updateActionsAsync() })
    private val status = JBLabel(" ")

    private val toolbar: ActionToolbar = buildToolbar()

    private var solution: Solution? = null
    /** Names of the solution's projects to include in the view (empty = show everything). */
    private var includedProjects: MutableSet<String> = linkedSetOf()
    /** Last CLI result, unfiltered; the view is [includedProjects] applied to this. */
    private var allRows: List<PackageSection> = emptyList()
    private var updatesChecked = false
    private var skippedProjects = 0
    private var busy = false
    private var listedOnce = false

    @Volatile
    private var cliAvailable = false
    private val centerLayout = CardLayout()
    private val centerPanel = JPanel(centerLayout)

    init {
        add(toolbar.component, BorderLayout.NORTH)
        centerPanel.add(JBScrollPane(listView.component), CARD_TABLE)
        centerPanel.add(cliMissingComponent(), CARD_CLI)
        add(centerPanel, BorderLayout.CENTER)
        add(status.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.SOUTH)

        discoverSolution()
    }

    /** Lazy: on first show, verify the CLI and (only if enabled) list packages. */
    override fun addNotify() {
        super.addNotify()
        if (!listedOnce) {
            listedOnce = true
            if (optionsService.options.includeUpToDate) runListPackages() else checkCliAndPrompt()
        }
    }

    private fun showCard(name: String) = centerLayout.show(centerPanel, name)

    /** Centered "install the CLI" message with a link to the dotnet-outdated repo. */
    private fun cliMissingComponent(): JComponent {
        val html = """
            <html><div style='text-align:center; padding:24px;'>
              <p style='font-size:13px;'>The <b>dotnet-outdated</b> CLI is required to use this tool.</p>
              <p>Install it (see the <a href="$INSTALL_URL">installation instructions</a>),
                 then press <b>Reload</b> or <b>Check for Updates</b>.</p>
            </div></html>
        """.trimIndent()
        val pane = JEditorPane("text/html", html).apply {
            isEditable = false
            isOpaque = false
            addHyperlinkListener { e -> if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) BrowserUtil.browse(INSTALL_URL) }
        }
        return JPanel(GridBagLayout()).apply { add(pane) }
    }

    /** Background CLI check used on first open when auto-listing is disabled. */
    private fun checkCliAndPrompt() {
        if (busy) return
        busy = true
        toolbar.updateActionsAsync()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "dotnet outdated GUI: checking CLI availability", false) {
            override fun run(indicator: ProgressIndicator) {
                cliAvailable = runner.isOutdatedInstalled()
            }
            override fun onSuccess() = onEdt {
                busy = false
                if (cliAvailable) {
                    showCard(CARD_TABLE)
                    setStatus("Press Check for Updates to find outdated packages.")
                } else {
                    showCard(CARD_CLI)
                }
                toolbar.updateActionsAsync()
            }
        })
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(ReloadPackagesAction())
            add(CheckForUpdatesAction())
            add(ScopeAction())
            addSeparator()
            add(SelectAllAction())
            add(UpdateAction())
            addSeparator()
            add(OptionsAction())
        }
        return ActionManager.getInstance()
            .createActionToolbar("NuGetExtended", group, true)
            .also { it.targetComponent = this }
    }

    private fun basePath(): String = project.basePath ?: System.getProperty("user.dir")

    /** True when every project in the open solution is included (the default). */
    private fun allProjectsSelected(): Boolean {
        val sln = solution ?: return true
        return sln.projects.isNotEmpty() && includedProjects.size == sln.projects.size
    }

    /** One call on the whole solution — used when all projects are selected. */
    private fun solutionUnit(): ScanUnit? =
        solution?.let { ScanUnit(it.name, it.solutionPath) }

    /** One unit per included project (or the base dir when there is no solution). */
    private fun perProjectUnits(): List<ScanUnit> {
        val sln = solution
        if (sln != null && sln.projects.isNotEmpty()) {
            return sln.projects.filter { it.name in includedProjects }
                .ifEmpty { sln.projects }
                .map { ScanUnit(it.name, it.path) }
        }
        val base = basePath()
        return listOf(ScanUnit(File(base).name, base))
    }

    /**
     * Preferred scan units: the whole solution in one call when all projects are selected.
     * A subset selection is always per-project. For tools that can't load unsupported project
     * types (e.g. `dotnet list package` chokes on `.shproj`), pass [toleratesUnsupported] = false
     * to go straight to per-project when the solution has such projects. `dotnet outdated`
     * tolerates them, so it keeps the fast single whole-solution call.
     */
    private fun primaryUnits(toleratesUnsupported: Boolean): List<ScanUnit> {
        val wholeSolutionOk = allProjectsSelected() &&
            (toleratesUnsupported || solution?.hasUnsupportedProjects != true)
        return if (wholeSolutionOk) listOfNotNull(solutionUnit()).ifEmpty { perProjectUnits() }
        else perProjectUnits()
    }

    /** Runs [exec] over each unit in parallel, collecting rows and per-unit failures. */
    private fun runUnits(
        units: List<ScanUnit>,
        indicator: ProgressIndicator,
        exec: (ScanUnit) -> Pair<List<PackageSection>?, ScanFailure?>,
    ): Pair<List<PackageSection>, List<ScanFailure>> {
        if (units.isEmpty()) return emptyList<PackageSection>() to emptyList()
        if (units.size == 1) {
            indicator.isIndeterminate = true
            indicator.text = "Analyzing ${units[0].label}…"
            indicator.text2 = "Running dotnet on ${File(units[0].path).name}"
            val (rows, error) = exec(units[0])
            return (rows ?: emptyList()) to listOfNotNull(error)
        }

        val rows = java.util.Collections.synchronizedList(mutableListOf<PackageSection>())
        val failures = java.util.Collections.synchronizedList(mutableListOf<ScanFailure>())
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        indicator.isIndeterminate = false
        indicator.text = "Analyzing 0 / ${units.size} projects…"
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(units.size, MAX_PARALLEL))
        try {
            val futures = units.map { unit ->
                pool.submit {
                    indicator.text2 = "Analyzing ${unit.label}"
                    val (unitRows, error) = exec(unit)
                    if (unitRows != null) rows.addAll(unitRows) else if (error != null) failures.add(error)
                    val completed = done.incrementAndGet()
                    indicator.fraction = completed.toDouble() / units.size
                    indicator.text = "Analyzed $completed / ${units.size} projects…"
                }
            }
            for (f in futures) {
                indicator.checkCanceled()
                f.get()
            }
        } finally {
            pool.shutdownNow()
        }
        return rows.toList() to failures.toList()
    }

    private fun discoverSolution() {
        solution = SolutionModel.discover(File(basePath()), project.name)
        includedProjects = solution?.projects?.map { it.name }?.toMutableSet() ?: linkedSetOf()
        toolbar.updateActionsAsync()
    }

    private fun scopeLabel(): String {
        val sln = solution ?: return "Scope: (no solution)"
        val total = sln.projects.size
        return "Scope: ${sln.name} (${includedProjects.size}/$total)"
    }

    private fun setStatus(text: String) {
        status.text = text.ifBlank { " " }
    }

    /**
     * A dotnet / user-project failure (missing CLI, unrestored project, a package version that
     * doesn't exist, …) — *not* a plugin bug. Surfaced as a balloon with the full CLI output one
     * click away, plus `LOG.warn` for idea.log.
     *
     * Deliberately never `LOG.error`: that opens the IDE's fatal-error dialog and would push other
     * people's broken solutions into the plugin's Marketplace Exception Analyzer.
     */
    private fun notifyFailure(
        context: String,
        failures: List<ScanFailure>,
        type: NotificationType = NotificationType.WARNING,
        updateStatus: Boolean = true,
    ) {
        if (failures.isEmpty()) return
        val details = failures.joinToString("\n\n") { it.details }
        LOG.warn("dotnet outdated GUI: $context\n$details")

        val shown = failures.take(MAX_SHOWN_FAILURES)
        val body = buildString {
            shown.forEach { append(StringUtil.escapeXmlEntities(it.line)).append("<br/>") }
            val more = failures.size - shown.size
            if (more > 0) append("…and $more more project(s).")
        }
        onEdt {
            if (updateStatus) setStatus("$context — see the notification for details.")
            notificationGroup()
                .createNotification(context, body, type)
                .addAction(
                    NotificationAction.createSimpleExpiring("Copy Details") {
                        CopyPasteManager.getInstance().setContents(StringSelection("$context\n\n$details"))
                    },
                )
                .notify(project)
        }
    }

    /** An unexpected plugin-side exception: this one *is* worth reporting (IDE error reporter). */
    private fun reportInternalError(context: String, throwable: Throwable) {
        LOG.error("dotnet outdated GUI: $context", throwable)
        onEdt { setStatus("$context — see the IDE error report for details.") }
    }

    private fun notificationGroup() =
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)

    /** Blocking CLI presence check (call off the EDT); caches the positive result. */
    private fun ensureCli(): Boolean {
        if (!cliAvailable) cliAvailable = runner.isOutdatedInstalled()
        return cliAvailable
    }

    /** Render the list from [allRows] and update the status line. EDT only. */
    private fun render() {
        listView.setData(allRows)
        val total = allRows.sumOf { it.deps.size }
        val projects = allRows.map { it.projectName }.distinct().size
        val outdated = allRows.sumOf { s -> s.deps.count { it.outdated } }
        val skipped = if (skippedProjects > 0) " ($skippedProjects skipped)" else ""
        setStatus(
            when {
                total == 0 && skippedProjects == 0 -> "No NuGet packages found."
                total == 0 -> "No packages listed$skipped."
                !updatesChecked -> "$total package(s) in $projects project(s)$skipped. Press Check for Updates."
                outdated == 0 -> "$total package(s) in $projects project(s) — all up to date$skipped."
                else -> "$total package(s) in $projects project(s), $outdated outdated$skipped."
            },
        )
    }

    private fun showScopePicker(anchor: JComponent) {
        val projects = solution?.projects ?: return
        if (projects.isEmpty()) return
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8) }
        val boxes = projects.map { p ->
            JBCheckBox(p.name, p.name in includedProjects).also { panel.add(it) }
        }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(panel), boxes.firstOrNull())
            .setRequestFocus(true)
            .setTitle("Projects in ${solution?.name}")
            .createPopup()
        val before = includedProjects.toSet()
        popup.setFinalRunnable {
            var next = projects.indices
                .filter { boxes[it].isSelected }
                .map { projects[it].name }
                .toMutableSet()
            if (next.isEmpty()) next = projects.map { it.name }.toMutableSet()
            if (next != before) {
                includedProjects = next
                toolbar.updateActionsAsync()
                runListPackages() // re-list only when the selection actually changed
            }
        }
        popup.showUnderneathOf(anchor)
    }

    /** Phase 1: discover packages + current versions locally (no `dotnet outdated`, no network). */
    private fun runListPackages() {
        if (busy) return
        busy = true
        toolbar.updateActionsAsync()
        setStatus("Finding packages…")

        val exec: (ScanUnit) -> Pair<List<PackageSection>?, ScanFailure?> = { unit ->
            val result = runner.listPackages(unit.path, basePath(), optionsService.options)
            if (result.json.isBlank()) null to failureOf(unit, result.stderr, result.stdout)
            else OutdatedRows.buildFromListing(ListPackagesParser.parse(result.json), unit.path) to null
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "dotnet outdated GUI: listing NuGet packages (dotnet list package)", true) {
            private var rows: List<PackageSection> = emptyList()
            private var failures: List<ScanFailure> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                if (!ensureCli()) return
                // dotnet list package can't load .shproj etc. -> per-project when unsupported present;
                // it also hard-fails on a single unrestored project, so recover the rest per-project.
                val (r, f) = runScoped(indicator, toleratesUnsupported = false, fallbackToPerProject = true, exec)
                rows = r; failures = f
            }

            override fun onSuccess() = onEdt {
                busy = false
                if (!cliAvailable) { showCard(CARD_CLI); toolbar.updateActionsAsync(); return@onEdt }
                finishScan(rows, failures, checked = false, hardFailContext = "Listing packages failed", skipContext = "Some projects were skipped while listing")
            }

            override fun onThrowable(error: Throwable) = onEdt {
                busy = false
                reportInternalError("Listing packages failed", error)
                toolbar.updateActionsAsync()
            }
        })
    }

    /** Phase 2: run `dotnet outdated` to fill New Version. */
    private fun runScan() {
        if (busy) return
        busy = true
        toolbar.updateActionsAsync()
        setStatus("Checking for updates…")

        val exec: (ScanUnit) -> Pair<List<PackageSection>?, ScanFailure?> = { unit ->
            val result = runner.scan(unit.path, basePath(), optionsService.options)
            when {
                result.timedOut -> null to failureOf(unit, result.stderr, result.stdout, summary = "timed out")
                result.exitCode != 0 && result.json.isBlank() -> null to failureOf(unit, result.stderr, result.stdout)
                else -> OutdatedRows.build(OutdatedReportParser.parse(result.json), unit.path) to null
            }
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "dotnet outdated GUI: checking for package updates (dotnet outdated)", true) {
            private var rows: List<PackageSection> = emptyList()
            private var failures: List<ScanFailure> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                if (!ensureCli()) return
                // dotnet outdated tolerates .shproj etc. -> keep the fast single whole-solution call.
                // No per-project fan-out on failure: it fails fast and names the broken project,
                // and re-running all projects one-by-one is a minutes-long crawl that fixes nothing.
                val (r, f) = runScoped(indicator, toleratesUnsupported = true, fallbackToPerProject = false, exec)
                rows = r; failures = f
            }

            override fun onSuccess() = onEdt {
                busy = false
                if (!cliAvailable) { showCard(CARD_CLI); toolbar.updateActionsAsync(); return@onEdt }
                finishScan(rows, failures, checked = true, hardFailContext = "Update check failed", skipContext = "Some projects were skipped during the update check")
            }

            override fun onThrowable(error: Throwable) = onEdt {
                busy = false
                reportInternalError("Update check failed", error)
                toolbar.updateActionsAsync()
            }
        })
    }

    /**
     * Runs the primary units: the whole solution in one call when all projects are selected (the
     * default), or one unit per project when the user narrowed the scope in the picker.
     *
     * [fallbackToPerProject] controls what happens when the whole-solution call comes back empty
     * with a failure:
     *  - Phase 2 (`dotnet outdated`) passes `false`: NO fan-out. The CLI fails fast and names the
     *    broken project (e.g. an `NU1102` version that doesn't exist); re-running every project
     *    one-by-one turns a ~15s solution call into a minutes-long crawl without fixing anything —
     *    the broken projects still fail. The CLI error is surfaced instead; per-project is an
     *    explicit capability via the scope picker.
     *  - Phase 1 (`dotnet list package`) passes `true`: it hard-fails on any single unrestored or
     *    unsupported project, so recover the rest with a per-project fan-out (each call is offline
     *    and cheap). One broken project shouldn't blank the whole list.
     */
    private fun runScoped(
        indicator: ProgressIndicator,
        toleratesUnsupported: Boolean,
        fallbackToPerProject: Boolean,
        exec: (ScanUnit) -> Pair<List<PackageSection>?, ScanFailure?>,
    ): Pair<List<PackageSection>, List<ScanFailure>> {
        val primary = primaryUnits(toleratesUnsupported)
        val (rows, failures) = runUnits(primary, indicator, exec)
        if (fallbackToPerProject && rows.isEmpty() && failures.isNotEmpty()) {
            val wasWholeSolution = primary.size == 1 && primary.first().path == solution?.solutionPath
            if (wasWholeSolution) {
                val fallback = perProjectUnits()
                if (fallback.isNotEmpty()) return runUnits(fallback, indicator, exec)
            }
        }
        return rows to failures
    }

    private fun finishScan(
        rows: List<PackageSection>,
        failures: List<ScanFailure>,
        checked: Boolean,
        hardFailContext: String,
        skipContext: String,
    ) {
        showCard(CARD_TABLE)
        allRows = rows // PackageListView sorts sections/rows for a stable order
        skippedProjects = failures.size
        updatesChecked = checked
        render()
        // Nothing came back at all -> tell the user why. Partial results already say "(n skipped)"
        // in the status line, so that case gets a quiet, non-status-hijacking balloon.
        if (rows.isEmpty() && failures.isNotEmpty()) notifyFailure(hardFailContext, failures)
        else if (failures.isNotEmpty()) notifyFailure(skipContext, failures, NotificationType.INFORMATION, updateStatus = false)
        toolbar.updateActionsAsync()
    }

    private fun runUpgrade() {
        if (busy) return
        val byTarget = listView.checkedByTarget()
        val packageCount = byTarget.values.flatten().distinct().size
        if (packageCount == 0) return

        val answer = Messages.showYesNoDialog(
            project,
            "Upgrade $packageCount package(s)? This edits your .csproj files.\n\n" +
                "Note: dotnet outdated matches package names by substring, so closely named " +
                "packages may also be upgraded. The list will re-scan afterwards.",
            "Upgrade Packages",
            "Upgrade",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return

        busy = true
        toolbar.updateActionsAsync()
        setStatus("Upgrading $packageCount package(s)…")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "dotnet outdated GUI: upgrading selected packages (dotnet outdated -u)", true) {
            private val failures = mutableListOf<ScanFailure>()

            override fun run(indicator: ProgressIndicator) {
                for ((targetPath, names) in byTarget) {
                    indicator.checkCanceled()
                    val unit = ScanUnit(File(targetPath).name, targetPath)
                    indicator.text = unit.label
                    val result = runner.upgrade(targetPath, names, basePath(), optionsService.options)
                    if (result.timedOut) failures += failureOf(unit, result.stderr, result.stdout, summary = "timed out")
                    else if (result.exitCode != 0) failures += failureOf(unit, result.stderr, result.stdout)
                }
            }

            override fun onSuccess() = onEdt {
                busy = false
                toolbar.updateActionsAsync()
                if (failures.isNotEmpty()) {
                    notifyFailure("Some upgrades failed", failures)
                } else {
                    setStatus("Upgrade complete. Re-checking…")
                }
                runScan()
            }

            override fun onThrowable(error: Throwable) = onEdt {
                busy = false
                reportInternalError("Upgrade failed", error)
                toolbar.updateActionsAsync()
            }
        })
    }

    /** Builds a [ScanFailure]: short summary for the UI, raw CLI output kept for "Copy Details". */
    private fun failureOf(
        unit: ScanUnit,
        stderr: String,
        stdout: String,
        summary: String = CliFailures.describe(stderr, stdout),
    ): ScanFailure {
        val raw = listOf(stderr, stdout).filter { it.isNotBlank() }.joinToString("\n").trim()
        return ScanFailure(unit.label, summary, raw)
    }

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block)

    // --- Toolbar actions ---------------------------------------------------

    private inner class ReloadPackagesAction : AnAction(
        "Reload Packages",
        "List all current packages (enable \"List all packages\" in settings)",
        AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            // Listing everything is the heavy capability; only available when the toggle is on.
            e.presentation.isEnabled = !busy && optionsService.options.includeUpToDate
        }
        override fun actionPerformed(e: AnActionEvent) = runListPackages()
    }

    private inner class CheckForUpdatesAction : AnAction(
        "Check for Updates",
        "Run dotnet outdated to fill New Version",
        AllIcons.Vcs.Fetch,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !busy
        }
        override fun actionPerformed(e: AnActionEvent) = runScan()
    }

    private inner class ScopeAction : AnAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.text = scopeLabel()
            e.presentation.icon = AllIcons.General.Filter
            e.presentation.isEnabled = !busy && (solution?.projects?.isNotEmpty() == true)
        }
        override fun actionPerformed(e: AnActionEvent) {
            showScopePicker(e.inputEvent?.component as? JComponent ?: toolbar.component)
        }
    }

    private inner class SelectAllAction : AnAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            val allChecked = listView.allOutdatedChecked()
            e.presentation.text = if (allChecked) "Deselect All" else "Select All"
            e.presentation.description = "Toggle the checkbox on every outdated package"
            e.presentation.icon = if (allChecked) AllIcons.Actions.Unselectall else AllIcons.Actions.Selectall
            e.presentation.isEnabled = !busy && listView.hasOutdated()
        }
        override fun actionPerformed(e: AnActionEvent) = listView.toggleSelectAll()
    }

    private inner class UpdateAction : AnAction("Update Selected", "Upgrade the checked packages", AllIcons.Actions.Download) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !busy && listView.hasChecked()
        }
        override fun actionPerformed(e: AnActionEvent) = runUpgrade()
    }

    private inner class OptionsAction : AnAction("Settings", "Open dotnet outdated GUI settings", AllIcons.General.Settings) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, OutdatedConfigurable::class.java)
        }
    }

    companion object {
        private val LOG = logger<OutdatedPanel>()
        private val MAX_PARALLEL = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)
        private const val CARD_TABLE = "table"
        private const val CARD_CLI = "cli"
        private const val INSTALL_URL = "https://github.com/dotnet-outdated/dotnet-outdated#installation"
        /** Must match the <notificationGroup id="…"> in plugin.xml. */
        private const val NOTIFICATION_GROUP = "dotnet outdated GUI"
        /** Balloons stay readable; the rest is in "Copy Details" and idea.log. */
        private const val MAX_SHOWN_FAILURES = 3
    }

    /** A single thing to scan: a project (or the base dir), with a display label and CLI path. */
    private data class ScanUnit(val label: String, val path: String)

    /**
     * A unit that couldn't be scanned: [summary] is the short, actionable line shown to the user,
     * [raw] the untouched CLI output kept for "Copy Details" / idea.log.
     */
    private data class ScanFailure(val label: String, val summary: String, val raw: String) {
        val line: String get() = "$label: $summary"
        val details: String get() = if (raw.isBlank()) line else "$line\n$raw"
    }
}
