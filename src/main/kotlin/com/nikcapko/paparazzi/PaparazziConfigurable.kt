package com.nikcapko.paparazzi

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class PaparazziConfigurable(private val project: Project) : SearchableConfigurable {

    private lateinit var panel: JPanel
    private lateinit var suffixField: JTextField

    override fun getId(): String = "paparazzi.settings"

    override fun getDisplayName(): String = "Paparazzi"

    override fun createComponent(): JComponent {
        suffixField = JTextField()

        panel = JPanel(BorderLayout()).apply {
            val form = JPanel(GridLayout(1, 2, 8, 8))
            form.add(JLabel("Task suffix (e.g. Debug):"))
            form.add(suffixField)

            add(form, BorderLayout.NORTH)
        }

        return panel
    }

    override fun isModified(): Boolean {
        val settings = PaparazziSettings.getInstance(project)
        return suffixField.text != settings.state.taskSuffix
    }

    override fun apply() {
        val settings = PaparazziSettings.getInstance(project)
        settings.state.taskSuffix = suffixField.text.trim()
    }

    override fun reset() {
        val settings = PaparazziSettings.getInstance(project)
        suffixField.text = settings.state.taskSuffix
    }
}
