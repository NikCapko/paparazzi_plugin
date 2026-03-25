package com.nikcapko.paparazzi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.psi.PsiDocumentManager
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
        PaparazziIndexer.scheduleReindex(project)
    }
}

// ---------------------------------------------------------------------------
// Слушатель изменений файлов
// ---------------------------------------------------------------------------

class PaparazziFileListener : VirtualFileListener {

    override fun contentsChanged(event: VirtualFileEvent) {
        val file = event.file
        if (file.extension != "kt" && file.extension != "java") return

        // Ленивый поиск проекта через FileDocumentManager
        val doc = FileDocumentManager.getInstance().getDocument(file) ?: return
        val project = PsiDocumentManager.getInstance(
            ProjectLocator.getInstance()
                .guessProjectForFile(file) ?: return
        ).getPsiFile(doc)?.project ?: return

        PaparazziIndexer.scheduleReindex(project, debounceMs = 1500)
    }
}

// ---------------------------------------------------------------------------
// Сам индексер
// ---------------------------------------------------------------------------

object PaparazziIndexer {
    // debounce: не переиндексируем чаще раза в N мс
    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("PaparazziIndexer", 1)
    private var pendingTask: ScheduledFuture<*>? = null

    fun scheduleReindex(project: Project, debounceMs: Long = 0) {
        pendingTask?.cancel(false)
        pendingTask = executor.schedule({
            reindex(project)
        }, debounceMs, TimeUnit.MILLISECONDS)
    }

    fun reindex(project: Project) {
        val tests = ReadAction.compute<List<PaparazziTest>, Throwable> {
            scanProject(project)
        }
        project.getService(PaparazziTestRegistry::class.java).update(tests)
    }

    // -----------------------------------------------------------------

    private fun scanProject(project: Project): List<PaparazziTest> {
        val modules = ModuleManager.getInstance(project).modules
        val result = mutableListOf<PaparazziTest>()

        modules.forEach { module ->
            val scope = module.moduleContentScope

            // 1️⃣ Kotlin файлы
            val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)
                .filter { it.path.endsWith("Test.kt") }
            ktFiles.forEach { vFile ->
               // vFile.refresh(false, false) // на случай, если файл новый
                val psiFile = PsiManager.getInstance(project).findFile(vFile) as? KtFile ?: return@forEach
                psiFile.declarations.filterIsInstance<KtClass>().forEach { ktClass ->
                    if (hasPaparazziFieldKt(ktClass)) {
                        result += extractTests(ktClass, project)
                    }
                }
            }
        }
        return result
    }

    private fun KtNamedFunction.hasTestAnnotation(): Boolean {
        return annotationEntries.any { it.shortName?.asString() == "Test" }
    }

    /** Для Kotlin: аналог через KtClass */
    private fun hasPaparazziFieldKt(ktClass: KtClass): Boolean {
        return ktClass.getProperties().any { property ->

            // 1️⃣ Проверяем тип Paparazzi
            // 1️⃣ Проверяем тип через typeReference
            val isPaparazziType = property.typeReference?.text?.contains("Paparazzi") == true
                    // или через initializer
                    || property.initializer?.text?.contains("Paparazzi") == true

            if (!isPaparazziType) return@any false

            // 2️⃣ Проверяем аннотации
            val hasRuleAnnotation = property.annotationEntries.any { annotation ->
                val name = annotation.shortName?.asString()
                if (name != "Rule") return@any false

                val useSite = annotation.useSiteTarget?.getAnnotationUseSiteTarget()
                useSite == null || useSite.renderName == "get" || useSite.renderName == "field"
            }

            hasRuleAnnotation
        }
    }

    /** Извлекает @Test методы из класса */
    private fun extractTests(ktClass: KtClass, project: Project): List<PaparazziTest> {
        val modulePath = resolveGradleModulePath(ktClass, project)

        val psiFile = ktClass.containingFile
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)

        return ktClass.declarations
            .filterIsInstance<KtNamedFunction>()
            .filter { it.hasTestAnnotation() }
            .map { function ->

                val lineNumber = document
                    ?.getLineNumber(function.textOffset)
                    ?.plus(1) ?: 0

                PaparazziTest(
                    fqClassName = ktClass.fqName?.asString()
                        ?: ktClass.name
                        ?: "Unknown",
                    methodName = function.name ?: "unknown",
                    gradleModulePath = modulePath,
                    sourceFile = psiFile.virtualFile?.path?.let { Paths.get(it) }
                        ?: Paths.get(""),
                    lineNumber = lineNumber,
                    useParametrized = ktClass.annotationEntries.firstOrNull()?.text?.contains("Parameterized") ?: false,
                )
            }
    }

    /**
     * Пытается определить gradle-путь модуля по расположению build.gradle.kts.
     * Идёт вверх по дереву директорий от файла теста до тех пор,
     * пока не найдёт build.gradle[.kts] и не достигнет корня проекта.
     */
    private fun resolveGradleModulePath(ktClass: KtClass, project: Project): String {
        val projectRoot = project.basePath ?: return ":"

        val virtualFile = ktClass.containingFile.virtualFile ?: return ":"
        var dir = virtualFile.parent

        while (dir != null && dir.path != projectRoot) {
            if (
                dir.findChild("build.gradle.kts") != null ||
                dir.findChild("build.gradle") != null
            ) {
                val relative = dir.path
                    .removePrefix(projectRoot)
                    .replace('/', ':')

                return if (relative.startsWith(":")) relative else ":$relative"
            }
            dir = dir.parent
        }

        return ":"
    }
}
