package com.github.iamr8.dotnetoutdated.ui

import com.github.iamr8.dotnetoutdated.engine.Change
import com.github.iamr8.dotnetoutdated.engine.Edit
import com.github.iamr8.dotnetoutdated.engine.Skip
import com.github.iamr8.dotnetoutdated.engine.UpgradePlan
import org.junit.Assert.assertEquals
import org.junit.Test

class UpgradeSummaryTest {
    @Test
    fun listsEditsSharedChangesSkipsAndUndo() {
        val plan = UpgradePlan(
            edits = listOf(Edit(file = "/r/Directory.Packages.props"), Edit(file = "/r/Directory.Packages.props"), Edit(file = "/r/A/A.csproj")),
            alsoChanges = listOf(Change("/r/B/B.csproj", "Polly")),
            skipped = listOf(Skip("/r/C/C.csproj", "Serilog", "floating version - restore picks the newest match")),
        )
        assertEquals(
            """
            Change 3 version(s) in 2 file(s)?

            These also change, because they share a version:
              - Polly in B

            Skipped:
              - Serilog in C: floating version - restore picks the newest match

            Undo reverts the files.
            """.trimIndent(),
            UpgradeSummary.text(plan, restoreOnly = 0),
        )
    }

    @Test
    fun restoreOnlyWithoutEdits() {
        assertEquals(
            "Restore 2 floating package(s) to their newest match?\n\nUndo reverts the files.",
            UpgradeSummary.text(UpgradePlan(), restoreOnly = 2),
        )
    }

    @Test
    fun insertsAreNamedAsNewReferencesNotCountedAsVersions() {
        // Under CPM one new reference is two inserts: the central PackageVersion and the PackageReference.
        val plan = UpgradePlan(
            edits = listOf(
                Edit(op = "set", file = "/r/A/A.csproj"),
                Edit(op = "insert", file = "/r/Directory.Packages.props", itemType = "PackageVersion", identity = "Serilog"),
                Edit(op = "insert", file = "/r/A/A.csproj", itemType = "PackageReference", identity = "Serilog"),
            ),
        )
        assertEquals(
            "Change 1 version(s) in 1 file(s),\nand add reference(s) to Serilog?\n\nUndo reverts the files.",
            UpgradeSummary.text(plan, restoreOnly = 0),
        )
    }

    @Test
    fun onlyInsertsWithRestoreOnly() {
        val plan = UpgradePlan(edits = listOf(Edit(op = "insert", file = "/r/A/A.csproj", identity = "Polly")))
        assertEquals(
            "Add reference(s) to Polly,\nand restore 1 floating package(s) to their newest match?\n\nUndo reverts the files.",
            UpgradeSummary.text(plan, restoreOnly = 1),
        )
    }
}
