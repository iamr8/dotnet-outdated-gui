package com.github.arash.dotnetoutdated

import com.github.arash.dotnetoutdated.cli.ListPackagesCommand
import com.github.arash.dotnetoutdated.parse.ListPackagesParser
import com.github.arash.dotnetoutdated.ui.OutdatedRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ListPackagesTest {

    private val sample = """
        {
          "version": 1,
          "parameters": "",
          "projects": [
            {
              "path": "/repo/Demo/Demo.csproj",
              "frameworks": [
                {
                  "framework": "net10.0",
                  "topLevelPackages": [
                    { "id": "Newtonsoft.Json", "requestedVersion": "13.0.1", "resolvedVersion": "13.0.1" },
                    { "id": "Serilog", "requestedVersion": "3.*", "resolvedVersion": "3.1.1" }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun listCommandDefaults() {
        assertEquals(
            listOf("dotnet", "list", "/repo/App.sln", "package", "--format", "json"),
            ListPackagesCommand.list("dotnet", "/repo/App.sln", includeTransitive = false),
        )
    }

    @Test
    fun listCommandWithTransitive() {
        assertEquals(
            listOf("dotnet", "list", "/repo/App.sln", "package", "--format", "json", "--include-transitive"),
            ListPackagesCommand.list("dotnet", "/repo/App.sln", includeTransitive = true),
        )
    }

    @Test
    fun buildsRowsWithCurrentVersionsAndNothingOutdated() {
        val rows = OutdatedRows.buildFromListing(ListPackagesParser.parse(sample), fallbackTargetPath = "/repo/App.sln")
        assertEquals(1, rows.size)
        val project = rows.single()
        assertEquals("Demo", project.name) // derived from the .csproj file name
        assertEquals("/repo/Demo/Demo.csproj", project.upgradeTarget)

        val deps = project.deps.associateBy { it.name }
        assertEquals("13.0.1", deps.getValue("Newtonsoft.Json").current)
        // resolvedVersion wins over the floating requestedVersion
        assertEquals("3.1.1", deps.getValue("Serilog").current)
        // No update check yet: New Version is empty, nothing outdated/checkable.
        assertEquals("", deps.getValue("Serilog").newVersion)
        assertFalse(project.deps.any { it.outdated })
    }

    @Test
    fun emptyReportYieldsNoRows() {
        assertEquals(0, OutdatedRows.buildFromListing(ListPackagesParser.parse(""), "/x").size)
    }
}
