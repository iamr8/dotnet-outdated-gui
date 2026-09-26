package com.github.iamr8.nugetextended.cli

import com.github.iamr8.nugetextended.engine.HelperProcess
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.charset.StandardCharsets

/** Runs `dotnet restore`. Blocking; call from a background thread. */
class DotnetRunner(private val dotnet: String = DotnetLocator.resolve()) {

    data class RunFailure(val label: String, val stderr: String, val stdout: String, val timedOut: Boolean)

    fun restore(
        affected: List<String>,
        solutionPath: String?,
        solutionProjects: Collection<String>,
        runtime: String,
        workDir: String,
        timeoutMs: Long,
        indicator: ProgressIndicator,
        afterUpgrade: Boolean,
    ): List<RunFailure> {
        val commands = RestoreCommand.plan(dotnet, affected, solutionPath, solutionProjects, runtime, afterUpgrade) { project ->
            File(project).resolveSibling("packages.lock.json").isFile
        }
        val env = restoreEnvironment(EnvironmentUtil.getEnvironmentMap())
        val failures = mutableListOf<RunFailure>()
        for (cmd in commands) {
            indicator.checkCanceled()
            val label = File(cmd[2]).name
            indicator.text2 = "dotnet restore $label"
            val line = GeneralCommandLine(cmd)
                .withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory(workDir)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                .withEnvironment(env)
            val out = CapturingProcessHandler(line).runProcessWithProgressIndicator(indicator, timeoutMs.toInt())
            if (out.isCancelled) indicator.checkCanceled()
            if (out.isTimeout || out.exitCode != 0) failures += RunFailure(label, out.stderr, out.stdout, out.isTimeout)
        }
        return failures
    }

    companion object {
        /**
         * Env for `dotnet restore` child processes: [HelperProcess.childEnvironment] (drops
         * MSBuild* keys) plus MSBUILDDISABLENODEREUSE=1.
         *
         * `dotnet restore` of a multi-project solution starts MSBuild worker nodes with node
         * reuse on. With MSBuild* variables present those nodes keep their stdout/stderr pipes
         * open for a 15-minute idle timeout, so `CapturingProcessHandler` (which waits for pipe
         * EOF) stalls until then. The `-nodeReuse:false` flag fixes this for project restores
         * but breaks `dotnet restore app.cs` (file-based apps) on SDK 10 with MSB4025, so the
         * flag is never added; this env var fixes both cases.
         */
        fun restoreEnvironment(parent: Map<String, String>): Map<String, String> =
            HelperProcess.childEnvironment(parent) + mapOf("MSBUILDDISABLENODEREUSE" to "1")
    }
}
