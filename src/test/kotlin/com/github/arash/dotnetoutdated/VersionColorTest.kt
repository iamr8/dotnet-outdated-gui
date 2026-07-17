package com.github.arash.dotnetoutdated

import com.github.arash.dotnetoutdated.model.changedVersionSuffix
import org.junit.Assert.assertEquals
import org.junit.Test

/** Smart colorization splits the new version into an unchanged prefix and the changed suffix. */
class VersionColorTest {

    @Test
    fun minorBumpColorsFromMinorComponent() {
        assertEquals("4." to "3.0", changedVersionSuffix("4.2.1", "4.3.0"))
    }

    @Test
    fun majorBumpColorsWholeVersion() {
        assertEquals("" to "4.6.1", changedVersionSuffix("3.13.3", "4.6.1"))
    }

    @Test
    fun patchBumpColorsOnlyLastComponent() {
        assertEquals("13.0." to "4", changedVersionSuffix("13.0.3", "13.0.4"))
    }

    @Test
    fun prereleaseBumpColorsChangedTail() {
        assertEquals("0.1.0-beta.11" to "5", changedVersionSuffix("0.1.0-beta.112", "0.1.0-beta.115"))
    }
}
