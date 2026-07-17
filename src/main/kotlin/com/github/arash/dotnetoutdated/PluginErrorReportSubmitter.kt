package com.github.arash.dotnetoutdated

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.datatransfer.StringSelection

/**
 * Enables the IDE's error-report dialog for this plugin: errors logged via `Logger.error` show up
 * in the JetBrains error reporter (with a full, selectable stack trace) instead of the tool
 * window's status bar. "Report" copies the assembled report to the clipboard so it can be pasted
 * into an issue.
 */
class PluginErrorReportSubmitter : ErrorReportSubmitter() {

    override fun getReportActionText(): String = "Copy Error Report to Clipboard"

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val report = buildString {
            events.forEach { event ->
                event.message?.let { appendLine(it) }
                appendLine(event.throwableText)
                appendLine()
            }
            additionalInfo?.let { appendLine("User comment: $it") }
        }
        CopyPasteManager.getInstance().setContents(StringSelection(report))
        consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
        return true
    }
}
