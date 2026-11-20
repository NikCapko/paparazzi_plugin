package com.nikcapko.paparazzi

import com.intellij.openapi.components.Service
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

// ---------------------------------------------------------------------------
// Стабильный идентификатор теста (не меняется при сдвиге строк кода)
// ---------------------------------------------------------------------------
data class PaparazziTestId(
    val gradleModulePath: String,
    val fqClassName: String,
    val methodName: String
)

// ---------------------------------------------------------------------------
// Доменная модель теста
// ---------------------------------------------------------------------------
data class PaparazziTest(
    val fqClassName: String,
    val methodName: String,
    val gradleModulePath: String,
    val sourceFile: Path,
    val lineNumber: Int,
    val useParametrized: Boolean,
) {
    // Стабильный ключ для хранения в Map и сравнения
    val id: PaparazziTestId get() = PaparazziTestId(gradleModulePath, fqClassName, methodName)

    val displayName: String get() = methodName
    val className: String get() = fqClassName.substringAfterLast('.')
    val packageName: String get() = fqClassName.substringBeforeLast('.')

    /**
     * Формирует базовое имя snapshot-файла в формате Paparazzi:
     * {package}_{ClassName}_{methodName}
     *
     * Пример: com.example.ui_HomeTest_homeScreenTest
     */
    private val snapshotBaseName: String
        get() = "${packageName}_${className}_${methodName.replace(" ", "_")}"

    fun referenceSnapshots(projectRoot: Path): List<Pair<BufferedImage, String>> {
        val imagesDir = projectRoot
            .resolve(gradleModulePath.removePrefix(":").replace(':', '/'))
            .resolve("src/test/snapshots/images")
            .toFile()

        if (!imagesDir.exists() || !imagesDir.isDirectory) return emptyList()

        return imagesDir
            .listFiles { _, name ->
                name.startsWith(snapshotBaseName) && name.endsWith(".png")
            }
            ?.sortedWith { file, file1 -> file.toPath().compareTo(file1.toPath()) }
            ?.mapNotNull {
                val img = ImageIO.read(it.toPath().toFile()) ?: return@mapNotNull null
                // Извлекаем суффикс (например, "[0]" для параметризованных тестов)
                val suffix = it.name.removePrefix(snapshotBaseName).removeSuffix(".png")
                img to suffix
            }
            ?: emptyList()
    }

    fun loadFailureDiffs(projectRoot: Path): List<Pair<BufferedImage, String>> {
        val failuresDir = projectRoot
            .resolve(gradleModulePath.removePrefix(":").replace(':', '/'))
            .resolve("build/paparazzi/failures")

        if (!Files.exists(failuresDir)) return emptyList()

        return Files.list(failuresDir)
            .filter { path ->
                val name = path.fileName.toString()
                name.startsWith("delta-") && name.contains(snapshotBaseName) && name.endsWith(".png")
            }
            .toList()
            .mapNotNull { path ->
                val img = ImageIO.read(path.toFile()) ?: return@mapNotNull null
                val suffix = path.name
                    .removePrefix("delta-")
                    .removePrefix(snapshotBaseName)
                    .removeSuffix(".png")
                img to suffix
            }
    }
}

// ---------------------------------------------------------------------------
// Реестр тестов (project-level service)
// ---------------------------------------------------------------------------
@Service(Service.Level.PROJECT)
class PaparazziTestRegistry {

    private val tests = mutableListOf<PaparazziTest>()

    // ИСПОЛЬЗУЕМ СТАБИЛЬНЫЙ ID В КАЧЕСТВЕ КЛЮЧА
    private val statusMap = mutableMapOf<PaparazziTestId, TestStatus>()

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
        val newIds = newTests.map { it.id }.toSet()

        // 1. Удаляем статусы тестов, которые больше не существуют (были удалены или переименованы)
        statusMap.keys.retainAll(newIds)

        // 2. Обновляем список тестов
        tests.clear()
        tests.addAll(newTests)

        // 3. Инициализируем статус UNKNOWN для новых тестов, если его ещё нет
        newTests.forEach { test ->
            statusMap.putIfAbsent(test.id, TestStatus.UNKNOWN)
        }

        listeners.forEach { it(newTests) }
    }

    fun setStatus(test: PaparazziTest, status: TestStatus) {
        statusMap[test.id] = status
        statusListeners.forEach { it(test) }
    }

    fun getStatus(test: PaparazziTest): TestStatus =
        statusMap[test.id] ?: TestStatus.UNKNOWN

    fun addListener(listener: (List<PaparazziTest>) -> Unit) {
        listeners += listener
    }

    fun addStatusListener(listener: (PaparazziTest) -> Unit) {
        statusListeners += listener
    }
}
