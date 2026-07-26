package com.github.iamr8.dotnetoutdated.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliFailuresTest {

    /** Verbatim `dotnet outdated` output for a csproj referencing a version that doesn't exist. */
    private val nu1102Output = """
        R8.RedisHashMap.Tests: Unable to process the project `/repo/test/R8.RedisHashMap.Tests.csproj`. Are you sure this is a valid .NET Core or .NET Standard project type?

        Here is the full error message returned from the Microsoft Build Engine:

        MSBuild version 18.6.11+35b593beb for .NET
          Determining projects to restore...
        /repo/test/R8.RedisHashMap.Tests.csproj : warning NU1504: Duplicate 'PackageReference' items found. [TargetFramework=netstandard2.1]
        /repo/test/R8.RedisHashMap.Tests.csproj : error NU1102: Unable to find package xunit.runner.visualstudio with version (>= 3.0.5 && < 3.1.0)
        /repo/test/R8.RedisHashMap.Tests.csproj : error NU1102:   - Found 100 version(s) in nuget.org [ Nearest version: 3.1.0 ]
          Failed to restore /repo/test/R8.RedisHashMap.Tests.csproj (in 942 ms).
          1 of 2 projects are up-to-date for restore.
         -  - exit code: 1
    """.trimIndent()

    @Test
    fun `unresolvable package version is reported as a restore failure, not a wall of MSBuild output`() {
        val message = CliFailures.describe(nu1102Output, "")
        assertTrue(message, "doesn't exist on the configured NuGet feeds" in message)
        assertTrue("the fix should point at the project file", "Directory.Packages.props" in message)
        assertTrue("must stay short enough for a balloon", message.length < 300)
        assertTrue("must not leak MSBuild noise", "NU1504" !in message && "MSBuild version" !in message)
    }

    @Test
    fun `missing dotnet-outdated tool suggests the install command`() {
        val message = CliFailures.describe("No executable found matching command \"dotnet-outdated\"", "")
        assertEquals("dotnet-outdated tool not found. Install: dotnet tool install -g dotnet-outdated-tool", message)
    }

    @Test
    fun `blank output falls back to the dotnet-not-runnable hint`() {
        assertEquals(
            "Could not run dotnet. Ensure the .NET SDK is installed and on PATH.",
            CliFailures.describe("", ""),
        )
    }

    @Test
    fun `unrestored project asks for a restore`() {
        val message = CliFailures.describe(
            "error NETSDK1004: Assets file '/repo/obj/project.assets.json' not found. Run a NuGet package restore",
            "",
        )
        assertTrue(message, "isn't restored" in message)
    }

    @Test
    fun `a project that cannot be loaded is described as a restore failure`() {
        val message = CliFailures.describe("Unable to process the project `/repo/a.csproj`.", "")
        assertTrue(message, "Restore failed for this project" in message)
    }

    @Test
    fun `unknown failures surface the first error line, truncated`() {
        val message = CliFailures.describe(
            "  Determining projects to restore...\n/repo/a.csproj : error MSB4025: bad XML\nmore noise",
            "",
        )
        assertEquals("/repo/a.csproj : error MSB4025: bad XML", message)
    }

    @Test
    fun `unknown failures without an error line fall back to the first line`() {
        assertEquals("something odd happened", CliFailures.describe("", "something odd happened\nand more"))
    }

    @Test
    fun `an overlong line is truncated`() {
        val long = "x".repeat(500)
        val message = CliFailures.describe(long, "")
        assertTrue(message.length <= 241)
        assertTrue(message.endsWith("…"))
    }
}
