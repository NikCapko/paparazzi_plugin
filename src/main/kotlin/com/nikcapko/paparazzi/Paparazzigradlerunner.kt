package com.nikcapko.paparazzi

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project

object PaparazziGradleRunner {

    /**
     * Запускает один конкретный тест.
     * Под капотом: ./gradlew :module:testDebugUnitTest --tests "Fqn.method"
     */
    fun runTest(project: Project, test: PaparazziTest) {
        run(
            project = project,
            tests = listOf(test),
            task = verifyTask(test.gradleModulePath),
            extraArgs = "--tests \"${test.fqClassName}.${test.methodName}[*]\""
        )
    }

    fun runTests(project: Project, tests: List<PaparazziTest>) {
        tests.groupBy { it.gradleModulePath }.forEach { (_, moduleTests) ->
            run(
                project = project,
                tests = moduleTests,
                task = verifyTask(moduleTests.first().gradleModulePath)
                // ❗ без --tests
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

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = project.basePath
            taskNames = listOf(":verifyPaparazziGmsDebug")  // один таск для всех тестов
            vmOptions = ""
            scriptParameters = ""  // если нужны extraArgs, можно добавить
            externalSystemIdString = "GRADLE"
        }

        ExternalSystemUtil.runTask(
            settings,
            DefaultRunExecutor.EXECUTOR_ID,
            project,
            ProjectSystemId("GRADLE"),
            object : TaskCallback {
                override fun onSuccess() {
                    allTests.forEach { registry.setStatus(it, TestStatus.PASSED) }
                }

                override fun onFailure() {
                    allTests.forEach { registry.setStatus(it, TestStatus.FAILED) }
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
                task = recordTask(moduleTests.first().gradleModulePath)
            )
        }
    }

    // ------------------------------------------------------------------

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
                    tests.forEach { registry.setStatus(it, TestStatus.PASSED) }
                }

                override fun onFailure() {
                    tests.forEach { registry.setStatus(it, TestStatus.FAILED) }
                }
            },
            ProgressExecutionMode.IN_BACKGROUND_ASYNC,
            false
        )
    }

    private fun verifyTask(module: String) =
        "$module:verifyPaparazziGmsDebug"

    private fun recordTask(module: String) =
        "$module:recordPaparazziGmsDebug"
}
