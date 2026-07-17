package com.github.arash.dotnetoutdated

import com.github.arash.dotnetoutdated.model.SeverityColor
import com.github.arash.dotnetoutdated.model.UpgradeSeverity
import com.github.arash.dotnetoutdated.model.isPrerelease
import com.github.arash.dotnetoutdated.model.severityColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeverityTest {

    @Test
    fun parsesSeverityCaseInsensitively() {
        assertEquals(UpgradeSeverity.MAJOR, UpgradeSeverity.from("Major"))
        assertEquals(UpgradeSeverity.MINOR, UpgradeSeverity.from("minor"))
        assertEquals(UpgradeSeverity.PATCH, UpgradeSeverity.from("PATCH"))
        assertEquals(UpgradeSeverity.NONE, UpgradeSeverity.from("None"))
        assertEquals(UpgradeSeverity.UNKNOWN, UpgradeSeverity.from(null))
        assertEquals(UpgradeSeverity.UNKNOWN, UpgradeSeverity.from("garbage"))
    }

    @Test
    fun mapsSeverityToLegendColor() {
        assertEquals(SeverityColor.RED, severityColor(UpgradeSeverity.MAJOR, "10.0.10"))
        assertEquals(SeverityColor.YELLOW, severityColor(UpgradeSeverity.MINOR, "10.0.10"))
        assertEquals(SeverityColor.GREEN, severityColor(UpgradeSeverity.PATCH, "10.0.10"))
        assertEquals(SeverityColor.NONE, severityColor(UpgradeSeverity.NONE, "10.0.10"))
    }

    @Test
    fun prereleaseIsAlwaysRed() {
        assertTrue(isPrerelease("0.9.0-beta.4"))
        assertFalse(isPrerelease("3.0.123"))
        // Even a "patch" bump is red when the target is a pre-release.
        assertEquals(SeverityColor.RED, severityColor(UpgradeSeverity.PATCH, "0.9.0-beta.4"))
    }
}
