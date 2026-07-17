package com.github.arash.dotnetoutdated.ui

import com.github.arash.dotnetoutdated.model.SeverityColor
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
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
private class PackageEntry(val dep: DepRow, val target: String) : ListEntry()

/**
 * Grouped package list mirroring the `dotnet outdated` CLI / Rider NuGet view: a
 * `ProjectName · framework` header per project+TFM section, then one row per package —
 * `Name · Current` on the left, new version (colored by severity) right-aligned.
 * Multi-select the package rows to drive "Update Selected".
 */
class PackageListView(onSelectionChanged: () -> Unit) {

    private val model = DefaultListModel<ListEntry>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        cellRenderer = EntryRenderer()
        addListSelectionListener {
            dropHeaderSelections()
            onSelectionChanged()
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

    fun hasSelectedOutdated(): Boolean =
        list.selectedValuesList.any { it is PackageEntry && it.dep.outdated }

    /** Selected outdated packages grouped by the project path to upgrade against. */
    fun selectedByTarget(): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        for (entry in list.selectedValuesList) {
            if (entry is PackageEntry && entry.dep.outdated) {
                result.getOrPut(entry.target) { mutableListOf() }.add(entry.dep.name)
            }
        }
        return result.mapValues { it.value.distinct() }
    }

    /** Headers aren't actionable; keep them out of the selection. */
    private fun dropHeaderSelections() {
        val headers = list.selectedIndices.filter { model.getElementAt(it) is HeaderEntry }
        headers.forEach { list.selectionModel.removeSelectionInterval(it, it) }
    }

    private class EntryRenderer : ListCellRenderer<ListEntry> {
        private val headerPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 8, 2, 8) }
        private val header = SimpleColoredComponent().apply { isOpaque = false }
        private val rowPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(3, 18, 3, 10) }
        private val left = SimpleColoredComponent().apply { isOpaque = false }
        private val right = SimpleColoredComponent().apply { isOpaque = false }

        init {
            headerPanel.add(header, BorderLayout.WEST)
            rowPanel.add(left, BorderLayout.WEST)
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
                header.append(entry.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                headerPanel
            }

            is PackageEntry -> {
                rowPanel.isOpaque = true
                rowPanel.background = if (selected) list.selectionBackground else list.background
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
}
