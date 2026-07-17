package com.github.arash.dotnetoutdated.ui

import com.github.arash.dotnetoutdated.model.SeverityColor
import com.github.arash.dotnetoutdated.model.changedVersionSuffix
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import com.intellij.util.ui.ColumnInfo
import java.awt.Color
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode

/** Maps a semantic severity bucket to a theme-aware color. */
fun SeverityColor.toJBColor(): Color = when (this) {
    SeverityColor.RED -> JBColor(0xD64B4B, 0xE06C6C)
    SeverityColor.YELLOW -> JBColor(0xB8860B, 0xD9A441)
    SeverityColor.GREEN -> JBColor(0x3C9A3C, 0x549E54)
    SeverityColor.NONE -> JBColor.foreground()
}

/**
 * A [TreeTable] showing projects → outdated packages, with a checkbox column and a
 * severity-colored "Latest" column. [onSelectionChanged] fires whenever check state changes.
 */
class OutdatedTreeTable(private val onSelectionChanged: () -> Unit) {

    private val root = DefaultMutableTreeNode("root")
    private val model = ListTreeTableModelOnColumns(root, buildColumns())
    val component: TreeTable = TreeTable(model).apply {
        setRootVisible(false)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = NodeRenderer()
        columnModel.getColumn(CHECK_COL).apply {
            maxWidth = 40
            minWidth = 40
        }
    }

    fun setData(projects: List<ProjectRow>) {
        root.removeAllChildren()
        for (project in projects) {
            val projectNode = DefaultMutableTreeNode(project)
            for (dep in project.deps) projectNode.add(DefaultMutableTreeNode(dep))
            root.add(projectNode)
        }
        model.reload()
        expandAll()
        onSelectionChanged()
    }

    fun anyChecked(): Boolean = eachDep().any { it.checked }

    /** Checked package names grouped by the path they should be upgraded against. */
    fun checkedByTarget(): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        for (i in 0 until root.childCount) {
            val projectNode = root.getChildAt(i) as DefaultMutableTreeNode
            val project = projectNode.userObject as ProjectRow
            val checked = project.deps.filter { it.checked }.map { it.name }.distinct()
            if (checked.isNotEmpty()) {
                result.getOrPut(project.upgradeTarget) { mutableListOf() }.addAll(checked)
            }
        }
        return result.mapValues { (_, names) -> names.distinct() }
    }

    private fun expandAll() {
        var i = 0
        while (i < component.tree.rowCount) {
            component.tree.expandRow(i)
            i++
        }
    }

    private fun eachDep(): Sequence<DepRow> = sequence {
        for (i in 0 until root.childCount) {
            val projectNode = root.getChildAt(i) as DefaultMutableTreeNode
            (projectNode.userObject as ProjectRow).deps.forEach { yield(it) }
        }
    }

    private fun buildColumns(): Array<ColumnInfo<*, *>> = arrayOf(
        PackageColumn(),
        VersionColumn("Current") { it.current },
        NewVersionColumn(),
        CheckColumn(onSelectionChanged) { component.repaint() },
    )

    private class PackageColumn : ColumnInfo<DefaultMutableTreeNode, Any>("Package") {
        override fun valueOf(item: DefaultMutableTreeNode): Any = item.userObject
        override fun getColumnClass(): Class<*> = TreeTableModel::class.java
    }

    private class VersionColumn(name: String, private val get: (DepRow) -> String) :
        ColumnInfo<DefaultMutableTreeNode, String>(name) {
        override fun valueOf(item: DefaultMutableTreeNode): String =
            (item.userObject as? DepRow)?.let(get) ?: ""
    }

    private class NewVersionColumn : ColumnInfo<DefaultMutableTreeNode, String>("New Version") {
        private val renderer = SmartVersionRenderer()
        override fun valueOf(item: DefaultMutableTreeNode): String =
            (item.userObject as? DepRow)?.newVersion ?: ""

        override fun getRenderer(item: DefaultMutableTreeNode?): TableCellRenderer = renderer
    }

    private class CheckColumn(
        private val onToggle: () -> Unit,
        private val repaint: () -> Unit,
    ) : ColumnInfo<DefaultMutableTreeNode, Boolean>("") {
        override fun valueOf(item: DefaultMutableTreeNode): Boolean = when (val o = item.userObject) {
            is DepRow -> o.checked
            is ProjectRow -> o.deps.any { it.outdated } && o.deps.filter { it.outdated }.all { it.checked }
            else -> false
        }

        override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java

        // Only outdated packages can be checked (there is nothing to upgrade otherwise).
        override fun isCellEditable(item: DefaultMutableTreeNode): Boolean = when (val o = item.userObject) {
            is DepRow -> o.outdated
            is ProjectRow -> o.deps.any { it.outdated }
            else -> false
        }

        override fun setValue(item: DefaultMutableTreeNode, value: Boolean) {
            when (val o = item.userObject) {
                is DepRow -> if (o.outdated) o.checked = value
                is ProjectRow -> o.deps.filter { it.outdated }.forEach { it.checked = value }
            }
            repaint()
            onToggle()
        }
    }

    /**
     * Renders the New Version cell like the CLI: only the part that changed from the current
     * version is colored, in the severity color (e.g. `4.2.1 -> 4.`**`3.0`** yellow). Uses a
     * SimpleColoredComponent (multi-segment) — reliable inside a TreeTable, unlike HTML.
     */
    private class SmartVersionRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ) {
            val dep = depAt(table, row)
            val latest = dep?.newVersion.orEmpty()
            if (dep == null || latest.isEmpty() || !dep.outdated || dep.color == SeverityColor.NONE) {
                if (latest.isNotEmpty()) append(latest)
                return
            }
            val (prefix, changed) = changedVersionSuffix(dep.current, latest)
            if (changed.isEmpty()) {
                append(latest)
                return
            }
            if (prefix.isNotEmpty()) append(prefix)
            append(changed, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, dep.color.toJBColor()))
        }

        private fun depAt(table: JTable, row: Int): DepRow? {
            val tree = (table as? TreeTable)?.tree ?: return null
            val node = tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode
            return node?.userObject as? DepRow
        }
    }

    /** Bold project rows with an outdated-count suffix; plain package rows. */
    private class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
                is ProjectRow -> {
                    append(o.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    val outdated = o.deps.count { it.outdated }
                    append("  ${o.deps.size} packages", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (outdated > 0) append(" · $outdated outdated", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is DepRow -> append(o.name) // name stays default; only the version is colored
                else -> append(o?.toString() ?: "")
            }
        }
    }

    companion object {
        private const val CHECK_COL = 3
    }
}
