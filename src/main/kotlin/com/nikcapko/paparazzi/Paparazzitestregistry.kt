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

    /** Путь к эталонному PNG: src/test/snapshots/images/<fqClass>_<method>.png */
//    fun referenceSnapshotPath(projectRoot: Path): Path =
//        projectRoot
//            .resolve(gradleModulePath.removePrefix(":").replace(':', '/'))
//            .resolve("src/test/snapshots/images")
//            .resolve("${fqClassName}_${methodName}.png")

    fun referenceSnapshotPath(projectRoot: Path): List<Path> {
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
            ?.map { it.toPath() }
            ?: emptyList()
    }

    fun loadFailureDiffs(projectRoot: Path): List<Pair<BufferedImage, String>> {
        val baseName = "${fqClassName.substringBeforeLast(".")}_${className}_${methodName.replace(" ", "_")}"

        val failuresDir = projectRoot
            .resolve(gradleModulePath.removePrefix(":").replace(':', '/'))
            .resolve("build/paparazzi/failures")

        if (!Files.exists(failuresDir)) return emptyList()

        return Files.list(failuresDir)
            .filter { path ->
                val name = path.fileName.toString()
                name.startsWith("delta-") && name.endsWith(".png")
            }
            .toList()
            .mapNotNull { path ->
                val img = ImageIO.read(path.toFile()) ?: return@mapNotNull null
                img to path.name
                    .replace(baseName.substringBefore(methodName.replace(" ", "_")), "")
                    .replace("delta-", "")
            }
    }

    fun referenceSnapshotTitles(projectRoot: Path): List<String> {
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
            ?.map { it.name.replace(baseName.substringBefore(methodName.replace(" ", "_")), "") }
            .orEmpty()
    }

    /** Путь к последнему сгенерированному PNG в build/ */
//    fun actualSnapshotPath(projectRoot: Path): Path =
//        projectRoot
//            .resolve(gradleModulePath.removePrefix(":").replace(':', '/'))
//            .resolve("build/paparazzi/images")
//            .resolve("${fqClassName}_${methodName}.png")

    fun actualSnapshotPath(projectRoot: Path): List<Path> {
        val baseName = "${fqClassName.substringBeforeLast(".")}_${className}_${methodName.replace(" ", "_")}"

        val imagesDir = projectRoot
            .resolve(gradleModulePath.removePrefix(":").replace(':', '/'))
            .resolve("build/paparazzi/failures")
            .toFile()

        if (!imagesDir.exists() || !imagesDir.isDirectory) return emptyList()

        return imagesDir.listFiles { _, name ->
            name.startsWith(baseName) && name.endsWith(".png")
        }?.map { it.toPath() } ?: emptyList()
    }
}

// ---------------------------------------------------------------------------
// Реестр тестов (project-level service)
// ---------------------------------------------------------------------------

@Service(Service.Level.PROJECT)
class PaparazziTestRegistry(private val project: Project) {

    /** Все найденные тесты, ключ = fqClassName#methodName */
    private val _tests = mutableMapOf<String, PaparazziTest>()

    /** Статус выполнения */
    private val _statuses = mutableMapOf<String, TestStatus>()

    /** Слушатели изменений (Tool Window подписывается для перерисовки) */
    private val listeners = mutableListOf<() -> Unit>()

    val allTests: List<PaparazziTest>
        get() = _tests.values
            .sortedWith(
                compareBy(
                    { it.gradleModulePath },
                    { it.fqClassName },
                    { it.methodName },
                )
            )

    fun update(tests: List<PaparazziTest>) {
        _tests.clear()
        tests.forEach { _tests[key(it)] = it }
        notifyListeners()
    }

    fun setStatus(test: PaparazziTest, status: TestStatus) {
        _statuses[key(test)] = status
        notifyListeners()
    }

    fun getStatus(test: PaparazziTest): TestStatus =
        _statuses[key(test)] ?: TestStatus.UNKNOWN

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun key(t: PaparazziTest) = "${t.fqClassName}#${t.methodName}"
    private fun notifyListeners() = listeners.forEach { it() }
}
