package com.nikcapko.paparazzi

import com.intellij.openapi.actionSystem.DataKey
import javax.swing.JTree

object PaparazziDataKeys {
    val TREE: DataKey<JTree> = DataKey.create("PAPARAZZI_TREE")

    // DataKey для передачи выбранных тестов в actions
    val SELECTED_TESTS: DataKey<List<PaparazziTest>> = DataKey.create("PAPARAZZI_SELECTED_TESTS")
}