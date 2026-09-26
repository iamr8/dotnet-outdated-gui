package com.github.iamr8.nugetextended

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** plugin.xml ids that code looks up by string must equal [PluginText.NAME]. */
class PluginXmlTest {

    private val xml = javaClass.getResourceAsStream("/META-INF/plugin.xml")!!.use { stream ->
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).documentElement
    }

    private fun attr(tag: String, name: String): String =
        (xml.getElementsByTagName(tag).item(0) as Element).getAttribute(name)

    @Test
    fun namesMatchTheConstant() {
        assertEquals(PluginText.NAME, xml.getElementsByTagName("name").item(0).textContent.trim())
        assertEquals(PluginText.NAME, attr("toolWindow", "id"))
        assertEquals(PluginText.NAME, attr("notificationGroup", "id"))
        assertEquals(PluginText.NAME, attr("projectConfigurable", "displayName"))
    }

    @Test
    fun idAndSettingsStorageAreUnchanged() {
        assertEquals("com.github.iamr8.dotnetoutdated", xml.getElementsByTagName("id").item(0).textContent.trim())
        assertEquals("nuget.extended", attr("projectConfigurable", "id"))
    }

    @Test
    fun descriptionNoLongerNeedsTheCliTool() {
        val description = xml.getElementsByTagName("description").item(0).textContent
        assertFalse(description.contains("global tool"))
        // The repo URL (…/dotnet-outdated-gui) may stay; the old CLI tool may not.
        assertFalse(Regex("dotnet-outdated(?!-gui)").containsMatchIn(description))
    }
}
