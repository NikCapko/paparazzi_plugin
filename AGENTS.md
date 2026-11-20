# Agents Instructions

This file provides instructions for AI agents working with the Paparazzi IntelliJ Plugin codebase.

## Project Overview

**Paparazzi IntelliJ Plugin** is an IntelliJ IDEA plugin that simplifies working with [Paparazzi](https://github.com/cashapp/paparazzi) — a snapshot testing tool for Android UI components by Cash App.

The plugin provides:
- Visual management of snapshot tests directly in the IDE
- Point-by-point test execution via Gradle `--tests` flag
- Snapshot preview and diff visualization
- One-click re-recording of golden images
- Real-time test status tracking

## Technology Stack

- **Language:** Kotlin
- **Platform:** IntelliJ Platform SDK (2025.3+)
- **Build System:** Gradle with Kotlin DSL
- **Plugin Framework:** `org.jetbrains.intellij.platform` version 2.10.5
- **Dependencies:**
  - `com.intellij.java` (bundled)
  - `org.jetbrains.kotlin` (bundled)

## Architecture

### Core Components

#### 1. Indexing Layer (`PaparazziIndexer.kt`)
- Scans Kotlin test files at project startup and on manual refresh
- Uses `ReadAction.nonBlocking()` for non-blocking PSI access
- Extracts test metadata: FQCN, method name, module path, line number
- Detects tests by checking inheritance from `PaparazziBaseTest`
- Implements debounced re-indexing (300ms delay)

#### 2. Data Model (`PaparazziTest.kt`)
```kotlin
data class PaparazziTestId(
    val gradleModulePath: String,
    val fqClassName: String,
    val methodName: String
)

data class PaparazziTest(
    val fqClassName: String,
    val methodName: String,
    val gradleModulePath: String,
    val sourceFile: Path,
    val lineNumber: Int,
    val useParametrized: Boolean
) {
    val id: PaparazziTestId // Stable identifier for status tracking
}
```

**Key Design Decision:** `PaparazziTestId` is used as the map key for status storage. This ensures statuses persist even when `lineNumber` changes due to code edits above the test.

#### 3. State Management (`PaparazziTestRegistry.kt`)
- Project-level service (`@Service(Service.Level.PROJECT)`)
- Stores test list and their statuses (`UNKNOWN`, `RUNNING`, `PASSED`, `FAILED`, `RECORDED`)
- Implements observer pattern with listeners for UI updates
- Cleans up stale statuses on re-index (`statusMap.keys.retainAll(newIds)`)

#### 4. Gradle Integration (`PaparazziGradleRunner.kt`)
- Uses `ExternalSystemUtil.runTask()` for Gradle execution
- Passes `--tests` flag for point-by-point test execution
- Parses XML test reports from `build/test-results/test{Suffix}UnitTest/`
- Updates individual test statuses based on XML parsing results

**Important:** Always parse XML reports after test execution. Never assign the same status to all tests in a batch.

#### 5. UI Layer (`PaparazziToolWindowFactory.kt`)
- Implements `ToolWindowFactory` for bottom panel
- Uses Swing `JTree` with custom `ColoredTreeCellRenderer`
- Implements `DataProvider` for action context propagation
- Provides snapshot preview with zoom (Ctrl+mouse wheel)
- Supports filtering by text and status ("Failed Only" toggle)

### IntelliJ Platform Patterns

#### DataKey Propagation
```kotlin
object PaparazziDataKeys {
    val TREE: DataKey<JTree> = DataKey.create("PAPARAZZI_TREE")
    val SELECTED_TESTS: DataKey<List<PaparazziTest>> = DataKey.create("PAPARAZZI_SELECTED_TESTS")
}

// In PaparazziPanel (implements DataProvider):
override fun getData(dataId: String): Any? = when {
    PaparazziDataKeys.TREE.`is`(dataId) -> tree
    PaparazziDataKeys.SELECTED_TESTS.`is`(dataId) -> getSelectedTests()
    else -> null
}

// In Actions:
val tests = e.getData(PaparazziDataKeys.SELECTED_TESTS)
```

#### Background Tasks with Progress
```kotlin
ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Indexing...", true) {
    override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = false
        for (module in modules) {
            if (indicator.isCanceled) break
            indicator.text = "Scanning: ${module.name}"
            indicator.fraction = current.toDouble() / total
            // ... work ...
        }
    }
})
```

**Critical:** Use `ReadAction.compute()` inside `Task.Backgroundable.run()`, NOT `ReadAction.nonBlocking().submit()`. The latter returns immediately and closes the progress bar prematurely.

#### Settings Persistence
```kotlin
@Service(Service.Level.PROJECT)
@State(name = "PaparazziSettings", storages = [Storage("paparazzi.xml")])
class PaparazziSettings : PersistentStateComponent<PaparazziSettings.State> {
    data class State(var taskSuffix: String = "Debug")
    // ...
}
```

## Code Conventions

### File Naming
- Use PascalCase for all Kotlin files: `PaparazziIndexer.kt`, not `Paparazziindexer.kt`
- One public class per file
- Group related actions in single files: `PaparazziActions.kt`, `PaparazziContextMenuActions.kt`

### Package Structure
```
com.nikcapko.paparazzi/
├── PaparazziIndexer.kt          # Indexing logic
├── PaparazziTestRegistry.kt     # State management
├── PaparazziGradleRunner.kt     # Gradle execution
├── PaparazziToolWindowFactory.kt # UI components
├── PaparazziActions.kt          # Toolbar actions
├── PaparazziContextMenuActions.kt # Context menu actions
├── PaparazziSettings.kt         # Settings service
└── PaparazziConfigurable.kt     # Settings UI
```

### Error Handling
- Always check for null project: `val project = e.project ?: return`
- Use `try-catch` for XML parsing and file operations
- Set status to `UNKNOWN` on errors, never crash the plugin

### Thread Safety
- UI updates must be on EDT: `SwingUtilities.invokeLater { ... }` or `ApplicationManager.getApplication().invokeLater { ... }`
- PSI access must be in `ReadAction`
- File system operations can be on background threads

## Snapshot File Naming

Paparazzi saves snapshots with this pattern:
```
{package}_{ClassName}_{methodName}.png
```

Where:
- `{package}` — full package name **with dots preserved** (e.g., `com.example.ui`)
- `{ClassName}` — simple class name (e.g., `HomeTest`)
- `{methodName}` — test method name with spaces replaced by underscores

**Important:** Only the dot between package and ClassName is replaced with underscore. Dots inside the package name remain.

### Example

```kotlin
package com.example.ui

class HomeTest : PaparazziBaseTest() {
    @Test fun homeScreenTest() { ... }
}
```

Snapshot file: `com.example.ui_HomeTest_homeScreenTest.png`

**Implementation:**

```kotlin
val packageName = fqClassName.substringBeforeLast('.')  // "com.example.ui"
val className = fqClassName.substringAfterLast('.')     // "HomeTest"
val snapshotBaseName = "${packageName}_${className}_${methodName.replace(" ", "_")}"
// Result: "com.example.ui_HomeTest_homeScreenTest"
```

### Parameterized Tests

For parameterized tests, files have suffixes like `[0]`, `[1]`:
```
com.example.ui_HomeTest_homeScreenTest[0].png
com.example.ui_HomeTest_homeScreenTest[1].png
```

### Failure Diffs

Paparazzi saves diff images with `delta-` prefix:
```
delta-com.example.ui_HomeTest_homeScreenTest.png
delta-com.example.ui_HomeTest_homeScreenTest[0].png
```

## Gradle Task Naming

Tasks follow the pattern:
```
:{module}:verifyPaparazzi{Suffix}
:{module}:recordPaparazzi{Suffix}
```

Where `{Suffix}` is configurable (default: `Debug`).

Examples:
- `:feature:ui:verifyPaparazziDebug`
- `:feature:ui:recordPaparazziGmsDebug`

Test reports are located at:
```
{module}/build/test-results/test{Suffix}UnitTest/TEST-{fqClassName}.xml
```

## Common Pitfalls

### 1. Progress Bar Closes Immediately
**Wrong:**
```kotlin
override fun run(indicator: ProgressIndicator) {
    ReadAction.nonBlocking { ... }.submit(executor) // Returns immediately!
}
```

**Correct:**
```kotlin
override fun run(indicator: ProgressIndicator) {
    ReadAction.compute { ... } // Blocks until complete
}
```

### 2. Status Lost After Line Number Change
**Wrong:** Using `PaparazziTest` as map key (includes `lineNumber`).

**Correct:** Use `PaparazziTestId` (only stable fields: module, class, method).

### 3. All Tests Get Same Status
**Wrong:**
```kotlin
override fun onSuccess() {
    tests.forEach { registry.setStatus(it, TestStatus.PASSED) }
}
```

**Correct:** Parse XML reports and set individual statuses:
```kotlin
override fun onSuccess() {
    updateStatusesFromXml(project, tests)
}
```

### 4. Snapshot Files Not Found

**Wrong:**
```kotlin
val baseName = "${fqClassName.replace('.', '_')}_${methodName}"
// Results in: "com_example_ui_HomeTest_method" (all dots replaced!)
```

**Correct:**
```kotlin
val baseName = "${fqClassName.substringBeforeLast(".")}_${fqClassName.substringAfterLast(".")}_${methodName}"
// Results in: "com.example.ui_HomeTest_method" (only package-class separator replaced)
```

### 5. Regex for Parameterized Tests
**Wrong:**
```kotlin
Regex("[(d+)]") // Character class, not what we want
```

**Correct:**
```kotlin
Regex("""\[(\d+)]""") // Matches [0], [1], etc.
```

## Testing the Plugin

### Run IDE with Plugin
```bash
./gradlew runIde
```

### Build Distribution
```bash
./gradlew buildPlugin
# Output: build/distributions/paparazzi_plugin-*.zip
```

### Manual Testing Checklist
1. Open project with Paparazzi tests
2. Verify tests appear in tool window
3. Run single test → check XML parsing updates status
4. Run all tests → verify individual statuses
5. Re-record snapshot → verify file updated
6. Add line above test → verify status persists
7. Filter "Failed Only" → verify tree updates
8. Right-click test → verify context menu works

## Known Limitations

1. **Kotlin Only:** Indexing works only for `.kt` files
2. **Base Class Detection:** Only detects classes inheriting from `PaparazziBaseTest`
3. **Single Build Variant:** Uses one suffix for all modules
4. **No Java Support:** Java test files are not indexed

## Future Improvements

Potential areas for enhancement:
- [ ] Support for Java test files
- [ ] Configurable base class names
- [ ] Side-by-side image comparison
- [ ] Auto-detection of build variant from Gradle model
- [ ] HTML report export
- [ ] CI/CD integration helpers

## Resources

- [IntelliJ Platform SDK Documentation](https://plugins.jetbrains.com/docs/intellij/)
- [Kotlin UI DSL](https://plugins.jetbrains.com/docs/intellij/kotlin-ui-dsl.html)
- [Paparazzi GitHub](https://github.com/cashapp/paparazzi)
- [Plugin Development Basics](https://plugins.jetbrains.com/docs/intellij/basics.html)

## Contact

For questions about the codebase:
- GitHub Issues: https://github.com/NikCapko/paparazzi_plugin/issues
- Email: nik.capko@mail.ru
```
