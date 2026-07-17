package com.github.iamr8.dotnetoutdated

import com.github.iamr8.dotnetoutdated.cli.CredLogLevel
import com.github.iamr8.dotnetoutdated.cli.OutdatedCommand
import com.github.iamr8.dotnetoutdated.cli.OutdatedOptions
import com.github.iamr8.dotnetoutdated.cli.PreRelease
import com.github.iamr8.dotnetoutdated.cli.VersionLock
import org.junit.Assert.assertEquals
import org.junit.Test

class OutdatedCommandTest {

    @Test
    fun scanWithDefaultsUsesSafeReliabilityFlagsAndNoUtd() {
        // includeUpToDate defaults to false now -> no -utd (only outdated packages reported).
        val cmd = OutdatedCommand.scan("dotnet", "/repo/App.sln", "/tmp/out.json", OutdatedOptions())
        assertEquals(
            listOf("dotnet", "outdated", "/repo/App.sln", "-ifs", "-it", "300", "-o", "/tmp/out.json", "-of", "json"),
            cmd,
        )
    }

    @Test
    fun scanListsAllWhenIncludeUpToDateEnabled() {
        val cmd = OutdatedCommand.scan("dotnet", "/repo/App.sln", "/tmp/out.json", OutdatedOptions(includeUpToDate = true))
        assertEquals(
            listOf("dotnet", "outdated", "/repo/App.sln", "-ifs", "-it", "300", "-utd", "-o", "/tmp/out.json", "-of", "json"),
            cmd,
        )
    }

    @Test
    fun scanWithClearedDefaultsIsMinimal() {
        val bare = OutdatedOptions(includeUpToDate = false, ignoreFailedSources = false, idleTimeoutSeconds = 120)
        val cmd = OutdatedCommand.scan("dotnet", "/repo/App.sln", "/tmp/out.json", bare)
        assertEquals(
            listOf("dotnet", "outdated", "/repo/App.sln", "-o", "/tmp/out.json", "-of", "json"),
            cmd,
        )
    }

    @Test
    fun scanThreadsEveryOption() {
        val opts = OutdatedOptions(
            includeAutoReferences = true,
            transitive = true,
            transitiveDepth = 3,
            includeUpToDate = true,
            preRelease = PreRelease.Always,
            preReleaseLabel = "rc",
            versionLock = VersionLock.Major,
            maximumVersion = "8.0",
            olderThanDays = 7,
            recursive = true,
            includeFileBasedApps = true,
            includeFilters = mutableListOf("Alpha"),
            excludeFilters = mutableListOf("Beta"),
            noRestore = true,
            ignoreFailedSources = true,
            idleTimeoutSeconds = 200,
            runtime = "linux-x64",
            credLogLevel = CredLogLevel.Error,
        )
        val cmd = OutdatedCommand.scan("dotnet", "/repo/App.sln", "/tmp/out.json", opts)
        assertEquals(
            listOf(
                "dotnet", "outdated", "/repo/App.sln",
                "-i", "-t", "-td", "3", "-pre", "Always", "-prl", "rc", "-vl", "Major",
                "-mv", "8.0", "-ot", "7", "-r", "-fba", "-n", "-ifs", "-it", "200",
                "-rt", "linux-x64", "-ncll", "Error",
                "-utd", "-inc", "Alpha", "-exc", "Beta",
                "-o", "/tmp/out.json", "-of", "json",
            ),
            cmd,
        )
    }

    @Test
    fun transitiveDepthOmittedWhenDefaultOrNotTransitive() {
        // depth != 1 but transitive off -> no -t/-td
        val offButDeep = OutdatedOptions(transitive = false, transitiveDepth = 5, ignoreFailedSources = false, idleTimeoutSeconds = 120, includeUpToDate = false)
        assertEquals(
            listOf("dotnet", "outdated", "/p", "-o", "/o", "-of", "json"),
            OutdatedCommand.scan("dotnet", "/p", "/o", offButDeep),
        )
        // transitive on, depth 1 -> -t only, no -td
        val onDefaultDepth = offButDeep.copy(transitive = true, transitiveDepth = 1)
        assertEquals(
            listOf("dotnet", "outdated", "/p", "-t", "-o", "/o", "-of", "json"),
            OutdatedCommand.scan("dotnet", "/p", "/o", onDefaultDepth),
        )
    }

    @Test
    fun upgradeWithDefaultsAddsIncludePerPackage() {
        val cmd = OutdatedCommand.upgrade("dotnet", "/repo/App.csproj", listOf("Foo", "Bar"), OutdatedOptions())
        assertEquals(
            listOf("dotnet", "outdated", "/repo/App.csproj", "-u", "-ifs", "-it", "300", "-inc", "Foo", "-inc", "Bar"),
            cmd,
        )
    }

    @Test
    fun upgradeAppliesVersionPolicyAndExcludes() {
        val opts = OutdatedOptions(versionLock = VersionLock.Minor, excludeFilters = mutableListOf("Preview"))
        val cmd = OutdatedCommand.upgrade("dotnet", "/repo/App.csproj", listOf("Foo"), opts)
        assertEquals(
            listOf("dotnet", "outdated", "/repo/App.csproj", "-u", "-vl", "Minor", "-ifs", "-it", "300", "-inc", "Foo", "-exc", "Preview"),
            cmd,
        )
    }
}
