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

/** One consolidated package across the scanned projects. */
class PackageItem(val name: String, val current: String) {
    var newVersion: String = ""
    var color: SeverityColor = SeverityColor.NONE
    var outdated: Boolean = false
    val targets = linkedSetOf<String>() // project paths to upgrade against
}

/**
 * A flat, Rider-NuGet-style package list: each row shows `Name · Current` on the left and the
 * new version (colored by severity) right-aligned. Multi-select drives "Update Selected".
 */
class PackageListView(onSelectionChanged: () -> Unit) {

    private val model = DefaultListModel<PackageItem>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        cellRenderer = PackageCellRenderer()
        addListSelectionListener { onSelectionChanged() }
    }

    val component: JComponent get() = list

    /** Flattens project rows into consolidated package items (deduped by name + current version). */
    fun setData(rows: List<ProjectRow>) {
        val byKey = LinkedHashMap<String, PackageItem>()
        for (project in rows) {
            for (dep in project.deps) {
                val item = byKey.getOrPut("${dep.name}|${dep.current}") { PackageItem(dep.name, dep.current) }
                item.targets.add(project.upgradeTarget)
                if (dep.outdated) {
                    item.outdated = true
                    item.newVersion = dep.newVersion
                    item.color = dep.color
                } else if (item.newVersion.isEmpty()) {
                    item.newVersion = dep.newVersion
                }
            }
        }
        val items = byKey.values.sortedWith(
            compareByDescending<PackageItem> { it.outdated }.thenBy { it.name.lowercase() },
        )
        model.clear()
        items.forEach { model.addElement(it) }
    }

    fun hasSelectedOutdated(): Boolean = list.selectedValuesList.any { it.outdated }

    /** Checked (selected) outdated packages grouped by the project path to upgrade against. */
    fun selectedByTarget(): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        for (item in list.selectedValuesList) {
            if (!item.outdated) continue
            for (target in item.targets) result.getOrPut(target) { mutableListOf() }.add(item.name)
        }
        return result.mapValues { it.value.distinct() }
    }

    private class PackageCellRenderer : ListCellRenderer<PackageItem> {
        private val panel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(3, 10) }
        private val left = SimpleColoredComponent().apply { isOpaque = false }
        private val right = SimpleColoredComponent().apply { isOpaque = false }

        init {
            panel.add(left, BorderLayout.WEST)
            panel.add(right, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out PackageItem>,
            item: PackageItem,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ): Component {
            panel.isOpaque = true
            panel.background = if (selected) list.selectionBackground else list.background
            left.clear()
            right.clear()

            val nameAttr = if (selected) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, list.selectionForeground)
            } else {
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
            left.append(item.name, nameAttr)
            left.append("  ·  ", SimpleTextAttributes.GRAYED_ATTRIBUTES) // Rider-style middle dot
            left.append(item.current, SimpleTextAttributes.GRAYED_ATTRIBUTES)

            if (item.newVersion.isNotEmpty()) {
                val attr = when {
                    item.outdated && item.color != SeverityColor.NONE ->
                        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, item.color.toJBColor())
                    selected -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, list.selectionForeground)
                    else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                }
                right.append(item.newVersion, attr)
            }
            return panel
        }
    }
}
