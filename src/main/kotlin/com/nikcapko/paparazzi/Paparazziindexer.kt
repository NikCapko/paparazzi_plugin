package com.nikcapko.paparazzi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.nio.file.Paths
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object PaparazziIndexer {

    private const val PAPARAZZI_FQ_NAME = "app.cash.paparazzi.Paparazzi"
    private const val JUNIT_RULE_FQ_NAME = "org.junit.Rule"
    private const val JUNIT_TEST_FQ_NAME = "org.junit.Test"

    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("PaparazziIndexer", 1)
    private var pendingTask: ScheduledFuture<*>? = null

    fun scheduleReindex(project: Project, debounceMs: Long = 0) {
        pendingTask?.cancel(false)
        pendingTask = executor.schedule({
            reindex(project)
        }, debounceMs, TimeUnit.MILLISECONDS)
    }

    fun reindex(project: Project) {
        // Ensure read action covers the whole scanning process
        val tests = ReadAction.compute<List<PaparazziTest>, Throwable> {
            scanProject(project)
        }
        project.getService(PaparazziTestRegistry::class.java).update(tests)
    }

    private fun scanProject(project: Project): List<PaparazziTest> {
        val modules = ModuleManager.getInstance(project).modules
        val result = mutableListOf<PaparazziTest>()

        modules.forEach { module ->
            // FIX: Use standard GlobalSearchScope instead of K1-specific moduleContentScope
            val scope = GlobalSearchScope.moduleScope(module)

            val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)
                .filter { it.path.endsWith("Test.kt") }

            ktFiles.forEach { vFile ->
                val psiFile = PsiManager.getInstance(project).findFile(vFile) as? KtFile ?: return@forEach
                if (!psiFile.isValid) return@forEach

                psiFile.declarations.filterIsInstance<KtClass>().forEach { ktClass ->
                    if (!ktClass.isValid) return@forEach
                    if (hasPaparazziFieldKt(ktClass)) {
                        result += extractTests(ktClass, project)
                    }
                }
            }
        }

        return result
    }

    private fun hasPaparazziFieldKt(ktClass: KtClass): Boolean {
        return try {
            analyze(ktClass) {
                val classSymbol = ktClass.symbol as? KaClassSymbol ?: return@analyze false

                classSymbol.memberScope
                    .callables
                    .filterIsInstance<KaPropertySymbol>()
                    .any { property ->
                        isPaparazziProperty(property)
                    }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun KaSession.isPaparazziProperty(property: KaPropertySymbol): Boolean {
        return try {
            // FIX: Check Fully Qualified Name for type
            val returnTypeClassFqName = property.returnType.expandedSymbol
                ?.let { it as? KaClassSymbol }
                ?.classId?.asSingleFqName()?.asString()

            val isCorrectType = returnTypeClassFqName == PAPARAZZI_FQ_NAME

            // FIX: Check Fully Qualified Name for Annotation
            val hasRuleAnnotation = property.annotations.any { annotation ->
                annotation.classId?.asSingleFqName()?.asString() == JUNIT_RULE_FQ_NAME
            }

            isCorrectType && hasRuleAnnotation
        } catch (e: Exception) {
            false
        }
    }

    private fun extractTests(ktClass: KtClass, project: Project): List<PaparazziTest> {
        val modulePath = resolveGradleModulePath(ktClass, project)
        val psiFile = ktClass.containingFile
        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile)

        return try {
            analyze(ktClass) {
                val classSymbol = ktClass.symbol as? KaClassSymbol ?: return@analyze emptyList()

                val fqName = classSymbol.classId?.asSingleFqName()?.asString().orEmpty()

                ktClass.declarations
                    .filterIsInstance<KtNamedFunction>()
                    .filter { it.isValid && hasTestAnnotation(it) }
                    .map { function ->
                        val lineNumber = document?.getLineNumber(function.textOffset)?.plus(1) ?: 0

                        PaparazziTest(
                            fqClassName = fqName,
                            methodName = function.name ?: "unknown",
                            gradleModulePath = modulePath,
                            sourceFile = psiFile.virtualFile?.path?.let { Paths.get(it) } ?: Paths.get(""),
                            lineNumber = lineNumber
                        )
                    }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun KaSession.hasTestAnnotation(function: KtNamedFunction): Boolean {
        return try {
            val functionSymbol = function.symbol as? KaNamedFunctionSymbol ?: return false
            functionSymbol.annotations.any { annotation ->
                annotation.classId?.asSingleFqName()?.asString() == JUNIT_TEST_FQ_NAME
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveGradleModulePath(ktClass: KtClass, project: Project): String {
        val projectRoot = project.basePath ?: return ":"
        val vFile = ktClass.containingFile.virtualFile ?: return ":"
        var dir: VirtualFile? = vFile.parent

        while (dir != null) {
            if (dir.path == projectRoot) break

            // Check for Gradle files
            if (dir.findChild("build.gradle.kts") != null || dir.findChild("build.gradle") != null) {
                val relative = dir.path.removePrefix(projectRoot).replace('\\', '/').replace('/', ':')
                return if (relative.startsWith(":")) relative else ":$relative"
            }
            dir = dir.parent
        }

        return ":"
    }
}