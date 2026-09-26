package com.github.iamr8.nugetextended.engine

import com.github.iamr8.nugetextended.PluginText
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rider's own NuGet restore. Rider restores a project by itself when its file changes, and a
 * `dotnet restore` of the same project at the same time makes both fail (the obj folder's
 * `*.nuget.g.props` "already exists"). So our restore waits for Rider's first.
 */
object RiderRestore {

    enum class Outcome { RIDER_RESTORED, NOT_RUNNING, UNKNOWN, STILL_RUNNING }

    /**
     * Pure wait loop. [busy] is Rider's state now, or null when it can't be read. Waits up to
     * [graceMs] for a restore to start, then until it ends - at most [maxMs] in all.
     */
    fun await(
        busy: () -> Boolean?,
        graceMs: Long,
        maxMs: Long,
        pollMs: Long,
        now: () -> Long,
        sleep: (Long) -> Unit,
        checkCanceled: () -> Unit,
    ): Outcome {
        val start = now()
        var seen = false
        while (true) {
            checkCanceled()
            val elapsed = now() - start
            when (busy()) {
                null -> return Outcome.UNKNOWN
                true -> seen = true
                false -> if (seen) return Outcome.RIDER_RESTORED else if (elapsed >= graceMs) return Outcome.NOT_RUNNING
            }
            if (elapsed >= maxMs) return if (seen) Outcome.STILL_RUNNING else Outcome.NOT_RUNNING
            sleep(pollMs)
        }
    }

    /**
     * Pure: what our own restore still has to do. [changed] are projects whose files changed (Rider
     * restores those itself); [alwaysOurs] need our restore anyway (floating versions: no file change,
     * so Rider sees nothing to do). Null while Rider still restores: running ours now would collide.
     */
    fun oursAfter(outcome: Outcome, changed: List<String>, alwaysOurs: List<String>): List<String>? = when (outcome) {
        Outcome.RIDER_RESTORED -> alwaysOurs.distinct()
        Outcome.NOT_RUNNING, Outcome.UNKNOWN -> (changed + alwaysOurs).distinct()
        Outcome.STILL_RUNNING -> null
    }

    /**
     * Rider's `RdNuGetOperator.isBusy`, read by reflection: the model classes are internal, move
     * between Rider versions, and live in a module this plugin's class loader may not see. Null when
     * this Rider does not expose it - the caller then restores as if Rider were idle.
     */
    fun isBusy(project: Project): Boolean? = try {
        read(project)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Throwable) {
        if (reported.compareAndSet(false, true)) {
            LOG.info("${PluginText.NAME}: Rider's NuGet restore state is not readable (${e.javaClass.simpleName}: ${e.message}); restoring without waiting for Rider")
        }
        null
    }

    private fun read(project: Project): Boolean {
        val loader = loader()
        val solution = loader.loadClass(SOLUTION_EXTENSIONS).getMethod("getSolution", Project::class.java).invoke(null, project)
        val getHost = HOST_EXTENSIONS.firstNotNullOfOrNull { name ->
            try {
                loader.loadClass(name).methods.firstOrNull { it.name == "getNuGetHost" && it.parameterCount == 1 }
            } catch (_: ClassNotFoundException) {
                null
            }
        } ?: error("getNuGetHost not found")
        val host = getHost.invoke(null, solution)
        val operator = host.javaClass.getMethod("getNuGetOperator").invoke(host)
        val property = operator.javaClass.getMethod("isBusy").invoke(operator)
        val value = property.javaClass.methods.first { it.name == "getValueOrNull" && it.parameterCount == 0 }.invoke(property)
        if (readable.compareAndSet(false, true)) LOG.info("${PluginText.NAME}: Rider's NuGet restore state is readable")
        return value == true
    }

    /** Our own loader when it sees Rider's classes; else the loader of a Rider NuGet action, whose module depends on them. */
    private fun loader(): ClassLoader {
        val own = RiderRestore::class.java.classLoader
        val sees = try {
            own.loadClass(SOLUTION_EXTENSIONS); true
        } catch (_: ClassNotFoundException) {
            false
        }
        if (sees) return own
        return ActionManager.getInstance().getAction(RIDER_NUGET_ACTION)?.javaClass?.classLoader ?: own
    }

    private const val SOLUTION_EXTENSIONS = "com.jetbrains.rider.projectView.SolutionHostExtensionsKt"
    private val HOST_EXTENSIONS = listOf("com.jetbrains.rider.model.RdNuGetHost_PregeneratedKt", "com.jetbrains.rider.model.RdNuGetHostKt")
    private const val RIDER_NUGET_ACTION = "RiderNuGetPopupRestoreAction"
    private val reported = AtomicBoolean()
    private val readable = AtomicBoolean()
    private val LOG = logger<RiderRestore>()
}
