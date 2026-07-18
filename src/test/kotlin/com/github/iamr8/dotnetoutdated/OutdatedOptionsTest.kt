package com.github.iamr8.dotnetoutdated

import com.github.iamr8.dotnetoutdated.cli.CredLogLevel
import com.github.iamr8.dotnetoutdated.cli.OutdatedOptions
import com.github.iamr8.dotnetoutdated.cli.PreRelease
import com.github.iamr8.dotnetoutdated.cli.VersionLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class OutdatedOptionsTest {

    private fun populated() = OutdatedOptions(
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
        includeFilters = mutableListOf("A"),
        excludeFilters = mutableListOf("B"),
        noRestore = true,
        ignoreFailedSources = false,
        idleTimeoutSeconds = 200,
        runtime = "linux-x64",
        credLogLevel = CredLogLevel.Error,
    )

    @Test
    fun deepCopyEqualsButListsAreIndependent() {
        val original = populated()
        val copy = original.deepCopy()
        assertEquals(original, copy)
        assertNotSame(original.includeFilters, copy.includeFilters)

        copy.includeFilters.add("X")
        assertEquals(listOf("A"), original.includeFilters) // original untouched
    }

    @Test
    fun assignFromCopiesEveryFieldAndDetachesLists() {
        val source = populated()
        val target = OutdatedOptions() // defaults
        target.assignFrom(source)
        assertEquals(source, target)

        target.excludeFilters.add("Y")
        assertEquals(listOf("B"), source.excludeFilters) // source untouched
    }
}
