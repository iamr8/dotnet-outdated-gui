package com.github.iamr8.dotnetoutdated.ui

import com.github.iamr8.dotnetoutdated.model.SeverityColor
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ThreeStateCheckBox
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/** Maps a severity bucket to a theme-aware color. */
fun SeverityColor.toJBColor(): Color = when (this) {
    SeverityColor.RED -> JBColor(0xD64B4B, 0xE06C6C)
    SeverityColor.YELLOW -> JBColor(0xB8860B, 0xD9A441)
    SeverityColor.GREEN -> JBColor(0x3C9A3C, 0x549E54)
    SeverityColor.NONE -> JBColor.foreground()
}

internal sealed class ListEntry
/** A `Project · framework` section header carrying its own package rows for the section toggle. */
internal class HeaderEntry(val title: String, val packages: List<PackageEntry>) : ListEntry()
internal class PackageEntry(val dep: DepRow, val target: String) : ListEntry() {
    var checked: Boolean = false
}

/** Aggregate checkbox state of a section's outdated rows. */
internal enum class CheckState { NONE, SOME, ALL }

/** Pure list logic (no Swing) — unit-tested; [PackageListView] is the thin Swing wiring. */
internal object PackageListLogic {

    /** Section headers + package rows in display order: sections sorted, deps outdated-first by name. */
    fun buildEntries(sections: List<PackageSection>): List<ListEntry> {
        val entries = ArrayList<ListEntry>()
        for (section in sections.sortedWith(compareBy({ it.projectName.lowercase() }, { it.framework }))) {
            val packages = section.deps
                .sortedWith(compareByDescending<DepRow> { it.outdated }.thenBy { it.name.lowercase() })
                .map { PackageEntry(it, section.upgradeTarget) }
            entries.add(HeaderEntry("${section.projectName}  ·  ${section.framework}", packages))
            entries.addAll(packages)
        }
        return entries
    }

    fun hasChecked(entries: List<ListEntry>): Boolean =
        entries.any { it is PackageEntry && it.checked && it.dep.outdated }

    /** Checkable (outdated) package rows. */
    fun outdatedEntries(entries: List<ListEntry>): List<PackageEntry> =
        entries.filterIsInstance<PackageEntry>().filter { it.dep.outdated }

    /** True when at least one row is checkable. */
    fun hasOutdated(entries: List<ListEntry>): Boolean = outdatedEntries(entries).isNotEmpty()

    /** True when there is at least one outdated row and all of them are checked. */
    fun allOutdatedChecked(entries: List<ListEntry>): Boolean {
        val outdated = outdatedEntries(entries)
        return outdated.isNotEmpty() && outdated.all { it.checked }
    }

    /** Checked outdated packages grouped by upgrade-target path, names deduped. */
    fun checkedByTarget(entries: List<ListEntry>): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        for (entry in entries) {
            if (entry is PackageEntry && entry.checked && entry.dep.outdated) {
                result.getOrPut(entry.target) { mutableListOf() }.add(entry.dep.name)
            }
        }
        return result.mapValues { it.value.distinct() }
    }

    /** Next checkbox state for a selection: if any is unchecked, check all; else uncheck all. */
    fun nextToggleState(selectedOutdated: List<PackageEntry>): Boolean =
        !selectedOutdated.all { it.checked }

    /** Aggregate state of a header's outdated rows: NONE, SOME (partial), or ALL. */
    fun sectionCheckState(header: HeaderEntry): CheckState {
        val outdated = header.packages.filter { it.dep.outdated }
        if (outdated.isEmpty()) return CheckState.NONE
        val checked = outdated.count { it.checked }
        return when (checked) {
            0 -> CheckState.NONE
            outdated.size -> CheckState.ALL
            else -> CheckState.SOME
        }
    }

    /** True when the header has at least one checkable (outdated) row. */
    fun sectionHasOutdated(header: HeaderEntry): Boolean = header.packages.any { it.dep.outdated }
}

/**
 * Grouped package list mirroring the `dotnet outdated` CLI / Rider NuGet view: a
 * `ProjectName · framework` header per project+TFM section, then one row per package with a
 * checkbox — `Name · Current` on the left, new version (colored by severity) right-aligned.
 * Check multiple outdated packages to drive "Update Selected". Type to speed-search by name.
 */
class PackageListView(private val onSelectionChanged: () -> Unit) {

    private val model = DefaultListModel<ListEntry>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        cellRenderer = EntryRenderer()
        addListSelectionListener { dropHeaderSelections() }
    }

    init {
        // Toggle a checkbox by clicking it.
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index < 0) return
                when (val entry = model.getElementAt(index)) {
                    is PackageEntry -> if (entry.dep.outdated && e.x <= CHECKBOX_HIT_WIDTH) {
                        entry.checked = !entry.checked
                        list.repaint()
                        onSelectionChanged()
                    }
                    // The section checkbox toggles every outdated package under the header.
                    is HeaderEntry -> if (e.x <= CHECKBOX_HIT_WIDTH) toggleSection(entry)
                }
            }
        })
        // Space toggles the checkbox on all selected outdated rows.
        list.registerKeyboardAction(
            { toggleCheckedForSelection() },
            KeyStroke.getKeyStroke("SPACE"),
            JComponent.WHEN_FOCUSED,
        )
        // Rider-style speed search: hidden until you type, then filters/navigates by text.
        ListSpeedSearch.installOn(list) { entry ->
            when (entry) {
                is PackageEntry -> entry.dep.name
                is HeaderEntry -> entry.title
                else -> ""
            }
        }
    }

    val component: JComponent get() = list

    fun setData(sections: List<PackageSection>) {
        model.clear()
        PackageListLogic.buildEntries(sections).forEach { model.addElement(it) }
    }

    fun hasChecked(): Boolean = PackageListLogic.hasChecked(entries())

    /** True when at least one checkable (outdated) row exists. */
    fun hasOutdated(): Boolean = PackageListLogic.hasOutdated(entries())

    /** True when every outdated row is checked (drives the Select/Deselect All label). */
    fun allOutdatedChecked(): Boolean = PackageListLogic.allOutdatedChecked(entries())

    /** Check all outdated rows if any is unchecked, else uncheck them all. */
    fun toggleSelectAll() {
        val outdated = PackageListLogic.outdatedEntries(entries())
        if (outdated.isEmpty()) return
        val newState = PackageListLogic.nextToggleState(outdated)
        outdated.forEach { it.checked = newState }
        list.repaint()
        onSelectionChanged()
    }

    /** Check/uncheck every outdated row under one section header. */
    private fun toggleSection(header: HeaderEntry) {
        val outdated = header.packages.filter { it.dep.outdated }
        if (outdated.isEmpty()) return
        val newState = PackageListLogic.nextToggleState(outdated)
        outdated.forEach { it.checked = newState }
        list.repaint()
        onSelectionChanged()
    }

    /** Checked outdated packages grouped by the project path to upgrade against. */
    fun checkedByTarget(): Map<String, List<String>> = PackageListLogic.checkedByTarget(entries())

    private fun toggleCheckedForSelection() {
        val targets = ArrayList<PackageEntry>()
        for (idx in list.selectedIndices) {
            val entry = model.getElementAt(idx) as? PackageEntry ?: continue
            if (entry.dep.outdated) targets.add(entry)
        }
        if (targets.isEmpty()) return
        val newState = PackageListLogic.nextToggleState(targets)
        targets.forEach { it.checked = newState }
        list.repaint()
        onSelectionChanged()
    }

    private fun entries(): List<ListEntry> = (0 until model.size()).map { model.getElementAt(it) }

    /** Headers aren't actionable; keep them out of the selection. */
    private fun dropHeaderSelections() {
        val headers = list.selectedIndices.filter { model.getElementAt(it) is HeaderEntry }
        headers.forEach { list.selectionModel.removeSelectionInterval(it, it) }
    }

    private class EntryRenderer : ListCellRenderer<ListEntry> {
        private val headerPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 8, 2, 8) }
        private val header = SimpleColoredComponent().apply { isOpaque = false }
        private val headerCheck = ThreeStateCheckBox().apply {
            isOpaque = false // display-only; the JList mouse listener drives toggling, not the component
            border = JBUI.Borders.emptyRight(6)
        }
        private val headerWest = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }

        private val rowPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(3, 8, 3, 10) }
        private val checkBox = JCheckBox().apply { isOpaque = false; border = JBUI.Borders.emptyRight(6) }
        private val westPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        private val left = SimpleColoredComponent().apply { isOpaque = false }
        private val right = SimpleColoredComponent().apply { isOpaque = false }

        init {
            westPanel.add(checkBox)
            westPanel.add(left)
            headerWest.add(headerCheck)
            headerWest.add(header)
            headerPanel.add(headerWest, BorderLayout.WEST)
            rowPanel.add(westPanel, BorderLayout.WEST)
            rowPanel.add(right, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out ListEntry>,
            entry: ListEntry,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ): Component = when (entry) {
            is HeaderEntry -> {
                headerPanel.isOpaque = true
                headerPanel.background = list.background // headers ignore selection highlight
                headerCheck.background = list.background
                // The section checkbox mirrors its packages: all / partial / none. Kept visible but
                // greyed when nothing is checkable, so headers stay aligned (matches the row checkboxes).
                headerCheck.isEnabled = PackageListLogic.sectionHasOutdated(entry)
                headerCheck.state = when (PackageListLogic.sectionCheckState(entry)) {
                    CheckState.ALL -> ThreeStateCheckBox.State.SELECTED
                    CheckState.SOME -> ThreeStateCheckBox.State.DONT_CARE
                    CheckState.NONE -> ThreeStateCheckBox.State.NOT_SELECTED
                }
                header.clear()
                header.append(entry.title, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                // Highlight the speed-search match inside the header title, like Rider's own lists.
                SpeedSearchUtil.applySpeedSearchHighlighting(list, header, false, false)
                headerPanel
            }

            is PackageEntry -> {
                val bg = if (selected) list.selectionBackground else list.background
                rowPanel.isOpaque = true
                rowPanel.background = bg
                checkBox.background = bg
                checkBox.isEnabled = entry.dep.outdated // only outdated packages are checkable
                checkBox.isSelected = entry.checked && entry.dep.outdated

                left.clear()
                right.clear()
                val nameAttr = if (selected) {
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, list.selectionForeground)
                } else {
                    SimpleTextAttributes.REGULAR_ATTRIBUTES
                }
                left.append(entry.dep.name, nameAttr)
                left.append("  ·  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                left.append(entry.dep.current, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                // Highlight the speed-search match inside the package name (main text only), so the
                // matched characters stand out as you type — same as Rider's native list search.
                SpeedSearchUtil.applySpeedSearchHighlighting(list, left, true, selected)

                if (entry.dep.newVersion.isNotEmpty()) {
                    val attr = when {
                        entry.dep.outdated && entry.dep.color != SeverityColor.NONE ->
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, entry.dep.color.toJBColor())
                        selected -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, list.selectionForeground)
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    }
                    right.append(entry.dep.newVersion, attr)
                }
                rowPanel
            }
        }
    }

    private companion object {
        const val CHECKBOX_HIT_WIDTH = 32 // left px region that toggles the checkbox
    }
}
