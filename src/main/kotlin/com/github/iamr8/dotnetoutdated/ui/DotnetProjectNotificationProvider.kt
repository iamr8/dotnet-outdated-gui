package com.github.iamr8.dotnetoutdated.ui

import com.github.iamr8.dotnetoutdated.PluginText
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.function.Function
import javax.swing.JComponent

/**
 * When a .NET project file or central-package-management file is opened, offers the
 * "NuGet (Extended)" tool for checking outdated NuGet packages. Dismissible.
 */
class DotnetProjectNotificationProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (!isProjectFile(file)) return null
        if (PropertiesComponent.getInstance().getBoolean(DISMISSED_KEY, false)) return null

        return Function { _ ->
            EditorNotificationPanel().apply {
                text = "Check outdated NuGet packages with ${PluginText.NAME}."
                createActionLabel("Open ${PluginText.NAME}") {
                    ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
                }
                createActionLabel("Don't show again") {
                    PropertiesComponent.getInstance().setValue(DISMISSED_KEY, true)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
            }
        }
    }

    private fun isProjectFile(file: VirtualFile): Boolean {
        val name = file.name
        return name.endsWith(".csproj", true) ||
            name.endsWith(".fsproj", true) ||
            name.endsWith(".vbproj", true) ||
            name.equals("Directory.Packages.props", true)
    }

    companion object {
        private const val TOOL_WINDOW_ID = PluginText.NAME
        private const val DISMISSED_KEY = "dotnetOutdatedGui.suggestion.dismissed"
    }
}
