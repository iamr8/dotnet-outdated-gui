package com.github.iamr8.nugetextended.edit

import com.github.iamr8.nugetextended.engine.Edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditPlanTest {

    private fun edit(file: String, id: String, expected: String, value: String, line: Int) =
        Edit("set", file, "attribute", "PackageVersion", "Include", id, null, null, "Version", expected, value, line, 5)

    private fun props(vararg versions: Pair<String, String>) =
        "<Project>\n  <ItemGroup>\n" +
            versions.joinToString("") { (id, v) -> "    <PackageVersion Include=\"$id\" Version=\"$v\" />\n" } +
            "  </ItemGroup>\n</Project>"

    private fun <K : Any> plan(edits: List<Edit>, files: Map<String, K>, texts: Map<K, String>) =
        EditPlan.plan(edits, files::get, { it.toString() }) { key -> texts[key]?.let { FileText.Ok(it) } ?: FileText.Unreadable("$key is not a text file") }

    private fun <K> applied(result: Planned<K>, texts: Map<K, String>): Map<K, String> {
        if (result is Planned.Failed) throw AssertionError(result.reason)
        return (result as Planned.Ok).splices.mapValues { (key, splices) -> EditText.applyAll(texts.getValue(key), splices) }
    }

    @Test
    fun appliesEditsInSeveralFiles() {
        val texts = mapOf("a" to props("Polly" to "7.0.0"), "b" to props("Polly" to "[7.0.0,8.0.0)"))
        val result = plan(listOf(edit("/a", "Polly", "7.0.0", "7.2.4", 3), edit("/b", "Polly", "[7.0.0,8.0.0)", "[7.2.4,8.0.0)", 3)), mapOf("/a" to "a", "/b" to "b"), texts)

        assertEquals(mapOf("a" to props("Polly" to "7.2.4"), "b" to props("Polly" to "[7.2.4,8.0.0)")), applied(result, texts))
    }

    @Test
    fun failureInSecondFileGivesNoPlan() {
        val texts = mapOf("a" to props("Polly" to "7.0.0"), "b" to props("Polly" to "7.1.0"))
        val result = plan(listOf(edit("/a", "Polly", "7.0.0", "7.2.4", 3), edit("/b", "Polly", "7.0.0", "7.2.4", 3)), mapOf("/a" to "a", "/b" to "b"), texts)

        // No splices for any file: the applier writes nothing, not even the first file.
        assertTrue(result is Planned.Failed)
        assertTrue((result as Planned.Failed).reason.startsWith("b: "))
    }

    @Test
    fun twoPathSpellingsOfOneFileCombineInsteadOfOverwriting() {
        val texts = mapOf("a" to props("Polly" to "7.0.0", "Serilog" to "3.0.0"))
        val spellings = mapOf("a/Directory.Packages.props" to "a", "./a/Directory.Packages.props" to "a")
        val result = plan(
            listOf(edit("a/Directory.Packages.props", "Polly", "7.0.0", "7.2.4", 3), edit("./a/Directory.Packages.props", "Serilog", "3.0.0", "3.1.1", 4)),
            spellings, texts,
        )

        assertEquals(mapOf("a" to props("Polly" to "7.2.4", "Serilog" to "3.1.1")), applied(result, texts))
    }

    @Test
    fun missingFileFailsWithItsName() {
        val result = plan(listOf(edit("/x/Directory.Packages.props", "Polly", "7.0.0", "7.2.4", 3)), emptyMap<String, String>(), emptyMap())

        assertEquals(Planned.Failed("Directory.Packages.props was not found - re-scan"), result)
    }

    @Test
    fun unreadableFileGivesItsReason() {
        val result = plan(listOf(edit("/a", "Polly", "7.0.0", "7.2.4", 3)), mapOf("/a" to "a"), emptyMap())

        assertEquals(Planned.Failed("a is not a text file"), result)
    }
}
