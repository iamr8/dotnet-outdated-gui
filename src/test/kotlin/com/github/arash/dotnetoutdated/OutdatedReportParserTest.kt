package com.github.arash.dotnetoutdated

import com.github.arash.dotnetoutdated.parse.OutdatedReportParser
import com.github.arash.dotnetoutdated.ui.OutdatedRows
import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutdatedReportParserTest {

    private val sample = """
        {
          "Projects": [
            {
              "Name": "Sahelanthropus.Data",
              "FilePath": "/repo/Sahelanthropus.Data/Sahelanthropus.Data.csproj",
              "TargetFrameworks": [
                {
                  "Name": "net10.0",
                  "Dependencies": [
                    { "Name": "ErrorProne.NET.CoreAnalyzers", "ResolvedVersion": "0.7.0-beta.1", "LatestVersion": "0.9.0-beta.4", "UpgradeSeverity": "Major" },
                    { "Name": "Meziantou.Analyzer", "ResolvedVersion": "3.0.121", "LatestVersion": "3.0.123", "UpgradeSeverity": "Patch" },
                    { "Name": "Microsoft.EntityFrameworkCore", "ResolvedVersion": "10.0.9", "LatestVersion": "10.0.10", "UpgradeSeverity": "Patch" }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesProjectsAndDependencies() {
        val report = OutdatedReportParser.parse(sample)
        assertEquals(1, report.projects.size)
        val project = report.projects[0]
        assertEquals("Sahelanthropus.Data", project.name)
        val deps = project.targetFrameworks.single().dependencies
        assertEquals(3, deps.size)
        assertEquals("0.9.0-beta.4", deps[0].latestVersion)
        assertEquals("Major", deps[0].upgradeSeverity)
    }

    @Test
    fun buildsRowsWithUpgradeTargetFromFilePath() {
        val report = OutdatedReportParser.parse(sample)
        val rows = OutdatedRows.build(report, fallbackTargetPath = "/repo/App.sln")
        assertEquals(1, rows.size)
        assertEquals("/repo/Sahelanthropus.Data/Sahelanthropus.Data.csproj", rows[0].upgradeTarget)
        assertEquals(3, rows[0].deps.size)
        assertTrue(rows[0].deps.all { it.outdated })
    }

    @Test
    fun includesUpToDatePackagesWithNewVersionEqualToCurrent() {
        val json = """
            {
              "Projects": [
                {
                  "Name": "App",
                  "FilePath": "/repo/App.csproj",
                  "TargetFrameworks": [
                    {
                      "Name": "net10.0",
                      "Dependencies": [
                        { "Name": "Up.To.Date", "ResolvedVersion": "1.2.3", "LatestVersion": "1.2.3", "UpgradeSeverity": "None" },
                        { "Name": "Old.Package", "ResolvedVersion": "1.0.0", "LatestVersion": "2.0.0", "UpgradeSeverity": "Major" }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val rows = OutdatedRows.build(OutdatedReportParser.parse(json), fallbackTargetPath = "/repo/App.sln")
        val deps = rows.single().deps.associateBy { it.name }

        val upToDate = deps.getValue("Up.To.Date")
        assertFalse(upToDate.outdated)
        assertEquals("1.2.3", upToDate.current)
        assertEquals("1.2.3", upToDate.newVersion) // new == current when nothing newer

        val old = deps.getValue("Old.Package")
        assertTrue(old.outdated)
        assertEquals("2.0.0", old.newVersion)
    }

    @Test
    fun emptyInputYieldsEmptyReport() {
        assertTrue(OutdatedReportParser.parse("").projects.isEmpty())
        assertTrue(OutdatedReportParser.parse("   ").projects.isEmpty())
    }

    @Test(expected = JsonSyntaxException::class)
    fun malformedJsonThrows() {
        OutdatedReportParser.parse("{ not valid json ")
    }
}
