package com.github.iamr8.dotnetoutdated.engine

import com.github.iamr8.dotnetoutdated.PluginText
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.util.EnvironmentUtil
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The running helper: `dotnet <plugin>/helper/Helper.dll <solutionDir>`. */
class HelperProcess private constructor(
    private val process: Process,
    val client: HelperClient,
    val hello: Hello,
) {
    val isAlive: Boolean get() = process.isAlive && !client.isClosed

    fun close() {
        client.close()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        const val PROTOCOL = 1
        private const val START_TIMEOUT_MS = 60_000L
        private const val STDERR_LINES = 100

        fun start(dotnet: String, helperDll: Path, workDir: String, log: (String) -> Unit): HelperProcess {
            val command = GeneralCommandLine(dotnet, helperDll.toString(), workDir)
                .withWorkDirectory(workDir)
                .withCharset(UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                .withEnvironment(childEnvironment(EnvironmentUtil.getEnvironmentMap()))
            val process = try {
                command.createProcess()
            } catch (e: ExecutionException) {
                throw HelperFatalException("Could not run dotnet. Install the .NET 6 SDK or later.", e.message)
            }

            val tail = ArrayDeque<String>()
            thread(isDaemon = true, name = "${PluginText.NAME} engine stderr") {
                process.errorStream.bufferedReader(UTF_8).forEachLine { line ->
                    synchronized(tail) {
                        tail.addLast(line)
                        if (tail.size > STDERR_LINES) tail.removeFirst()
                    }
                    log(line)
                }
            }
            val details = { synchronized(tail) { tail.joinToString("\n") }.ifBlank { null } }
            val client = HelperClient(process.inputStream, process.outputStream, details)

            val hello = try {
                client.awaitHello(START_TIMEOUT_MS)
            } catch (e: HelperFatalException) {
                process.destroyForcibly()
                throw e
            } catch (e: HelperException) {
                process.waitFor(2, TimeUnit.SECONDS) // let stderr drain before we read it
                process.destroyForcibly()
                throw HelperFatalException(startMessage(details()), details())
            }
            if (hello.protocol != PROTOCOL) {
                client.close()
                process.destroyForcibly()
                throw HelperFatalException("The engine does not match this plugin version. Reinstall the plugin.")
            }
            return HelperProcess(process, client, hello)
        }

        /** Short text for a helper that exited before its handshake. */
        fun startMessage(stderr: String?): String = when {
            stderr != null && (stderr.contains("You must install or update .NET", ignoreCase = true) ||
                stderr.contains("Microsoft.NETCore.App", ignoreCase = true)) ->
                "The engine needs the .NET 6 runtime or later. Install the .NET 6 SDK or later."
            else -> "The engine could not start. Copy Details has its output."
        }

        /**
         * The IDE's own environment, minus `MSBuild*` (any case) and `MSBUILD_EXE_PATH`: the
         * helper must resolve its SDK from [workDir] with `MSBuildLocator`, not from whatever
         * MSBuild the IDE has already loaded. Adds the two dotnet CLI quieting variables.
         */
        fun childEnvironment(parent: Map<String, String>): Map<String, String> {
            // Drops every MSBUILD*-prefixed key on purpose, including user-set ones like
            // MSBUILDDISABLENODEREUSE: the helper must resolve MSBuild only from workDir.
            val filtered = parent.filterKeys { !it.startsWith("MSBUILD", ignoreCase = true) }
            return filtered + mapOf(
                "DOTNET_CLI_TELEMETRY_OPTOUT" to "1",
                "DOTNET_NOLOGO" to "1",
            )
        }
    }
}
