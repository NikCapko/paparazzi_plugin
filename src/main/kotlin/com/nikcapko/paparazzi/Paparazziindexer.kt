package com.nikcapko.paparazzi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.nio.file.Paths
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Запуск при старте проекта
// ---------------------------------------------------------------------------

class PaparazziStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // При старте тоже показываем прогресс, но можно сделать его невидимым, если мешает
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Indexing Paparazzi Tests", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                PaparazziIndexer.reindexWithProgress(project, indicator)
            }
        })
    }
}

// ---------------------------------------------------------------------------
// Сам индексер
// ---------------------------------------------------------------------------

object PaparazziIndexer {

    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("PaparazziIndexer", 1)
    private var pendingTask: ScheduledFuture<*>? = null

    /**
     * Планирует переиндексацию с дебаунсом
     */
    fun scheduleReindex(project: Project) {
        pendingTask?.cancel(true)
        pendingTask = executor.schedule({
            ProgressManager.getInstance().run(object : com.intellij.openapi.progress.Task.Backgroundable(
                project, "Indexing Paparazzi Tests", true
            ) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    reindexWithProgress(project, indicator)
                }
            })
        }, 300, TimeUnit.MILLISECONDS)
    }

    /**
     * Основной метод индексации с поддержкой ProgressIndicator
     */
    fun reindexWithProgress(project: Project, indicator: ProgressIndicator) {
        indicator.text = "Preparing to scan..."

        // ReadAction.compute безопасно выполняет чтение PSI из фонового потока
        // и блокирует метод run(), пока не закончит, что держит прогресс-бар открытым.
        val tests = ReadAction.compute<List<PaparazziTest>, Exception> {
            val modules = ModuleManager.getInstance(project).modules
            val totalModules = modules.size
            var currentModule = 0
            val result = mutableListOf<PaparazziTest>()

            for (module in modules) {
                if (indicator.isCanceled) {
                    break // Пользователь нажал Cancel
                }

                currentModule++
                indicator.text = "Scanning module: ${module.name} ($currentModule/$totalModules)"
                indicator.fraction = currentModule.toDouble() / totalModules // Заполнение полоски 0.0 -> 1.0

                val scope = module.moduleContentScope
                val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)
                    .filter { it.path.endsWith("Test.kt") }

                for (vFile in ktFiles) {
                    if (indicator.isCanceled) break

                    PsiManager.getInstance(project).findFile(vFile)?.let { psi ->
                        result.addAll(extractTestsFromFile(psi))
                    }
                }
            }
            result
        }

        // Если задачу не отменили, обновляем UI на главном потоке (EDT)
        if (!indicator.isCanceled) {
            ApplicationManager.getApplication().invokeLater {
                val registry = project.getService(PaparazziTestRegistry::class.java)
                registry.update(tests)
            }
        }
    }

    /**
     * Извлекаем PaparazziTest из одного KtFile
     */
    private fun extractTestsFromFile(ktFile: PsiFile): List<PaparazziTest> {
        if (ktFile !is KtFile) return emptyList()
        val result = mutableListOf<PaparazziTest>()

        ktFile.declarations.filterIsInstance<KtClass>().forEach { ktClass ->
            if (!hasPaparazziFieldKt(ktClass)) return@forEach

            val psiFile = ktClass.containingFile
            val document = PsiDocumentManager.getInstance(ktFile.project).getDocument(psiFile)

            ktClass.declarations
                .filterIsInstance<KtNamedFunction>()
                .filter { it.hasTestAnnotation() }
                .forEach { function ->
                    val lineNumber = document?.getLineNumber(function.textOffset)?.plus(1) ?: 0
                    result += PaparazziTest(
                        fqClassName = ktClass.fqName?.asString() ?: ktClass.name ?: "Unknown",
                        methodName = function.name ?: "unknown",
                        gradleModulePath = resolveGradleModulePath(ktClass),
                        sourceFile = psiFile.virtualFile?.toNioPath() ?: Paths.get(""),
                        lineNumber = lineNumber,
                        useParametrized = ktClass.annotationEntries.firstOrNull()?.text?.contains("Parameterized")
                            ?: false
                    )
                }
        }

        return result
    }

    private fun KtNamedFunction.hasTestAnnotation(): Boolean =
        annotationEntries.any { it.shortName?.asString() == "Test" }

    private fun hasPaparazziFieldKt(ktClass: KtClass): Boolean {
        return ktClass.superTypeListEntries.any { entry ->
            entry.typeAsUserType?.referencedName == "PaparazziBaseTest" ||
            entry.typeAsUserType?.referencedName == "BaseScreenshotTest" ||
            entry.typeAsUserType?.referencedName == "ScreenshotTest"
        }
    }

    private fun resolveGradleModulePath(ktClass: KtClass): String {
        val projectRoot = ktClass.project.basePath ?: return ":"
        val virtualFile = ktClass.containingFile.virtualFile ?: return ":"
        var dir = virtualFile.parent
        while (dir != null && dir.path != projectRoot) {
            if (dir.findChild("build.gradle.kts") != null || dir.findChild("build.gradle") != null) {
                val relative = dir.path.removePrefix(projectRoot).replace('/', ':')
                return if (relative.startsWith(":")) relative else ":$relative"
            }
            dir = dir.parent
        }
        return ":"
    }
}
