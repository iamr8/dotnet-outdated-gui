package com.github.iamr8.dotnetoutdated.engine

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/** Tells the engine which cached evaluations are out of date. */
class FileWatch(private val project: Project) : BulkFileListener {

    sealed class Action {
        data object None : Action()
        data object Restart : Action()
        data class Invalidate(val all: Boolean) : Action()
    }

    override fun after(events: List<VFileEvent>) {
        val service = project.serviceIfCreated<HelperService>() ?: return
        for (event in events) {
            val name = event.path.substringAfterLast('/')
            when (val action = decide(name, structural = event !is VFileContentChangeEvent)) {
                Action.None -> {}
                Action.Restart -> service.restartLater()
                is Action.Invalidate -> service.markChanged(if (action.all) "*" else event.path)
            }
        }
    }

    companion object {
        private val importExtensions = setOf("props", "targets")
        private val projectExtensions = setOf("csproj", "fsproj", "vbproj")

        fun decide(fileName: String, structural: Boolean): Action {
            val lower = fileName.lowercase()
            val ext = lower.substringAfterLast('.', "")
            return when {
                lower == "global.json" || lower == "nuget.config" -> Action.Restart
                ext in importExtensions -> Action.Invalidate(all = structural)
                ext in projectExtensions || ext == "cs" -> Action.Invalidate(all = false)
                else -> Action.None
            }
        }
    }
}
