package com.github.arash.dotnetoutdated.parse

import com.github.arash.dotnetoutdated.model.ListPackagesReport
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/** Parses the JSON from `dotnet list package --format json`. Pure, no platform dependencies. */
object ListPackagesParser {
    private val gson = Gson()

    /**
     * @throws JsonSyntaxException if [json] is non-blank but not valid JSON.
     * Blank input returns an empty report.
     */
    @Throws(JsonSyntaxException::class)
    fun parse(json: String): ListPackagesReport {
        if (json.isBlank()) return ListPackagesReport()
        return gson.fromJson(json, ListPackagesReport::class.java) ?: ListPackagesReport()
    }
}
