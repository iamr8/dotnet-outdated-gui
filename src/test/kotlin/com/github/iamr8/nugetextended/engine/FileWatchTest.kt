package com.github.iamr8.nugetextended.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FileWatchTest {
    @Test
    fun sdkAndFeedSettingsRestartTheEngine() {
        assertEquals(FileWatch.Action.Restart, FileWatch.decide("global.json", structural = false))
        assertEquals(FileWatch.Action.Restart, FileWatch.decide("NuGet.Config", structural = false))
        assertEquals(FileWatch.Action.Restart, FileWatch.decide("nuget.config", structural = true))
    }

    @Test
    fun projectFilesInvalidate() {
        assertEquals(FileWatch.Action.Invalidate(all = false), FileWatch.decide("A.csproj", structural = false))
        assertEquals(FileWatch.Action.Invalidate(all = false), FileWatch.decide("Directory.Packages.props", structural = false))
        assertEquals(FileWatch.Action.Invalidate(all = false), FileWatch.decide("Build.targets", structural = false))
        assertEquals(FileWatch.Action.Invalidate(all = false), FileWatch.decide("app.cs", structural = false))
    }

    @Test
    fun newOrDeletedImportInvalidatesEverything() {
        // A new Directory.Build.props is not an input of any cached project yet.
        assertEquals(FileWatch.Action.Invalidate(all = true), FileWatch.decide("Directory.Build.props", structural = true))
        assertEquals(FileWatch.Action.Invalidate(all = false), FileWatch.decide("app.cs", structural = true))
    }

    @Test
    fun otherFilesAreIgnored() {
        assertEquals(FileWatch.Action.None, FileWatch.decide("packages.lock.json", structural = false))
        assertEquals(FileWatch.Action.None, FileWatch.decide("README.md", structural = true))
    }
}
