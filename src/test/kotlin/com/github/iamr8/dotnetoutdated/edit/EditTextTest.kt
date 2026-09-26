package com.github.iamr8.dotnetoutdated.edit

import com.github.iamr8.dotnetoutdated.engine.Edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditTextTest {

    private fun apply(text: String, vararg edits: Edit): String {
        val r = EditText.plan(text, edits.toList())
        if (r is EditResult.Failed) throw AssertionError(r.reason)
        return EditText.applyAll(text, (r as EditResult.Ok).splices)
    }

    private fun attr(expected: String, value: String, line: Int, column: Int, type: String = "PackageReference", id: String = "Polly",
                     identityAttr: String = "Include", condition: String? = null, groupCondition: String? = null) =
        Edit("set", "/f", "attribute", type, identityAttr, id, condition, groupCondition, "Version", expected, value, line, column)

    @Test
    fun attributeRangeKeepsEverythingElse() {
        val text = """
            <Project Sdk="Microsoft.NET.Sdk">
              <ItemGroup>
                <PackageReference Include="Polly" Version="[7.0.0,8.0.0)" PrivateAssets="all" />
              </ItemGroup>
            </Project>
        """.trimIndent()
        val out = apply(text, attr("[7.0.0,8.0.0)", "[7.2.4,8.0.0)", 3, 5))
        assertEquals(text.replace("[7.0.0,8.0.0)", "[7.2.4,8.0.0)"), out)
    }

    @Test
    fun updateItemInImportedFile() {
        val text = "<Project>\n  <PropertyGroup><PollyVer>7.2.4</PollyVer></PropertyGroup>\n  <ItemGroup><PackageReference Update=\"Serilog\" Version=\"3.0.0\" /></ItemGroup>\n</Project>"
        val out = apply(text, attr("3.0.0", "3.1.1", 3, 14, id = "Serilog", identityAttr = "Update"))
        assertTrue(out.contains("<PackageReference Update=\"Serilog\" Version=\"3.1.1\" />"))
    }

    @Test
    fun childElementValue() {
        val text = "<Project>\n  <ItemGroup>\n    <PackageReference Include=\"Polly\">\n      <Version> 7.0.0 </Version>\n    </PackageReference>\n  </ItemGroup>\n</Project>"
        val edit = Edit("set", "/f", "child", "PackageReference", "Include", "Polly", null, null, "Version", "7.0.0", "7.2.4", 3, 5)
        assertEquals(text.replace(" 7.0.0 ", " 7.2.4 "), apply(text, edit))
    }

    @Test
    fun propertyValue() {
        val text = "<Project>\n  <PropertyGroup><PollyVer>7.2.4</PollyVer></PropertyGroup>\n</Project>"
        val edit = Edit("set", "/f", "property", null, null, null, null, null, "PollyVer", "7.2.4", "8.0.0", 2, 18)
        assertEquals(text.replace(">7.2.4<", ">8.0.0<"), apply(text, edit))
    }

    @Test
    fun conditionSelectsTheRightElement() {
        val text = """
            <Project>
              <ItemGroup Condition="'${'$'}(TargetFramework)' == 'net6.0'">
                <PackageReference Include="Dapper" Version="2.0.4" />
              </ItemGroup>
              <ItemGroup Condition="'${'$'}(TargetFramework)' == 'net8.0'">
                <PackageReference Include="Dapper" Version="2.0.4" />
              </ItemGroup>
            </Project>
        """.trimIndent()
        val out = apply(text, attr("2.0.4", "2.1.35", 6, 5, id = "Dapper", groupCondition = "'\$(TargetFramework)' == 'net8.0'"))
        val lines = out.lines()
        assertTrue(lines[2].contains("2.0.4"))
        assertTrue(lines[5].contains("2.1.35"))
    }

    @Test
    fun commentedOutElementIsIgnored() {
        val text = """
            <Project>
              <ItemGroup>
                <!-- <PackageReference Include="Polly" Version="7.0.0" /> -->
                <PackageReference Include="Polly" Version="7.0.0" />
              </ItemGroup>
            </Project>
        """.trimIndent()
        val out = apply(text, attr("7.0.0", "7.2.4", 3, 5)) // stale line hint points at the comment
        val lines = out.lines()
        assertTrue(lines[2].contains("7.0.0"))
        assertTrue(lines[3].contains("7.2.4"))
    }

    @Test
    fun equalMatchesAreDecidedByLine() {
        val text = "<Project>\n  <ItemGroup>\n    <PackageReference Include=\"A\" Version=\"1.0.0\" />\n  </ItemGroup>\n  <ItemGroup>\n    <PackageReference Include=\"A\" Version=\"1.0.0\" />\n  </ItemGroup>\n</Project>"
        val out = apply(text, attr("1.0.0", "1.1.0", 6, 5, id = "A"))
        assertTrue(out.lines()[2].contains("1.0.0"))
        assertTrue(out.lines()[5].contains("1.1.0"))
    }

    @Test
    fun expectedMismatchFails() {
        val text = "<Project><ItemGroup><PackageReference Include=\"Polly\" Version=\"7.1.0\" /></ItemGroup></Project>"
        val r = EditText.plan(text, listOf(attr("7.0.0", "7.2.4", 1, 21)))
        assertEquals(EditResult.Failed("file changed - re-scan"), r)
    }

    @Test
    fun directiveVersion() {
        val text = "#:package Humanizer.Core@2.14.1\n#:package Serilog@3.0.0\nConsole.WriteLine();\n"
        val edit = Edit("set", "/app.cs", "directive", null, null, null, null, null, "Serilog", "3.0.0", "4.4.0", 2, 1)
        assertEquals(text.replace("Serilog@3.0.0", "Serilog@4.4.0"), apply(text, edit))
    }

    @Test
    fun insertIntoExistingUnconditionedGroup() {
        val text = "<Project>\n  <ItemGroup>\n    <PackageVersion Include=\"Top\" Version=\"1.0.0\" />\n  </ItemGroup>\n</Project>"
        val edit = Edit("insert", "/f", "item", "PackageVersion", "Include", "Leaf", null, null, "Version", null, "2.1.0", 0, 0)
        assertEquals(
            "<Project>\n  <ItemGroup>\n    <PackageVersion Include=\"Leaf\" Version=\"2.1.0\" />\n    <PackageVersion Include=\"Top\" Version=\"1.0.0\" />\n  </ItemGroup>\n</Project>",
            apply(text, edit),
        )
    }

    @Test
    fun insertNewGroupWhenNoneFits() {
        val text = "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <ItemGroup Condition=\"'a' == 'a'\">\n    <PackageReference Include=\"X\" Version=\"1.0.0\" />\n  </ItemGroup>\n</Project>\n"
        val edit = Edit("insert", "/f", "item", "PackageReference", "Include", "Leaf", null, null, "", null, "", 0, 0)
        assertEquals(
            text.replace("</Project>", "  <ItemGroup>\n    <PackageReference Include=\"Leaf\" />\n  </ItemGroup>\n</Project>"),
            apply(text, edit),
        )
    }

    @Test
    fun insertOfAnExistingReferenceIsANoOp() {
        val text = "<Project>\n  <ItemGroup>\n    <PackageReference Include=\"Leaf\" />\n  </ItemGroup>\n</Project>"
        val edit = Edit("insert", "/f", "item", "PackageReference", "Include", "Leaf", null, null, "", null, "", 0, 0)
        assertEquals(text, apply(text, edit, edit))
    }

    @Test
    fun twoEditsInOneFile() {
        val text = "<Project>\n  <ItemGroup>\n    <PackageVersion Include=\"A\" Version=\"1.0.0\" />\n    <PackageVersion Include=\"B\" Version=\"[2.0.0,3.0.0)\" />\n  </ItemGroup>\n</Project>"
        val out = apply(
            text,
            attr("1.0.0", "1.1.0", 3, 5, type = "PackageVersion", id = "A"),
            attr("[2.0.0,3.0.0)", "[2.5.0,3.0.0)", 4, 5, type = "PackageVersion", id = "B"),
        )
        assertTrue(out.contains("Include=\"A\" Version=\"1.1.0\""))
        assertTrue(out.contains("Include=\"B\" Version=\"[2.5.0,3.0.0)\""))
    }

    // --- Controller rulings: decoded comparisons, numeric entities, quote-style preservation ---

    @Test
    fun conditionWithGtEntityMatches() {
        // The helper hands us the DECODED condition text ('$(V)' > '1'); the file on disk still has the
        // XML-escaped &gt; form. Comparison must decode the file's Condition before matching.
        val text = """
            <Project>
              <ItemGroup Condition="'${'$'}(V)' &gt; '1'">
                <PackageReference Include="Dapper" Version="2.0.4" />
              </ItemGroup>
            </Project>
        """.trimIndent()
        val out = apply(text, attr("2.0.4", "2.1.35", 3, 5, id = "Dapper", groupCondition = "'\$(V)' > '1'"))
        assertTrue(out.contains("Version=\"2.1.35\""))
    }

    @Test
    fun identityWithAmpEntityMatches() {
        // Include="A&amp;B" on disk decodes to identity "A&B", the form the helper sends.
        val text = "<Project>\n  <ItemGroup>\n    <PackageReference Include=\"A&amp;B\" Version=\"1.0.0\" />\n  </ItemGroup>\n</Project>"
        val edit = attr("1.0.0", "1.1.0", 3, 5, id = "A&B")
        val out = apply(text, edit)
        assertTrue(out.contains("Include=\"A&amp;B\" Version=\"1.1.0\""))
    }

    @Test
    fun numericEntityInIdentityMatches() {
        // Include="A&#38;B" on disk decodes to identity "A&B" (decimal numeric character reference).
        val text = "<Project>\n  <ItemGroup>\n    <PackageReference Include=\"A&#38;B\" Version=\"1.0.0\" />\n  </ItemGroup>\n</Project>"
        val out = apply(text, attr("1.0.0", "1.1.0", 3, 5, id = "A&B"))
        assertTrue(out.contains("Include=\"A&#38;B\" Version=\"1.1.0\""))
    }

    @Test
    fun setEncodesTheNewValueOnWrite() {
        val text = "<Project>\n  <ItemGroup>\n    <PackageReference Include=\"Polly\" Version=\"7.0.0\" />\n  </ItemGroup>\n</Project>"
        val out = apply(text, attr("7.0.0", "7.2.4&beta", 3, 5))
        assertTrue(out.contains("Version=\"7.2.4&amp;beta\""))
    }

    @Test
    fun singleQuotedAttributeKeepsSingleQuotes() {
        val text = "<Project>\n  <ItemGroup>\n    <PackageReference Include='Polly' Version='7.0.0' />\n  </ItemGroup>\n</Project>"
        val out = apply(text, attr("7.0.0", "7.2.4", 3, 5))
        assertTrue(out.contains("Version='7.2.4'"))
        assertTrue(out.contains("Include='Polly'"))
    }

    // --- Fix round 1: CRLF/whitespace normalization, PropertyGroup-only property matches,
    // --- real element nesting (Target/Choose/When), case-insensitive metadata names, merged
    // --- new-group inserts. ---

    @Test
    fun crlfOriginExpectedMatchesLfDocumentOnMultilineChild() {
        // The document (IDE text) is LF-only; `expected` carries the CRLF the helper read from disk.
        val text = "<Project>\n  <ItemGroup>\n    <PackageReference Include=\"Polly\">\n      <Version>\n        7.0.0\n      </Version>\n    </PackageReference>\n  </ItemGroup>\n</Project>"
        val edit = Edit("set", "/f", "child", "PackageReference", "Include", "Polly", null, null, "Version", "\r\n        7.0.0\r\n      ", "7.2.4", 4, 7)
        val out = apply(text, edit)
        assertTrue(out.contains("<Version>\n        7.2.4\n      </Version>"))
    }

    @Test
    fun propertyWithSurroundingSpacesMatchesTrimmedAndKeepsThem() {
        val text = "<Project>\n  <PropertyGroup><PollyVer> 7.2.4 </PollyVer></PropertyGroup>\n</Project>"
        val edit = Edit("set", "/f", "property", null, null, null, null, null, "PollyVer", " 7.2.4 ", "8.0.0", 2, 18)
        val out = apply(text, edit)
        assertEquals("<Project>\n  <PropertyGroup><PollyVer> 8.0.0 </PollyVer></PropertyGroup>\n</Project>", out)
    }

    @Test
    fun multilineGroupConditionMatches() {
        val text = "<Project>\n  <ItemGroup Condition=\"'\$(TargetFramework)'\n                        == 'net8.0'\">\n    <PackageReference Include=\"Dapper\" Version=\"2.0.4\" />\n  </ItemGroup>\n</Project>"
        val out = apply(text, attr("2.0.4", "2.1.35", 4, 5, id = "Dapper", groupCondition = "'\$(TargetFramework)' == 'net8.0'"))
        assertTrue(out.contains("Version=\"2.1.35\""))
    }

    @Test
    fun propertyEditNeverMatchesAnItemChildWithTheSameName() {
        // Both Version elements read "7.0.0", so only the PropertyGroup-parent check tells them
        // apart - a stale line hint (pointing at the PackageReference's Version) must not matter.
        val text = "<Project>\n  <ItemGroup>\n    <PackageReference Include=\"Foo\">\n      <Version>7.0.0</Version>\n    </PackageReference>\n  </ItemGroup>\n  <PropertyGroup>\n    <Version>7.0.0</Version>\n  </PropertyGroup>\n</Project>"
        val edit = Edit("set", "/f", "property", null, null, null, null, null, "Version", "7.0.0", "8.0.0", 4, 7)
        val out = apply(text, edit)
        val lines = out.lines()
        assertTrue(lines[3].contains("7.0.0")) // PackageReference/Version untouched
        assertTrue(lines[7].contains("8.0.0")) // PropertyGroup/Version changed
    }

    @Test
    fun insertGoesToTheTopLevelGroupNotATargetsGroup() {
        val text = "<Project>\n  <Target Name=\"Before\">\n    <ItemGroup>\n      <PackageReference Include=\"Old\" />\n    </ItemGroup>\n  </Target>\n  <ItemGroup>\n    <PackageReference Include=\"Top\" Version=\"1.0.0\" />\n  </ItemGroup>\n</Project>"
        val edit = Edit("insert", "/f", "item", "PackageReference", "Include", "Leaf", null, null, "Version", null, "2.1.0", 0, 0)
        val out = apply(text, edit)
        assertEquals(
            "<Project>\n  <Target Name=\"Before\">\n    <ItemGroup>\n      <PackageReference Include=\"Old\" />\n    </ItemGroup>\n  </Target>\n  <ItemGroup>\n    <PackageReference Include=\"Leaf\" Version=\"2.1.0\" />\n    <PackageReference Include=\"Top\" Version=\"1.0.0\" />\n  </ItemGroup>\n</Project>",
            out,
        )
    }

    @Test
    fun sameIdInsideATargetIsNotANoOp() {
        val text = "<Project>\n  <Target Name=\"T\">\n    <ItemGroup>\n      <PackageReference Include=\"Leaf\" />\n    </ItemGroup>\n  </Target>\n</Project>\n"
        val edit = Edit("insert", "/f", "item", "PackageReference", "Include", "Leaf", null, null, "", null, "", 0, 0)
        val out = apply(text, edit)
        assertEquals(
            text.replace("</Project>", "  <ItemGroup>\n    <PackageReference Include=\"Leaf\" />\n  </ItemGroup>\n</Project>"),
            out,
        )
    }

    @Test
    fun tagAfterClosedGroupDoesNotInheritItsCondition() {
        val text = "<Project>\n  <Target Name=\"T\">\n    <ItemGroup Condition=\"'cond'=='true'\">\n      <PackageReference Include=\"Dapper\" Version=\"9.9.9\" />\n    </ItemGroup>\n  </Target>\n  <ItemGroup>\n    <PackageReference Include=\"Dapper\" Version=\"2.0.4\" />\n  </ItemGroup>\n</Project>"
        val out = apply(text, attr("2.0.4", "2.1.35", 8, 5, id = "Dapper"))
        assertTrue(out.contains("Version=\"9.9.9\""))
        assertTrue(out.contains("Version=\"2.1.35\""))
    }

    @Test
    fun metadataNameIsCaseInsensitiveForAttribute() {
        val text = "<Project>\n  <ItemGroup>\n    <PackageReference Include=\"Polly\" version=\"3.0.0\" />\n  </ItemGroup>\n</Project>"
        val out = apply(text, attr("3.0.0", "3.1.1", 3, 5))
        assertTrue(out.contains("version=\"3.1.1\""))
    }

    @Test
    fun metadataNameIsCaseInsensitiveForChild() {
        val text = "<Project>\n  <ItemGroup>\n    <PackageReference Include=\"Polly\">\n      <version>7.0.0</version>\n    </PackageReference>\n  </ItemGroup>\n</Project>"
        val edit = Edit("set", "/f", "child", "PackageReference", "Include", "Polly", null, null, "Version", "7.0.0", "7.2.4", 3, 5)
        val out = apply(text, edit)
        assertTrue(out.contains("<version>7.2.4</version>"))
    }

    @Test
    fun twoNewInsertsGoIntoOneNewGroup() {
        val text = "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <ItemGroup Condition=\"'a' == 'a'\">\n    <PackageReference Include=\"X\" Version=\"1.0.0\" />\n  </ItemGroup>\n</Project>\n"
        val e1 = Edit("insert", "/f", "item", "PackageReference", "Include", "Leaf1", null, null, "", null, "", 0, 0)
        val e2 = Edit("insert", "/f", "item", "PackageReference", "Include", "Leaf2", null, null, "", null, "", 0, 0)
        val out = apply(text, e1, e2)
        assertEquals(
            text.replace(
                "</Project>",
                "  <ItemGroup>\n    <PackageReference Include=\"Leaf1\" />\n    <PackageReference Include=\"Leaf2\" />\n  </ItemGroup>\n</Project>",
            ),
            out,
        )
    }

    // --- Fix round 2 ---

    @Test
    fun whitespaceOnlyConditionMatchesUnconditioned() {
        val text = "<Project>\n  <ItemGroup Condition=\" \">\n    <PackageReference Include=\"Dapper\" Version=\"2.0.4\" />\n  </ItemGroup>\n</Project>"
        val out = apply(text, attr("2.0.4", "2.1.35", 3, 5, id = "Dapper", groupCondition = " "))
        assertTrue(out.contains("Version=\"2.1.35\""))
    }

    @Test
    fun conditionCollapsesWhitespaceOnlyOutsideQuotedLiterals() {
        val text = """
            <Project>
              <ItemGroup Condition="'${'$'}(V)' == 'a  b'">
                <PackageReference Include="Dapper" Version="2.0.4" />
              </ItemGroup>
              <ItemGroup Condition="'${'$'}(V)' == 'a b'">
                <PackageReference Include="Dapper" Version="2.0.4" />
              </ItemGroup>
            </Project>
        """.trimIndent()
        // stale hint points at the FIRST (wrong, double-space-inside-quotes) group's Dapper
        val out = apply(text, attr("2.0.4", "2.1.35", 3, 5, id = "Dapper", groupCondition = "'\$(V)' == 'a b'"))
        val lines = out.lines()
        assertTrue(lines[2].contains("2.0.4")) // 'a  b' group untouched
        assertTrue(lines[5].contains("2.1.35")) // 'a b' group changed
    }

    @Test
    fun propertyMustBeInATopLevelPropertyGroupNotATargets() {
        val text = "<Project>\n  <Target Name=\"T\">\n    <PropertyGroup>\n      <PollyVer>7.2.4</PollyVer>\n    </PropertyGroup>\n  </Target>\n  <PropertyGroup>\n    <PollyVer>7.2.4</PollyVer>\n  </PropertyGroup>\n</Project>"
        // stale hint points at the Target's PollyVer (not static - must not be edited)
        val edit = Edit("set", "/f", "property", null, null, null, null, null, "PollyVer", "7.2.4", "8.0.0", 4, 7)
        val out = apply(text, edit)
        val lines = out.lines()
        assertTrue(lines[3].contains("7.2.4")) // Target's PropertyGroup untouched
        assertTrue(lines[7].contains("8.0.0")) // top-level PropertyGroup changed
    }
}
