package com.github.iamr8.dotnetoutdated.ui

import com.github.iamr8.dotnetoutdated.model.SeverityColor
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
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

private sealed class ListEntry
private class HeaderEntry(val title: String) : ListEntry()
private class PackageEntry(val dep: DepRow, val target: String) : ListEntry() {
    var checked: Boolean = false
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
                val entry = model.getElementAt(index) as? PackageEntry ?: return
                if (entry.dep.outdated && e.x <= CHECKBOX_HIT_WIDTH) {
                    entry.checked = !entry.checked
                    list.repaint()
                    onSelectionChanged()
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
        sections
            .sortedWith(compareBy({ it.projectName.lowercase() }, { it.framework }))
            .forEach { section ->
                model.addElement(HeaderEntry("${section.projectName}  ·  ${section.framework}"))
                section.deps
                    .sortedWith(compareByDescending<DepRow> { it.outdated }.thenBy { it.name.lowercase() })
                    .forEach { model.addElement(PackageEntry(it, section.upgradeTarget)) }
            }
    }

    fun hasChecked(): Boolean = eachPackage().any { it.checked && it.dep.outdated }

    /** Checked outdated packages grouped by the project path to upgrade against. */
    fun checkedByTarget(): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        for (entry in eachPackage()) {
            if (entry.checked && entry.dep.outdated) {
                result.getOrPut(entry.target) { mutableListOf() }.add(entry.dep.name)
            }
        }
        return result.mapValues { it.value.distinct() }
    }

    private fun toggleCheckedForSelection() {
        val targets = ArrayList<PackageEntry>()
        for (idx in list.selectedIndices) {
            val entry = model.getElementAt(idx) as? PackageEntry ?: continue
            if (entry.dep.outdated) targets.add(entry)
        }
        if (targets.isEmpty()) return
        val newState = !targets.all { it.checked } // if any unchecked, check all; else uncheck
        targets.forEach { it.checked = newState }
        list.repaint()
        onSelectionChanged()
    }

    private fun eachPackage(): Sequence<PackageEntry> = sequence {
        for (i in 0 until model.size()) (model.getElementAt(i) as? PackageEntry)?.let { yield(it) }
    }

    /** Headers aren't actionable; keep them out of the selection. */
    private fun dropHeaderSelections() {
        val headers = list.selectedIndices.filter { model.getElementAt(it) is HeaderEntry }
        headers.forEach { list.selectionModel.removeSelectionInterval(it, it) }
    }

    private class EntryRenderer : ListCellRenderer<ListEntry> {
        private val headerPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 8, 2, 8) }
        private val header = SimpleColoredComponent().apply { isOpaque = false }

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
            headerPanel.add(header, BorderLayout.WEST)
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
                header.clear()
                header.append(entry.title, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
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
