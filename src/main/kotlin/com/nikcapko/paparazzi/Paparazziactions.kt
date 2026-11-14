package com.nikcapko.paparazzi

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Container
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode

// ---------------------------------------------------------------------------
// Базовый класс: достаёт выбранные тесты из Tool Window
// ---------------------------------------------------------------------------

abstract class BasePaparazziAction : AnAction() {

    protected fun selectedTests(e: AnActionEvent): List<PaparazziTest> {
        // Ищем дерево в компонентах Tool Window
        val tree = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
            ?.let { findTree(it as? Container) }
            ?: return emptyList()

        return tree.selectionPaths
            ?.mapNotNull { path ->
                (path.lastPathComponent as? DefaultMutableTreeNode)
                    ?.userObject as? PaparazziTest
            }
            ?: emptyList()
    }

    private fun findTree(container: Container?): Tree? {
        container ?: return null
        for (comp in container.components) {
            if (comp is Tree) return comp
            if (comp is Container) {
                val found = findTree(comp)
                if (found != null) return found
            }
        }
        return null
    }
}

// ---------------------------------------------------------------------------
// Запустить выбранный тест
// ---------------------------------------------------------------------------

class RunSelectedTestAction : BasePaparazziAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tests = selectedTests(e)
        if (tests.isEmpty()) return
        PaparazziGradleRunner.runTests(project, tests)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selectedTests(e).isNotEmpty()
    }
}

// ---------------------------------------------------------------------------
// Запустить все тесты
// ---------------------------------------------------------------------------

class RunAllTestsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PaparazziGradleRunner.runAll(project)
    }

    override fun update(e: AnActionEvent) {
        val registry = e.project?.getService(PaparazziTestRegistry::class.java)
        e.presentation.isEnabled = (registry?.allTests?.isNotEmpty()) == true
    }
}

// ---------------------------------------------------------------------------
// Re-record: перезаписать эталонные снимки
// ---------------------------------------------------------------------------

class ReRecordAction : BasePaparazziAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tests = selectedTests(e)
        if (tests.isEmpty()) return
        PaparazziGradleRunner.reRecord(project, tests)
    }
}

// ---------------------------------------------------------------------------
// Переиндексировать тесты вручную
// ---------------------------------------------------------------------------

class RefreshTestsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PaparazziIndexer.scheduleReindex(project)
    }
}

// ---------------------------------------------------------------------------
// Развернуть дерево тестов
// ---------------------------------------------------------------------------
class ExpandAllTestsAction : AnAction(
    /* text = */ "Expand All",
    /* description = */"Expand all nodes",
    /* icon = */AllIcons.Actions.Expandall,
) {

    override fun actionPerformed(e: AnActionEvent) {
        val tree = e.getData(PaparazziDataKeys.TREE) ?: return
        TreeUtil.expandAll(tree)
    }
}

// ---------------------------------------------------------------------------
// Свернуть дерево тестов
// ---------------------------------------------------------------------------
class CollapseAllTestsAction : AnAction(
    /* text = */ "Collapse All",
    /* description = */ "Collapse all nodes",
    /* icon = */ AllIcons.Actions.Collapseall,
) {

    override fun actionPerformed(e: AnActionEvent) {
        val tree = e.getData(PaparazziDataKeys.TREE) ?: return
        TreeUtil.collapseAll(tree, 0)
    }
}
