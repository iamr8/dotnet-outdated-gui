package com.github.iamr8.dotnetoutdated

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import java.awt.Component

/**
 * Sends this plugin's errors to Sentry when the user clicks "Report" in the IDE error dialog.
 * Nothing is transmitted unless the user explicitly reports. Runs off the EDT.
 */
class PluginErrorReportSubmitter : ErrorReportSubmitter() {

    override fun getReportActionText(): String = "Report to the Plugin Author (Sentry)"

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val throwable = events.firstOrNull()?.throwable
        val report = buildString {
            events.forEach { event ->
                event.message?.let { appendLine(it) }
                appendLine(event.throwableText)
                appendLine()
            }
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val ok = SentryReporter.report(throwable, report, additionalInfo)
            val status = if (ok) {
                SubmittedReportInfo.SubmissionStatus.NEW_ISSUE
            } else {
                SubmittedReportInfo.SubmissionStatus.FAILED
            }
            ApplicationManager.getApplication().invokeLater {
                consumer.consume(SubmittedReportInfo(null, if (ok) "Reported to Sentry" else "Report failed", status))
            }
        }
        return true
    }
}
