package com.github.iamr8.nugetextended.cli

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreCommandTest {
    private val sln = "/r/App.sln"
    private val all = listOf("/r/A/A.csproj", "/r/B/B.csproj")

    @Test
    fun solutionCallWhenItCoversEveryProject() {
        val cmds = RestoreCommand.plan("dotnet", all, sln, all, "", afterUpgrade = true)
        assertEquals(listOf(listOf("dotnet", "restore", sln, "-p:RestoreLockedMode=false", "--force-evaluate")), cmds)
    }

    @Test
    fun perProjectWhenAProjectIsOutsideTheSolution() {
        val cmds = RestoreCommand.plan("dotnet", all + "/r/X/X.csproj", sln, all, "", afterUpgrade = true)
        assertEquals(3, cmds.size)
        assertEquals(listOf("dotnet", "restore", "/r/X/X.csproj", "-p:RestoreLockedMode=false", "--force-evaluate"), cmds[2])
    }

    @Test
    fun perProjectWithoutSolution() {
        val cmds = RestoreCommand.plan("dotnet", all, null, emptyList(), "", afterUpgrade = true)
        assertEquals(listOf("/r/A/A.csproj", "/r/B/B.csproj"), cmds.map { it[2] })
    }

    // Break: --force-evaluate only when a sibling packages.lock.json exists (the old file name check).
    @Test
    fun afterUpgradeAlwaysForcesEvaluateAndRuntimeAddsRid() {
        // No lock file is looked up: a floating upgrade edits nothing, and a lock file can have any name.
        val cmds = RestoreCommand.plan("dotnet", listOf("/r/A/A.csproj"), null, emptyList(), "linux-x64", afterUpgrade = true)
        assertEquals(
            listOf("dotnet", "restore", "/r/A/A.csproj", "-p:RestoreLockedMode=false", "--force-evaluate", "-r", "linux-x64"),
            cmds.single(),
        )
    }

    @Test
    fun fileBasedAppsGetTheirOwnCall() {
        val cmds = RestoreCommand.plan("dotnet", all + "/r/tools/app.cs", sln, all, "", afterUpgrade = true)
        assertEquals(listOf(sln, "/r/tools/app.cs"), cmds.map { it[2] })
        assertEquals(listOf("dotnet", "restore", "/r/tools/app.cs", "-p:RestoreLockedMode=false"), cmds[1]) // an app has no lock file
    }

    @Test
    fun pathsCompareAfterNormalizing() {
        val cmds = RestoreCommand.plan("dotnet", listOf("/r/A/../A/A.csproj"), sln, listOf("/r/A/A.csproj"), "", afterUpgrade = true)
        assertEquals(sln, cmds.single()[2])
    }

    @Test
    fun scanTimeRestoreKeepsTheLockFileRules() {
        // Only an upgrade changes versions on purpose; a scan-time restore must not rewrite a
        // locked-mode lock file.
        val cmds = RestoreCommand.plan("dotnet", listOf("/r/A/A.csproj"), null, emptyList(), "linux-x64", afterUpgrade = false)
        assertEquals(listOf("dotnet", "restore", "/r/A/A.csproj", "-r", "linux-x64"), cmds.single())
    }
}
