package com.nikcapko.paparazzi

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.datatransfer.StringSelection
import java.nio.file.Paths

/**
 * Базовый класс для действий контекстного меню.
 * Получает выбранные тесты через DataKey.
 */
abstract class BasePaparazziContextAction : AnAction() {

    protected fun selectedTests(e: AnActionEvent): List<PaparazziTest> {
        return e.getData(PaparazziDataKeys.SELECTED_TESTS) ?: emptyList()
    }
}

// ---------------------------------------------------------------------------
// Открыть исходный файл теста
// ---------------------------------------------------------------------------
class OpenSourceAction : BasePaparazziContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tests = selectedTests(e)
        if (tests.isEmpty()) return

        // Открываем первый выбранный тест
        val test = tests.first()
        val vFile = LocalFileSystem.getInstance().findFileByNioFile(test.sourceFile) ?: return
        OpenFileDescriptor(project, vFile, test.lineNumber - 1, 0).navigate(true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = selectedTests(e).isNotEmpty()
    }
}

// ---------------------------------------------------------------------------
// Открыть reference snapshot в IDE
// ---------------------------------------------------------------------------
class OpenReferenceSnapshotAction : BasePaparazziContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tests = selectedTests(e)
        if (tests.isEmpty()) return

        val test = tests.first()
        val projectRoot = Paths.get(project.basePath ?: return)
        val refs = test.referenceSnapshots(projectRoot)
        if (refs.isEmpty()) return

        // Открываем первый snapshot
        val imageFile = refs.first().first
        // Snapshot-файлы хранятся в src/test/snapshots/images, находим их через VFS
        val baseName = "${test.fqClassName.replace('.', '_')}_${test.className}_${test.methodName.replace(" ", "_")}"
        val moduleDir = projectRoot.resolve(test.gradleModulePath.removePrefix(":").replace(':', '/'))
        val snapshotPath = moduleDir.resolve("src/test/snapshots/images")
            .toFile()
            .listFiles { _, name -> name.startsWith(baseName) && name.endsWith(".png") }
            ?.firstOrNull()
            ?.toPath() ?: return

        val vFile = LocalFileSystem.getInstance().findFileByNioFile(snapshotPath) ?: return
        OpenFileDescriptor(project, vFile).navigate(true)
    }

    override fun update(e: AnActionEvent) {
        val tests = selectedTests(e)
        e.presentation.isEnabledAndVisible = tests.isNotEmpty()
    }
}

// ---------------------------------------------------------------------------
// Открыть failure diff в IDE
// ---------------------------------------------------------------------------
class OpenFailureDiffAction : BasePaparazziContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tests = selectedTests(e)
        if (tests.isEmpty()) return

        val test = tests.first()
        val registry = project.getService(PaparazziTestRegistry::class.java)
        if (registry.getStatus(test) != TestStatus.FAILED) return

        val projectRoot = Paths.get(project.basePath ?: return)
        val diffs = test.loadFailureDiffs(projectRoot)
        if (diffs.isEmpty()) return

        val moduleDir = projectRoot.resolve(test.gradleModulePath.removePrefix(":").replace(':', '/'))
        val baseName = "${test.fqClassName.replace('.', '_')}_${test.className}_${test.methodName.replace(" ", "_")}"
        val failuresDir = moduleDir.resolve("build/paparazzi/failures")
        val diffFile = failuresDir.toFile()
            .listFiles { _, name -> name.startsWith("delta-") && name.contains(baseName) && name.endsWith(".png") }
            ?.firstOrNull()
            ?.toPath() ?: return

        val vFile = LocalFileSystem.getInstance().findFileByNioFile(diffFile) ?: return
        OpenFileDescriptor(project, vFile).navigate(true)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project ?: return
        val tests = selectedTests(e)
        val registry = project.getService(PaparazziTestRegistry::class.java)
        val hasFailed = tests.any { registry.getStatus(it) == TestStatus.FAILED }
        e.presentation.isEnabledAndVisible = hasFailed
    }
}

// ---------------------------------------------------------------------------
// Скопировать Gradle-команду в буфер обмена
// ---------------------------------------------------------------------------
class CopyGradleCommandAction : BasePaparazziContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tests = selectedTests(e)
        if (tests.isEmpty()) return

        val suffix = PaparazziSettings.getInstance(project).state.taskSuffix
        val command = buildString {
            append("./gradlew ")
            tests.groupBy { it.gradleModulePath }.forEach { (module, moduleTests) ->
                append(module)
                append(":verifyPaparazzi")
                append(suffix)
                moduleTests.forEach { test ->
                    append(" --tests \"")
                    append(test.fqClassName)
                    append(".")
                    append(test.methodName)
                    append("\"")
                }
                append(" ")
            }
        }.trim()

        CopyPasteManager.getInstance().setContents(StringSelection(command))
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = selectedTests(e).isNotEmpty()
    }
}

// ---------------------------------------------------------------------------
// Скопировать FQN теста
// ---------------------------------------------------------------------------
class CopyTestFqnAction : BasePaparazziContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val tests = selectedTests(e)
        if (tests.isEmpty()) return

        val text = tests.joinToString("\n") { "${it.fqClassName}.${it.methodName}" }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = selectedTests(e).isNotEmpty()
    }
}
