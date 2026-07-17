package com.github.arash.dotnetoutdated

import com.intellij.openapi.application.ApplicationInfo
import io.sentry.Hub
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.SentryId
import java.util.Properties

/**
 * Sends opt-in error reports to Sentry using an isolated [Hub] — it does NOT call the global
 * `Sentry.init`, so it never touches the IDE's or other plugins' error handling, and installs no
 * uncaught-exception handler. Only invoked when the user clicks "Report" in the IDE error dialog.
 */
object SentryReporter {

    private const val RELEASE = "dotnet-outdated-gui@0.1.0"

    /** DSN baked in at build time from SENTRY_DSN; blank in local/dev builds → reporting disabled. */
    private val dsn: String by lazy {
        runCatching {
            SentryReporter::class.java.getResourceAsStream("/sentry.properties")?.use {
                Properties().apply { load(it) }.getProperty("dsn").orEmpty().trim()
            }.orEmpty()
        }.getOrDefault("")
    }

    private val hub: Hub by lazy {
        val options = SentryOptions().apply {
            dsn = this@SentryReporter.dsn
            release = RELEASE
            // Keep it self-contained and non-invasive inside the IDE.
            isEnableUncaughtExceptionHandler = false
            isEnableShutdownHook = false
            isAttachServerName = false // don't leak the machine hostname
            isSendDefaultPii = false
            environment = runCatching { ApplicationInfo.getInstance().build.asString() }.getOrDefault("unknown")
        }
        Hub(options)
    }

    /** @return true if the event was accepted for delivery. Blocking; call off the EDT. */
    fun report(throwable: Throwable?, report: String, comment: String?): Boolean = try {
        if (dsn.isBlank()) return false // reporting not configured in this build
        hub.configureScope { scope ->
            scope.level = SentryLevel.ERROR
            scope.setTag("ide", runCatching { ApplicationInfo.getInstance().fullApplicationName }.getOrDefault("Rider"))
            scope.setTag("plugin", "dotnet-outdated-gui")
            scope.setTag("os", System.getProperty("os.name") ?: "unknown")
            scope.setExtra("report", report.take(8000))
            if (!comment.isNullOrBlank()) scope.setExtra("comment", comment)
        }
        val id = if (throwable != null) hub.captureException(throwable) else hub.captureMessage(report)
        hub.flush(5000)
        id != SentryId.EMPTY_ID
    } catch (e: Throwable) {
        false
    }
}
