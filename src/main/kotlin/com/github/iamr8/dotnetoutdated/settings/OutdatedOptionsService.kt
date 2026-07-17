package com.github.iamr8.dotnetoutdated.settings

import com.github.iamr8.dotnetoutdated.cli.OutdatedOptions
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Persists [OutdatedOptions] per project (serialized to `.idea/nuget-extended.xml`). */
@Service(Service.Level.PROJECT)
@State(name = "NuGetExtendedOptions", storages = [Storage("nuget-extended.xml")])
class OutdatedOptionsService : PersistentStateComponent<OutdatedOptions> {
    private var state = OutdatedOptions()

    override fun getState(): OutdatedOptions = state
    override fun loadState(loaded: OutdatedOptions) {
        state = loaded
    }

    var options: OutdatedOptions
        get() = state
        set(value) {
            state = value
        }

    companion object {
        fun getInstance(project: Project): OutdatedOptionsService = project.service()
    }
}
