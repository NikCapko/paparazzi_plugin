package com.nikcapko.paparazzi

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "PaparazziSettings",
    storages = [Storage("paparazzi.xml")]
)
class PaparazziSettings : PersistentStateComponent<PaparazziSettings.State> {

    data class State(
        var taskSuffix: String = "Debug"
    )

    private var state = State()

    override fun getState() = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): PaparazziSettings =
            project.getService(PaparazziSettings::class.java)
    }
}
