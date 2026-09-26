package com.github.iamr8.nugetextended.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DotnetRunnerTest {

    @Test
    fun setsNodeReuseVariableDropsMSBuildKeysKeepsPath() {
        val parent = mapOf(
            "PATH" to "/usr/bin:/bin",
            "MSBuildExtensionsPath" to "/some/path",
            "MSBUILDDISABLENODEREUSE" to "0",
        )

        val env = DotnetRunner.restoreEnvironment(parent)

        assertEquals("1", env["MSBUILDDISABLENODEREUSE"])
        assertNull(env["MSBuildExtensionsPath"])
        assertEquals("/usr/bin:/bin", env["PATH"])
    }
}
