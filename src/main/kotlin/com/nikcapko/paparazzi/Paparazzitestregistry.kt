package com.nikcapko.paparazzi

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.name

// ---------------------------------------------------------------------------
// Доменная модель
// ---------------------------------------------------------------------------

enum class TestStatus {
    UNKNOWN,    // ещё не запускался
    RUNNING,    // сейчас выполняется
    PASSED,     // последний запуск прошёл
    FAILED,     // последний запуск упал
    RECORDED    // только что перезаписан эталон
}

data class PaparazziTest(
    /** com.example.ui.ButtonTest */
    val fqClassName: String,
    /** myButtonLightTheme */
    val methodName: String,
    /** :feature:ui */
    val gradleModulePath: String,
    /** абсолютный путь к .kt/.java файлу */
    val sourceFile: Path,
    /** строка объявления метода */
    val lineNumber: Int,
    /** использование параметризации для запуска тестов */
    val useParametrized: Boolean,
) {
    val displayName: String get() = methodName
    val className: String get() = fqClassName.substringAfterLast('.')

    fun referenceSnapshots(projectRoot: Path): List<Pair<BufferedImage, String>> {
        val baseName = "${fqClassName.substringBeforeLast(".")}_${className}_${methodName.replace(" ", "_")}"

        val imagesDir = projectRoot
            .resolve(gradleModulePath.removePrefix(":").replace(':', '/'))
            .resolve("src/test/snapshots/images")
            .toFile()

        if (!imagesDir.exists() || !imagesDir.isDirectory) return emptyList()

        return imagesDir
            .listFiles { _, name ->
                name.startsWith(baseName) && name.endsWith(".png")
            }
            ?.sortedWith { file, file1 -> file.toPath().compareTo(file1.toPath()) }
            ?.mapNotNull {
                val img = ImageIO.read(it.toPath().toFile()) ?: return@mapNotNull null
                img to it.name.replace(baseName.substringBefore(methodName.replace(" ", "_")), "")
            }
            ?: emptyList()
    }

    fun loadFailureDiffs(projectRoot: Path): List<Pair<BufferedImage, String>> {
        val baseName = "${fqClassName.replace('.', '_')}_${methodName.replace(" ", "_")}"

        val failuresDir = projectRoot
            .resolve(gradleModulePath.removePrefix(":").replace(':', '/'))
            .resolve("build/paparazzi/failures")

        if (!Files.exists(failuresDir)) return emptyList()

        return Files.list(failuresDir)
            .filter { path ->
                val name = path.fileName.toString()
                name.startsWith("delta-") && name.contains(baseName) && name.endsWith(".png")
            }
            .toList()
            .mapNotNull { path ->
                val img = ImageIO.read(path.toFile()) ?: return@mapNotNull null
                img to path.name
                    .replace(baseName.substringBefore(methodName.replace(" ", "_")), "")
                    .replace("delta-", "")
            }
    }
}

// ---------------------------------------------------------------------------
// Реестр тестов (project-level service)
// ---------------------------------------------------------------------------

@Service(Service.Level.PROJECT)
class PaparazziTestRegistry {

    private val tests = mutableListOf<PaparazziTest>()
    private val statusMap = mutableMapOf<PaparazziTest, TestStatus>()

    private val listeners = mutableListOf<(List<PaparazziTest>) -> Unit>()
    private val statusListeners = mutableListOf<(PaparazziTest) -> Unit>()

    val allTests: List<PaparazziTest>
        get() = tests.sortedWith(
            compareBy(
                { it.gradleModulePath },
                { it.fqClassName },
                { it.methodName },
            )
        )

    fun update(newTests: List<PaparazziTest>) {
        tests.clear()
        tests.addAll(newTests)

        // сбрасываем статусы только для новых
        newTests.forEach {
            statusMap.putIfAbsent(it, TestStatus.UNKNOWN)
        }

        listeners.forEach { it(newTests) }
    }

    fun setStatus(test: PaparazziTest, status: TestStatus) {
        statusMap[test] = status
        statusListeners.forEach { it(test) }
    }

    fun getStatus(test: PaparazziTest): TestStatus =
        statusMap[test] ?: TestStatus.UNKNOWN

    fun addListener(listener: (List<PaparazziTest>) -> Unit) {
        listeners += listener
    }

    fun addStatusListener(listener: (PaparazziTest) -> Unit) {
        statusListeners += listener
    }
}
