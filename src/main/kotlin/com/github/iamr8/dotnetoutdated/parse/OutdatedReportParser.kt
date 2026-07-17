package com.github.iamr8.dotnetoutdated.parse

import com.github.iamr8.dotnetoutdated.model.OutdatedReport
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/** Parses the JSON report emitted by `dotnet outdated`. Pure, no platform dependencies. */
object OutdatedReportParser {
    private val gson = Gson()

    /**
     * @throws JsonSyntaxException if [json] is non-blank but not valid JSON.
     * Blank input returns an empty report.
     */
    @Throws(JsonSyntaxException::class)
    fun parse(json: String): OutdatedReport {
        if (json.isBlank()) return OutdatedReport()
        return gson.fromJson(json, OutdatedReport::class.java) ?: OutdatedReport()
    }
}
