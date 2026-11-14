package com.nikcapko.paparazzi

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory

object PaparazziGradleRunner {

    /**
     * Запускает выбранные тесты.
     * Под капотом: ./gradlew :module:verifyPaparazzi "Fqn.method"
     */
    fun runTests(project: Project, tests: List<PaparazziTest>) {
        tests.groupBy { it.gradleModulePath }.forEach { (_, moduleTests) ->
            run(
                project = project,
                tests = moduleTests,
                task = verifyTask(project, moduleTests.first().gradleModulePath)
            )
        }
    }

    /**
     * Запускает все переданные тесты.
     * Если они из разных модулей — группирует по модулю и запускает отдельные таски.
     */
    fun runAll(project: Project) {
        val registry = project.getService(PaparazziTestRegistry::class.java)
        val allTests = registry.allTests
        if (allTests.isEmpty()) return

        // Помечаем все тесты как RUNNING
        allTests.forEach { registry.setStatus(it, TestStatus.RUNNING) }

        val suffix = PaparazziSettings.getInstance(project).state.taskSuffix
        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = project.basePath
            taskNames = listOf("verifyPaparazzi$suffix")
            vmOptions = ""
            scriptParameters = ""
            externalSystemIdString = "GRADLE"
        }

        ExternalSystemUtil.runTask(
            settings,
            DefaultRunExecutor.EXECUTOR_ID,
            project,
            ProjectSystemId("GRADLE"),
            object : TaskCallback {
                override fun onSuccess() {
                    updateStatusesFromXml(project, allTests)
                }

                override fun onFailure() {
                    updateStatusesFromXml(project, allTests)
                }
            },
            ProgressExecutionMode.IN_BACKGROUND_ASYNC,
            false
        )
    }

    /**
     * Перезаписывает эталонные снимки.
     * -Dpaparazzi.record=true — официальный флаг Cash App Paparazzi.
     */
    fun reRecord(project: Project, tests: List<PaparazziTest>) {
        tests.groupBy { it.gradleModulePath }.forEach { (_, moduleTests) ->
            run(
                project = project,
                tests = moduleTests,
                task = recordTask(project, moduleTests.first().gradleModulePath)
            )
        }
    }

    private fun run(
        project: Project,
        tests: List<PaparazziTest>,
        task: String,
        extraArgs: String = "",
    ) {
        if (tests.isEmpty()) return

        val registry = project.getService(PaparazziTestRegistry::class.java)

        // статус RUNNING
        tests.forEach { registry.setStatus(it, TestStatus.RUNNING) }

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = project.basePath
            taskNames = listOf(task)
            scriptParameters = extraArgs
            externalSystemIdString = "GRADLE"
        }

        ExternalSystemUtil.runTask(
            settings,
            DefaultRunExecutor.EXECUTOR_ID,
            project,
            ProjectSystemId("GRADLE"),
            object : TaskCallback {
                override fun onSuccess() {
                    updateStatusesFromXml(project, tests)
                }

                override fun onFailure() {
                    updateStatusesFromXml(project, tests)
                }
            },
            ProgressExecutionMode.IN_BACKGROUND_ASYNC,
            false
        )
    }

    /**
     * Парсит XML-отчёты Gradle и обновляет статусы тестов.
     */
    private fun updateStatusesFromXml(project: Project, tests: List<PaparazziTest>) {
        val registry = project.getService(PaparazziTestRegistry::class.java)
        val projectRoot = Paths.get(project.basePath ?: return)
        val suffix = PaparazziSettings.getInstance(project).state.taskSuffix

        // Группируем тесты по модулю
        val testsByModule = tests.groupBy { it.gradleModulePath }

        testsByModule.forEach { (modulePath, moduleTests) ->
            val moduleDir = projectRoot.resolve(modulePath.removePrefix(":").replace(':', '/'))

            // Путь к отчётам с учётом суффикса
            val reportsDir = moduleDir.resolve("build/test-results/test${suffix}UnitTest")

            if (!Files.exists(reportsDir)) {
                // Если нет папки отчётов, все тесты UNKNOWN
                moduleTests.forEach { registry.setStatus(it, TestStatus.UNKNOWN) }
                return@forEach
            }

            // Группируем тесты по классу для эффективного поиска XML
            val testsByClass = moduleTests.groupBy { it.fqClassName }

            testsByClass.forEach { (fqClassName, classTests) ->
                val xmlFile = reportsDir.resolve("TEST-$fqClassName.xml")

                if (!Files.exists(xmlFile)) {
                    // Если XML-файл не найден, тесты UNKNOWN
                    classTests.forEach { registry.setStatus(it, TestStatus.UNKNOWN) }
                    return@forEach
                }

                try {
                    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile.toFile())
                    doc.documentElement.normalize()

                    val testCases = doc.getElementsByTagName("testcase")

                    classTests.forEach { test ->
                        var found = false

                        for (i in 0 until testCases.length) {
                            val node = testCases.item(i) as? Element ?: continue
                            val methodName = node.getAttribute("name")

                            // Проверяем совпадение имени метода
                            // Для параметризованных тестов имя может быть "methodName[0]", "methodName[1]"
                            val matches = if (test.useParametrized) {
                                methodName.startsWith(test.methodName)
                            } else {
                                methodName == test.methodName
                            }

                            if (matches) {
                                found = true
                                val failedNodes = node.getElementsByTagName("failure")
                                val skippedNodes = node.getElementsByTagName("skipped")

                                val status = when {
                                    failedNodes.length > 0 -> TestStatus.FAILED
                                    skippedNodes.length > 0 -> TestStatus.UNKNOWN
                                    else -> TestStatus.PASSED
                                }

                                registry.setStatus(test, status)
                                break
                            }
                        }

                        if (!found) {
                            registry.setStatus(test, TestStatus.UNKNOWN)
                        }
                    }
                } catch (e: Exception) {
                    // При ошибке парсинга все тесты UNKNOWN
                    classTests.forEach { registry.setStatus(it, TestStatus.UNKNOWN) }
                    e.printStackTrace()
                }
            }
        }
    }

    private fun verifyTask(project: Project, module: String): String {
        val suffix = PaparazziSettings.getInstance(project).state.taskSuffix
        return "$module:verifyPaparazzi$suffix"
    }

    private fun recordTask(project: Project, module: String): String {
        val suffix = PaparazziSettings.getInstance(project).state.taskSuffix
        return "$module:recordPaparazzi$suffix"
    }
}
