package com.nikcapko.paparazzi

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise
import org.jetbrains.kotlin.idea.KotlinFileType
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
        PaparazziIndexer.scheduleReindex(project)
    }
}

// ---------------------------------------------------------------------------
// Сам индексер
// ---------------------------------------------------------------------------

object PaparazziIndexer {

    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("PaparazziIndexer", 1)
    private var pendingTask: ScheduledFuture<*>? = null
    private var readAction: CancellablePromise<List<PaparazziTest>>? = null

    /**
     * Планирует переиндексацию с дебаунсом
     */
    fun scheduleReindex(project: Project) {
        pendingTask?.cancel(true)
        pendingTask = executor.schedule(
            {
                reindexNonBlocking(project)
            },
            300,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Основной non-blocking индекс
     */
    private fun reindexNonBlocking(project: Project) {
        val registry = project.getService(PaparazziTestRegistry::class.java)

        ReadAction.nonBlocking<List<PaparazziTest>> {
            ModuleManager.getInstance(project).modules.flatMap { module ->
                val scope = module.moduleContentScope

                // Находим только Kotlin тестовые файлы
                val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)
                    .filter { it.path.endsWith("Test.kt") }

                ktFiles.flatMap { vFile ->
                    PsiManager.getInstance(project).findFile(vFile)?.let { psi ->
                        extractTestsFromFile(psi)
                    } ?: emptyList()
                }
            }
        }
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { tests ->
                registry.update(tests)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun reindexNonBlocking2(project: Project) {
        val registry = project.getService(PaparazziTestRegistry::class.java)

        ReadAction.nonBlocking<List<PaparazziTest>> {
            val result = mutableListOf<PaparazziTest>()

            ModuleManager.getInstance(project).modules.flatMap { module ->
                val scope = module.moduleContentScope

                FileTypeIndex
                    .processFiles(KotlinFileType.INSTANCE, { vFile ->
                        if (vFile.name.endsWith("Test.kt")) {
                            vFile.findPsiFile(project)
                                ?.let { extractTestsFromFile(it) }
                                ?.let { result.addAll(it) }
                        }
                        true
                    }, scope)
                result
            }
        }
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { tests ->
                registry.update(tests)
                pendingTask?.cancel(true)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
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
            entry.typeAsUserType?.referencedName == "PaparazziBaseTest"
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
