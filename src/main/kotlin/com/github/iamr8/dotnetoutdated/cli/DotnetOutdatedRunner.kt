package com.github.iamr8.dotnetoutdated.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import java.io.File
import java.nio.charset.StandardCharsets

/** Result of a scan: the parsed-report JSON plus the raw process output for diagnostics. */
data class ScanResult(
    val exitCode: Int,
    val json: String,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

/** Result of an upgrade run: raw output only (a re-scan reflects the real end state). */
data class UpgradeResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

/** Result of listing packages via `dotnet list package` (JSON printed to stdout). */
data class ListResult(
    val exitCode: Int,
    val json: String,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

/** Runs `dotnet outdated`. Blocking; call from a background thread. */
class DotnetOutdatedRunner(
    private val dotnetPath: String = DotnetLocator.resolve(),
) {

    /** True if the `dotnet-outdated` global tool is installed and runnable. */
    fun isOutdatedInstalled(): Boolean = try {
        val cmd = listOf(dotnetPath, "outdated", "--version")
        val out = CapturingProcessHandler(GeneralCommandLine(cmd).withCharset(StandardCharsets.UTF_8))
            .runProcess(30_000)
        out.exitCode == 0 && !out.isTimeout
    } catch (e: Exception) {
        false
    }

    fun scan(targetPath: String, workDir: String?, options: OutdatedOptions): ScanResult {
        val outputFile = File.createTempFile("dotnet-outdated-", ".json").apply { deleteOnExit() }
        try {
            val cmd = OutdatedCommand.scan(dotnetPath, targetPath, outputFile.absolutePath, options)
            val output = run(cmd, workDir, options)
            val json = if (output.exitCode == 0 && outputFile.length() > 0) {
                outputFile.readText(StandardCharsets.UTF_8)
            } else {
                ""
            }
            return ScanResult(output.exitCode, json, output.stdout, output.stderr, output.isTimeout)
        } finally {
            outputFile.delete()
        }
    }

    /** Fast, offline package discovery — `dotnet list package --format json` (JSON on stdout). */
    fun listPackages(targetPath: String, workDir: String?, options: OutdatedOptions): ListResult {
        val cmd = ListPackagesCommand.list(dotnetPath, targetPath, options.transitive)
        val output = run(cmd, workDir, options)
        // `dotnet list package` prints JSON to stdout; accept it even with a non-zero exit
        // (e.g. warnings), as long as the payload actually looks like JSON.
        val json = if (output.stdout.trimStart().startsWith("{")) output.stdout else ""
        return ListResult(output.exitCode, json, output.stdout, output.stderr, output.isTimeout)
    }

    fun upgrade(targetPath: String, packageNames: List<String>, workDir: String?, options: OutdatedOptions): UpgradeResult {
        val cmd = OutdatedCommand.upgrade(dotnetPath, targetPath, packageNames, options)
        val output = run(cmd, workDir, options)
        return UpgradeResult(output.exitCode, output.stdout, output.stderr, output.isTimeout)
    }

    private fun run(cmd: List<String>, workDir: String?, options: OutdatedOptions): ProcessOutput {
        val commandLine = GeneralCommandLine(cmd)
            .withCharset(StandardCharsets.UTF_8)
        if (workDir != null) commandLine.setWorkDirectory(workDir)
        // Overall process timeout must comfortably exceed the CLI's own idle timeout.
        val timeoutMs = maxOf(5 * 60 * 1000, (options.idleTimeoutSeconds + 60) * 1000)
        return CapturingProcessHandler(commandLine).runProcess(timeoutMs)
    }
}
