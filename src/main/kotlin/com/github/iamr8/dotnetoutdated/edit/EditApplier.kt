package com.github.iamr8.dotnetoutdated.edit

import com.github.iamr8.dotnetoutdated.PluginText
import com.github.iamr8.dotnetoutdated.engine.Edit
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/** Applies an engine plan to IDE documents: every edit located first, then one undo step, then saved. EDT only. */
object EditApplier {

    sealed class Result {
        data object Applied : Result()
        data class Failed(val reason: String) : Result()
    }

    fun apply(
        project: Project,
        edits: List<Edit>,
        commandName: String,
        find: (String) -> VirtualFile? = ::findLocal,
    ): Result {
        if (edits.isEmpty()) return Result.Applied
        val fdm = FileDocumentManager.getInstance()

        // Resolve each raw path to a VirtualFile first, then group by the resolved file (not the
        // raw path string) - two spellings of one path must combine into a single plan, not
        // silently overwrite each other's splices.
        val perFile = LinkedHashMap<VirtualFile, MutableList<Edit>>()
        for ((path, fileEdits) in edits.groupBy { it.file }) {
            val file = find(path) ?: return Result.Failed("${File(path).name} was not found - re-scan")
            perFile.getOrPut(file) { ArrayList() }.addAll(fileEdits)
        }

        val work = LinkedHashMap<Document, List<Splice>>()
        val files = ArrayList<VirtualFile>()
        for ((file, fileEdits) in perFile) {
            // a file changed on disk outside the IDE (or not yet indexed) must be seen before we plan against it
            VfsUtil.markDirtyAndRefresh(false, false, false, file)
            if (!file.isValid) return Result.Failed("${file.name} was deleted - re-scan")
            val document = fdm.getDocument(file) ?: return Result.Failed("${file.name} is not a text file")
            when (val planned = EditText.plan(document.text, fileEdits)) {
                is EditResult.Failed -> {
                    // a user's project file caused this, never LOG.error - but an unexpected
                    // exception during planning (planned.cause set) may be a plugin bug worth
                    // seeing in idea.log, so log it at warn with the throwable attached.
                    if (planned.cause != null) LOG.warn("${PluginText.NAME}: plan failed for ${file.name}: ${planned.reason}", planned.cause)
                    return Result.Failed("${file.name}: ${planned.reason}")
                }
                is EditResult.Ok -> {
                    work[document] = planned.splices
                    files += file
                }
            }
        }
        val status = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files)
        if (status.hasReadonlyFiles()) return Result.Failed("read-only: " + status.readonlyFiles.joinToString { it.name })

        WriteCommandAction.writeCommandAction(project).withName(commandName).withGlobalUndo().run<RuntimeException> {
            for ((document, splices) in work) {
                splices.sortedByDescending { it.start }.forEach { document.replaceString(it.start, it.end, it.text) }
            }
        }
        work.keys.forEach(fdm::saveDocument) // the engine and dotnet restore read the disk
        return Result.Applied
    }

    fun findLocal(path: String): VirtualFile? {
        val system = FileUtil.toSystemIndependentName(path)
        val lfs = LocalFileSystem.getInstance()
        return lfs.findFileByPath(system) ?: lfs.refreshAndFindFileByPath(system)
    }

    private val LOG = logger<EditApplier>()
}
