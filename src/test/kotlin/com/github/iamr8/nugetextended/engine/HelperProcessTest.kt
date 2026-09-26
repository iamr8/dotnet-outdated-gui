package com.github.iamr8.nugetextended.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HelperProcessTest {
    @Test
    fun missingRuntimeHasAnInstallHint() {
        val stderr = "You must install or update .NET to run this application.\nFramework: 'Microsoft.NETCore.App', version '6.0.0'"
        assertEquals("The engine needs the .NET 6 runtime or later. Install the .NET 6 SDK or later.", HelperProcess.startMessage(stderr))
    }

    @Test
    fun otherStartFailuresAreGeneric() {
        assertEquals("The engine could not start. Copy Details has its output.", HelperProcess.startMessage("boom"))
        assertEquals("The engine could not start. Copy Details has its output.", HelperProcess.startMessage(null))
    }

    @Test
    fun childEnvironmentDropsMsBuildVariablesAnyCase() {
        val parent = mapOf(
            "PATH" to "/usr/bin",
            "MSBuildSDKsPath" to "/ide/sdks",
            "MSBUILD_EXE_PATH" to "/ide/msbuild.exe",
            "msbuildextensionspath" to "/ide/ext",
            "MSBUILDDISABLENODEREUSE" to "1",
        )
        val child = HelperProcess.childEnvironment(parent)
        assertFalse(child.containsKey("MSBuildSDKsPath"))
        assertFalse(child.containsKey("MSBUILD_EXE_PATH"))
        assertFalse(child.containsKey("msbuildextensionspath"))
        assertFalse(child.containsKey("MSBUILDDISABLENODEREUSE"))
        assertEquals("/usr/bin", child["PATH"])
    }

    @Test
    fun childEnvironmentAddsDotnetOptOutAndNoLogo() {
        val child = HelperProcess.childEnvironment(mapOf("PATH" to "/usr/bin"))
        assertEquals("1", child["DOTNET_CLI_TELEMETRY_OPTOUT"])
        assertEquals("1", child["DOTNET_NOLOGO"])
    }

    @Test
    fun childEnvironmentOverridesAnInheritedDotnetOptOut() {
        val parent = mapOf("DOTNET_CLI_TELEMETRY_OPTOUT" to "0", "DOTNET_NOLOGO" to "0")
        val child = HelperProcess.childEnvironment(parent)
        assertEquals("1", child["DOTNET_CLI_TELEMETRY_OPTOUT"])
        assertEquals("1", child["DOTNET_NOLOGO"])
    }
}
