package com.github.iamr8.nugetextended.cli

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreCommandTest {
    private val sln = "/r/App.sln"
    private val all = listOf("/r/A/A.csproj", "/r/B/B.csproj")
    private val noLock: (String) -> Boolean = { false }

    @Test
    fun solutionCallWhenItCoversEveryProject() {
        val cmds = RestoreCommand.plan("dotnet", all, sln, all, "", afterUpgrade = true, noLock)
        assertEquals(listOf(listOf("dotnet", "restore", sln, "-p:RestoreLockedMode=false")), cmds)
    }

    @Test
    fun perProjectWhenAProjectIsOutsideTheSolution() {
        val cmds = RestoreCommand.plan("dotnet", all + "/r/X/X.csproj", sln, all, "", afterUpgrade = true, noLock)
        assertEquals(3, cmds.size)
        assertEquals(listOf("dotnet", "restore", "/r/X/X.csproj", "-p:RestoreLockedMode=false"), cmds[2])
    }

    @Test
    fun perProjectWithoutSolution() {
        val cmds = RestoreCommand.plan("dotnet", all, null, emptyList(), "", afterUpgrade = true, noLock)
        assertEquals(listOf("/r/A/A.csproj", "/r/B/B.csproj"), cmds.map { it[2] })
    }

    @Test
    fun lockFileAddsForceEvaluateAndRuntimeAddsRid() {
        val cmds = RestoreCommand.plan("dotnet", listOf("/r/A/A.csproj"), null, emptyList(), "linux-x64", afterUpgrade = true) { it == "/r/A/A.csproj" }
        assertEquals(
            listOf("dotnet", "restore", "/r/A/A.csproj", "-p:RestoreLockedMode=false", "--force-evaluate", "-r", "linux-x64"),
            cmds.single(),
        )
    }

    @Test
    fun fileBasedAppsGetTheirOwnCall() {
        val cmds = RestoreCommand.plan("dotnet", all + "/r/tools/app.cs", sln, all, "", afterUpgrade = true, noLock)
        assertEquals(listOf(sln, "/r/tools/app.cs"), cmds.map { it[2] })
    }

    @Test
    fun pathsCompareAfterNormalizing() {
        val cmds = RestoreCommand.plan("dotnet", listOf("/r/A/../A/A.csproj"), sln, listOf("/r/A/A.csproj"), "", afterUpgrade = true, noLock)
        assertEquals(sln, cmds.single()[2])
    }

    @Test
    fun scanTimeRestoreKeepsTheLockFileRules() {
        // Only an upgrade changes versions on purpose; a scan-time restore must not rewrite a
        // locked-mode lock file.
        val cmds = RestoreCommand.plan("dotnet", listOf("/r/A/A.csproj"), null, emptyList(), "linux-x64", afterUpgrade = false) { true }
        assertEquals(listOf("dotnet", "restore", "/r/A/A.csproj", "-r", "linux-x64"), cmds.single())
    }
}
