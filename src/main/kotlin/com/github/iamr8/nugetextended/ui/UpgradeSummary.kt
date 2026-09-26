package com.github.iamr8.nugetextended.ui

import com.github.iamr8.nugetextended.engine.UpgradePlan
import java.io.File

/** Pure: the confirm dialog text for an upgrade plan. */
object UpgradeSummary {
    private const val MAX_LINES = 10

    fun text(plan: UpgradePlan, restoreOnly: Int): String = buildString {
        val (inserts, sets) = plan.edits.partition { it.op == "insert" }
        // Under CPM one new reference is two inserts (central PackageVersion + PackageReference).
        val added = inserts.mapNotNull { it.identity }.distinctBy { it.lowercase() }
        val clauses = buildList {
            if (sets.isNotEmpty()) add("change ${sets.size} version(s) in ${sets.map { it.file }.distinct().size} file(s)")
            if (added.isNotEmpty()) add("add reference(s) to ${added.joinToString(", ")}")
            if (restoreOnly > 0) add("restore $restoreOnly floating package(s) to their newest match")
        }
        if (clauses.isNotEmpty()) {
            val head = if (clauses.size == 1) clauses[0] else clauses.dropLast(1).joinToString(",\n") + ",\nand " + clauses.last()
            append(head.replaceFirstChar { it.uppercase() }).append("?")
        }
        section("These also change, because they share a version:", plan.alsoChanges.map { "${it.id} in ${name(it.project)}" })
        section("Skipped:", plan.skipped.map { "${it.id} in ${name(it.project)}: ${it.reason}" })
        append("\n\nUndo reverts the files.")
    }

    private fun StringBuilder.section(title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        append("\n\n").append(title)
        lines.take(MAX_LINES).forEach { append("\n  - ").append(it) }
        if (lines.size > MAX_LINES) append("\n  - ...and ${lines.size - MAX_LINES} more")
    }

    private fun name(project: String) = File(project).nameWithoutExtension
}
