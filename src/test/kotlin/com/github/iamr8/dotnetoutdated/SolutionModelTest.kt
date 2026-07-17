package com.github.iamr8.dotnetoutdated

import com.github.iamr8.dotnetoutdated.cli.SolutionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SolutionModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parsesClassicSlnProjectsAndSkipsSolutionFolders() {
        val sln = tmp.newFile("App.sln")
        sln.writeText(
            """
            Microsoft Visual Studio Solution File, Format Version 12.00
            Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "Core", "Core\Core.csproj", "{11111111-1111-1111-1111-111111111111}"
            EndProject
            Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "Api", "src\Api\Api.csproj", "{22222222-2222-2222-2222-222222222222}"
            EndProject
            Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "SolutionItems", "SolutionItems", "{33333333-3333-3333-3333-333333333333}"
            EndProject
            """.trimIndent(),
        )
        val projects = SolutionModel.parseProjects(sln)
        assertEquals(listOf("Api", "Core"), projects.map { it.name }) // sorted, folder skipped
        assertEquals(tmp.root.resolve("Core/Core.csproj").path, projects.first { it.name == "Core" }.path)
    }

    @Test
    fun parsesSlnxProjects() {
        val slnx = tmp.newFile("App.slnx")
        slnx.writeText(
            """
            <Solution>
              <Folder Name="/Solution Items/" />
              <Project Path="src/Web/Web.csproj" />
              <Project Path="test/Web.Tests/Web.Tests.fsproj" />
            </Solution>
            """.trimIndent(),
        )
        val projects = SolutionModel.parseProjects(slnx)
        assertEquals(listOf("Web", "Web.Tests"), projects.map { it.name })
    }

    @Test
    fun discoverPrefersSolutionMatchingProjectName() {
        tmp.newFile("Other.sln").writeText("")
        val wanted = tmp.newFile("Wanted.sln")
        wanted.writeText("""Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "P", "P\P.csproj", "{11111111-1111-1111-1111-111111111111}"""")
        val solution = SolutionModel.discover(tmp.root, preferredName = "Wanted")
        assertNotNull(solution)
        assertEquals("Wanted", solution!!.name)
        assertEquals(listOf("P"), solution.projects.map { it.name })
    }

    @Test
    fun flagsUnsupportedProjectTypesAndExcludesThem() {
        val sln = tmp.newFile("App.sln")
        sln.writeText(
            """
            Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "Core", "Core\Core.csproj", "{11111111-1111-1111-1111-111111111111}"
            EndProject
            Project("{D954291E-2A0B-460D-934E-DC6B0785DB48}") = "Shared", "Shared\Shared.shproj", "{22222222-2222-2222-2222-222222222222}"
            EndProject
            """.trimIndent(),
        )
        val solution = SolutionModel.discover(tmp.root, preferredName = "App")
        assertNotNull(solution)
        assertEquals(listOf("Core"), solution!!.projects.map { it.name }) // shproj excluded
        assert(solution.hasUnsupportedProjects) { "expected .shproj to flag unsupported projects" }
    }

    @Test
    fun discoverReturnsNullWhenNoSolution() {
        tmp.newFile("readme.txt")
        assertNull(SolutionModel.discover(tmp.root, preferredName = null))
    }
}
