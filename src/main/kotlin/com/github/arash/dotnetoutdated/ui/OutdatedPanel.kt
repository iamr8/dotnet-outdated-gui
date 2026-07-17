package com.github.arash.dotnetoutdated.ui

import com.github.arash.dotnetoutdated.cli.DotnetOutdatedRunner
import com.github.arash.dotnetoutdated.cli.Solution
import com.github.arash.dotnetoutdated.cli.SolutionModel
import com.github.arash.dotnetoutdated.parse.ListPackagesParser
import com.github.arash.dotnetoutdated.parse.OutdatedReportParser
import com.github.arash.dotnetoutdated.settings.OutdatedConfigurable
import com.github.arash.dotnetoutdated.settings.OutdatedOptionsService
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
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
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GridBagLayout
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
    private var allRows: List<ProjectRow> = emptyList()
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
              <p>Install it, then press <b>Reload</b> or <b>Check for Updates</b>:</p>
              <p><code>dotnet tool install --global dotnet-outdated-tool</code></p>
              <p><a href="$REPO_URL">$REPO_URL</a></p>
            </div></html>
        """.trimIndent()
        val pane = JEditorPane("text/html", html).apply {
            isEditable = false
            isOpaque = false
            addHyperlinkListener { e -> if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) BrowserUtil.browse(REPO_URL) }
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

    /** Runs [exec] over each unit in parallel, collecting rows and per-unit failure messages. */
    private fun runUnits(
        units: List<ScanUnit>,
        indicator: ProgressIndicator,
        exec: (ScanUnit) -> Pair<List<ProjectRow>?, String?>,
    ): Pair<List<ProjectRow>, List<String>> {
        if (units.isEmpty()) return emptyList<ProjectRow>() to emptyList()
        if (units.size == 1) {
            indicator.isIndeterminate = true
            indicator.text = "Analyzing ${units[0].label}…"
            indicator.text2 = "Running dotnet on ${File(units[0].path).name}"
            val (rows, error) = exec(units[0])
            return (rows ?: emptyList()) to (error?.let { listOf("${units[0].label}: $it") } ?: emptyList())
        }

        val rows = java.util.Collections.synchronizedList(mutableListOf<ProjectRow>())
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        indicator.isIndeterminate = false
        indicator.text = "Analyzing 0 / ${units.size} projects…"
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(units.size, MAX_PARALLEL))
        try {
            val futures = units.map { unit ->
                pool.submit {
                    indicator.text2 = "Analyzing ${unit.label}"
                    val (unitRows, error) = exec(unit)
                    if (unitRows != null) rows.addAll(unitRows) else if (error != null) failures.add("${unit.label}: $error")
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

    /** Route errors to the IDE error reporter (selectable/copyable) rather than the status bar. */
    private fun reportError(context: String, detail: String? = null, throwable: Throwable? = null) {
        val message = buildString {
            append("dotnet outdated GUI: ").append(context)
            if (!detail.isNullOrBlank()) append('\n').append(detail)
        }
        if (throwable != null) LOG.error(message, throwable) else LOG.error(message)
        onEdt { setStatus("$context — see the IDE error report for details.") }
    }

    /** Logs non-fatal detail to the error reporter without hijacking the (informative) status line. */
    private fun logQuietly(context: String, detail: String) {
        LOG.error("dotnet outdated GUI: $context\n$detail")
    }

    /** Blocking CLI presence check (call off the EDT); caches the positive result. */
    private fun ensureCli(): Boolean {
        if (!cliAvailable) cliAvailable = runner.isOutdatedInstalled()
        return cliAvailable
    }

    /** Render the list from [allRows] and update the status line. EDT only. */
    private fun render() {
        listView.setData(allRows)
        val total = allRows.sumOf { it.deps.size }
        val outdated = allRows.sumOf { p -> p.deps.count { it.outdated } }
        val skipped = if (skippedProjects > 0) " ($skippedProjects skipped)" else ""
        setStatus(
            when {
                total == 0 && skippedProjects == 0 -> "No NuGet packages found."
                total == 0 -> "No packages listed$skipped."
                !updatesChecked -> "$total package(s) in ${allRows.size} project(s)$skipped. Press Check for Updates."
                outdated == 0 -> "$total package(s) in ${allRows.size} project(s) — all up to date$skipped."
                else -> "$total package(s) in ${allRows.size} project(s), $outdated outdated$skipped."
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

        val exec: (ScanUnit) -> Pair<List<ProjectRow>?, String?> = { unit ->
            val result = runner.listPackages(unit.path, basePath(), optionsService.options)
            if (result.json.isBlank()) null to describeFailure(result.stderr, result.stdout)
            else OutdatedRows.buildFromListing(ListPackagesParser.parse(result.json), unit.path) to null
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "dotnet outdated GUI: listing NuGet packages (dotnet list package)", true) {
            private var rows: List<ProjectRow> = emptyList()
            private var failures: List<String> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                if (!ensureCli()) return
                // dotnet list package can't load .shproj etc. -> per-project when unsupported present.
                val (r, f) = runScoped(indicator, toleratesUnsupported = false, exec)
                rows = r; failures = f
            }

            override fun onSuccess() = onEdt {
                busy = false
                if (!cliAvailable) { showCard(CARD_CLI); toolbar.updateActionsAsync(); return@onEdt }
                finishScan(rows, failures, checked = false, hardFailContext = "Listing packages failed", skipContext = "Some projects were skipped while listing")
            }

            override fun onThrowable(error: Throwable) = onEdt {
                busy = false
                reportError("Listing packages failed", throwable = error)
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

        val exec: (ScanUnit) -> Pair<List<ProjectRow>?, String?> = { unit ->
            val result = runner.scan(unit.path, basePath(), optionsService.options)
            when {
                result.timedOut -> null to "timed out"
                result.exitCode != 0 && result.json.isBlank() -> null to describeFailure(result.stderr, result.stdout)
                else -> OutdatedRows.build(OutdatedReportParser.parse(result.json), unit.path) to null
            }
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "dotnet outdated GUI: checking for package updates (dotnet outdated)", true) {
            private var rows: List<ProjectRow> = emptyList()
            private var failures: List<String> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                if (!ensureCli()) return
                // dotnet outdated tolerates .shproj etc. -> keep the fast single whole-solution call.
                val (r, f) = runScoped(indicator, toleratesUnsupported = true, exec)
                rows = r; failures = f
            }

            override fun onSuccess() = onEdt {
                busy = false
                if (!cliAvailable) { showCard(CARD_CLI); toolbar.updateActionsAsync(); return@onEdt }
                finishScan(rows, failures, checked = true, hardFailContext = "Update check failed", skipContext = "Some projects were skipped during the update check")
            }

            override fun onThrowable(error: Throwable) = onEdt {
                busy = false
                reportError("Update check failed", throwable = error)
                toolbar.updateActionsAsync()
            }
        })
    }

    /**
     * Runs the primary units (whole solution when all selected); if that yields nothing but
     * produced failures, falls back to per-project so one broken project (e.g. a `.shproj`
     * that the dotnet CLI can't load) can't sink the whole solution.
     */
    private fun runScoped(
        indicator: ProgressIndicator,
        toleratesUnsupported: Boolean,
        exec: (ScanUnit) -> Pair<List<ProjectRow>?, String?>,
    ): Pair<List<ProjectRow>, List<String>> {
        val primary = primaryUnits(toleratesUnsupported)
        val (rows, failures) = runUnits(primary, indicator, exec)
        val wasWholeSolution = primary.size == 1 && primary.first().path == solution?.solutionPath
        if (rows.isEmpty() && failures.isNotEmpty() && wasWholeSolution) {
            val fallback = perProjectUnits()
            if (fallback.isNotEmpty()) return runUnits(fallback, indicator, exec)
        }
        return rows to failures
    }

    private fun finishScan(
        rows: List<ProjectRow>,
        failures: List<String>,
        checked: Boolean,
        hardFailContext: String,
        skipContext: String,
    ) {
        showCard(CARD_TABLE)
        allRows = rows.sortedBy { it.name.lowercase() } // stable order despite parallel completion
        skippedProjects = failures.size
        updatesChecked = checked
        render()
        if (rows.isEmpty() && failures.isNotEmpty()) reportError(hardFailContext, failures.joinToString("\n"))
        else if (failures.isNotEmpty()) logQuietly(skipContext, failures.joinToString("\n"))
        toolbar.updateActionsAsync()
    }

    private fun runUpgrade() {
        if (busy) return
        val byTarget = listView.selectedByTarget()
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
            private val failures = mutableListOf<String>()

            override fun run(indicator: ProgressIndicator) {
                for ((targetPath, names) in byTarget) {
                    indicator.checkCanceled()
                    indicator.text = File(targetPath).name
                    val result = runner.upgrade(targetPath, names, basePath(), optionsService.options)
                    if (result.timedOut) failures += "${File(targetPath).name}: timed out"
                    else if (result.exitCode != 0) failures += "${File(targetPath).name}: ${describeFailure(result.stderr, result.stdout)}"
                }
            }

            override fun onSuccess() = onEdt {
                busy = false
                toolbar.updateActionsAsync()
                if (failures.isNotEmpty()) {
                    reportError("Some upgrades failed", failures.joinToString("\n"))
                } else {
                    setStatus("Upgrade complete. Re-checking…")
                }
                runScan()
            }

            override fun onThrowable(error: Throwable) = onEdt {
                busy = false
                reportError("Upgrade failed", throwable = error)
                toolbar.updateActionsAsync()
            }
        })
    }

    private fun describeFailure(stderr: String, stdout: String): String {
        val combined = (stderr + "\n" + stdout).lowercase()
        return when {
            "no executable found matching command" in combined || "is not a dotnet command" in combined ->
                "dotnet-outdated tool not found. Install: dotnet tool install -g dotnet-outdated-tool"
            "command not found" in combined || combined.isBlank() ->
                "Could not run dotnet. Ensure the .NET SDK is installed and on PATH."
            "no assets" in combined || "run a restore" in combined || "project.assets.json" in combined ->
                "The project isn't restored. Run 'dotnet restore' (or build) and try again."
            else -> stderr.ifBlank { stdout }.ifBlank { "dotnet exited with a non-zero status." }
        }
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

    private inner class UpdateAction : AnAction("Update Selected", "Upgrade the selected packages (multi-select in the list)", AllIcons.Actions.Download) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !busy && listView.hasSelectedOutdated()
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
        private const val REPO_URL = "https://github.com/dotnet-outdated/dotnet-outdated"
    }

    /** A single thing to scan: a project (or the base dir), with a display label and CLI path. */
    private data class ScanUnit(val label: String, val path: String)
}
