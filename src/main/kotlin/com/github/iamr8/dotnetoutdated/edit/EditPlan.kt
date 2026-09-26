package com.github.iamr8.dotnetoutdated.edit

import com.github.iamr8.dotnetoutdated.engine.Edit
import java.io.File

/** One file's text, or why it can't be read. */
sealed class FileText {
    data class Ok(val text: String) : FileText()
    data class Unreadable(val reason: String) : FileText()
}

sealed class Planned<out K> {
    data class Ok<K>(val splices: Map<K, List<Splice>>) : Planned<K>()

    /** [cause]: set only for an unexpected exception while planning (a possible plugin bug). */
    data class Failed(val reason: String, val cause: Throwable? = null) : Planned<Nothing>()
}

/** Pure: locates every edit in every file first. Any failure gives no splices at all, so nothing is written. */
object EditPlan {

    /**
     * [resolve] maps a raw path to its file key. Edits are grouped by that key, not by the raw path,
     * so two spellings of one file combine into a single plan instead of overwriting each other.
     */
    fun <K : Any> plan(edits: List<Edit>, resolve: (String) -> K?, name: (K) -> String, read: (K) -> FileText): Planned<K> {
        val perFile = LinkedHashMap<K, MutableList<Edit>>()
        for ((path, fileEdits) in edits.groupBy { it.file }) {
            val file = resolve(path) ?: return Planned.Failed("${File(path).name} was not found - re-scan")
            perFile.getOrPut(file) { ArrayList() }.addAll(fileEdits)
        }

        val splices = LinkedHashMap<K, List<Splice>>()
        for ((file, fileEdits) in perFile) {
            val text = when (val read = read(file)) {
                is FileText.Unreadable -> return Planned.Failed(read.reason)
                is FileText.Ok -> read.text
            }
            when (val planned = EditText.plan(text, fileEdits)) {
                is EditResult.Failed -> return Planned.Failed("${name(file)}: ${planned.reason}", planned.cause)
                is EditResult.Ok -> splices[file] = planned.splices
            }
        }
        return Planned.Ok(splices)
    }
}
