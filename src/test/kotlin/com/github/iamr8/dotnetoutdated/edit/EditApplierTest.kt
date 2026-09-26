package com.github.iamr8.dotnetoutdated.edit

import com.github.iamr8.dotnetoutdated.engine.Edit
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditApplierTest : BasePlatformTestCase() {

    private fun edit(file: VirtualFile, expected: String, value: String) =
        Edit("set", file.path, "attribute", "PackageVersion", "Include", "Polly", null, null, "Version", expected, value, 3, 5)

    private fun props(version: String) =
        "<Project>\n  <ItemGroup>\n    <PackageVersion Include=\"Polly\" Version=\"$version\" />\n  </ItemGroup>\n</Project>"

    private fun twoProps(pollyVersion: String, serilogVersion: String) =
        "<Project>\n  <ItemGroup>\n    <PackageVersion Include=\"Polly\" Version=\"$pollyVersion\" />\n    <PackageVersion Include=\"Serilog\" Version=\"$serilogVersion\" />\n  </ItemGroup>\n</Project>"

    fun testAppliesEditsInSeveralFiles() {
        val a = myFixture.addFileToProject("a/Directory.Packages.props", props("7.0.0")).virtualFile
        val b = myFixture.addFileToProject("b/Directory.Packages.props", props("[7.0.0,8.0.0)")).virtualFile
        val files = mapOf(a.path to a, b.path to b)

        val result = EditApplier.apply(project, listOf(edit(a, "7.0.0", "7.2.4"), edit(b, "[7.0.0,8.0.0)", "[7.2.4,8.0.0)")), "Upgrade", files::get)

        assertEquals(EditApplier.Result.Applied, result)
        assertEquals(props("7.2.4"), FileDocumentManager.getInstance().getDocument(a)!!.text)
        assertEquals(props("[7.2.4,8.0.0)"), FileDocumentManager.getInstance().getDocument(b)!!.text)
    }

    fun testFailureInSecondFileChangesNothing() {
        val a = myFixture.addFileToProject("a/Directory.Packages.props", props("7.0.0")).virtualFile
        val b = myFixture.addFileToProject("b/Directory.Packages.props", props("7.1.0")).virtualFile
        val files = mapOf(a.path to a, b.path to b)

        val result = EditApplier.apply(project, listOf(edit(a, "7.0.0", "7.2.4"), edit(b, "7.0.0", "7.2.4")), "Upgrade", files::get)

        assertTrue(result is EditApplier.Result.Failed)
        assertEquals(props("7.0.0"), FileDocumentManager.getInstance().getDocument(a)!!.text)
        assertEquals(props("7.1.0"), FileDocumentManager.getInstance().getDocument(b)!!.text)
    }

    fun testTwoPathSpellingsOfOneFileCombineInsteadOfOverwriting() {
        val a = myFixture.addFileToProject("a/Directory.Packages.props", twoProps("7.0.0", "3.0.0")).virtualFile
        // Two different spellings of the same path, both resolving to the SAME VirtualFile.
        val spellings = mapOf("a/Directory.Packages.props" to a, "./a/Directory.Packages.props" to a)

        val editPolly = Edit("set", "a/Directory.Packages.props", "attribute", "PackageVersion", "Include", "Polly", null, null, "Version", "7.0.0", "7.2.4", 3, 5)
        val editSerilog = Edit("set", "./a/Directory.Packages.props", "attribute", "PackageVersion", "Include", "Serilog", null, null, "Version", "3.0.0", "3.1.1", 4, 5)

        val result = EditApplier.apply(project, listOf(editPolly, editSerilog), "Upgrade", spellings::get)

        assertEquals(EditApplier.Result.Applied, result)
        assertEquals(twoProps("7.2.4", "3.1.1"), FileDocumentManager.getInstance().getDocument(a)!!.text)
    }
}
