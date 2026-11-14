package com.nikcapko.paparazzi

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.*
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Paths
import java.util.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath

// ---------------------------------------------------------------------------
// Factory: регистрируется в plugin.xml
// ---------------------------------------------------------------------------

class PaparazziToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        //val panel = PaparazziComposePanel(project)
        val panel = PaparazziPanel(project)
        val content = ContentFactory.getInstance()
            .createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

// ---------------------------------------------------------------------------
// Главная панель
// ---------------------------------------------------------------------------

class PaparazziPanel(private val project: Project) : JPanel(BorderLayout()), DataProvider {

    private val registry = project.getService(PaparazziTestRegistry::class.java)

    // ----- Tree -----
    private val root = DefaultMutableTreeNode("Paparazzi Tests")
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        cellRenderer = PaparazziTreeCellRenderer(registry)
        addTreeSelectionListener { onSelectionChanged() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigateToSelected()
            }
        })
    }

    // ----- Preview Panels -----
    private val referencePanel = JPanel(BorderLayout())
    private val deltaPanel = JPanel(BorderLayout())

    private var scaleFactor = 0.5  // коэффициент масштаба по умолчанию (50%)

    // ----- Search -----
    private var filterText = ""

    // 🆕 Состояние фильтрации по статусу
    private var showOnlyFailed = false

    private val searchField = SearchTextField().apply {
        addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onFilterChanged()
            override fun removeUpdate(e: DocumentEvent?) = onFilterChanged()
            override fun changedUpdate(e: DocumentEvent?) = onFilterChanged()
        })
    }

    // 🆕 Кнопка-переключатель "Только упавшие"
    private val filterFailedButton = JToggleButton().apply {
        isSelected = false
        icon = AllIcons.General.Filter
        toolTipText = "Show only failed tests"
        text = "Failed Only" // Добавляем текст для наглядности
        addActionListener {
            showOnlyFailed = isSelected
            // Меняем иконку для наглядности
            icon = if (showOnlyFailed) AllIcons.RunConfigurations.TestFailed else AllIcons.General.Filter
            rebuildTree()
        }
    }

    private val testNodes = mutableMapOf<PaparazziTest, DefaultMutableTreeNode>()
    private val classNodes = mutableMapOf<String, DefaultMutableTreeNode>()
    private val moduleNodes = mutableMapOf<String, DefaultMutableTreeNode>()

    init {
        registry.addListener { tests ->
            SwingUtilities.invokeLater {
                updateTreeStructure(tests)
            }
        }

        registry.addStatusListener { test ->
            SwingUtilities.invokeLater {
                // 🆕 Если включен фильтр, перестраиваем дерево целиком,
                // чтобы скрыть исправленный тест или показать новый упавший.
                // Иначе просто обновляем иконку существующего узла.
                if (showOnlyFailed) {
                    rebuildTree()
                } else {
                    updateNode(test)
                }
            }
        }

        val toolbar = createToolbar()

        // 🆕 Панель для поиска и кнопки фильтра
        val topFilterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(searchField)
            add(filterFailedButton)
        }

        val leftPanel = JPanel(BorderLayout()).apply {
            add(topFilterPanel, BorderLayout.NORTH) // 🆕 Используем новую панель
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }

        val previewTabs = JBTabbedPane().apply {
            addTab("Reference", referencePanel)
            addTab("Delta", deltaPanel)
        }

        val splitter = JBSplitter(false, 0.4f).apply {
            firstComponent = leftPanel
            secondComponent = previewTabs
        }

        add(toolbar.component, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        val scaleSlider = JSlider(25, 100, (scaleFactor * 100).toInt())
        scaleSlider.majorTickSpacing = 25
        scaleSlider.minorTickSpacing = 5
        scaleSlider.paintTicks = true
        scaleSlider.paintLabels = true
        scaleSlider.addChangeListener {
            scaleFactor = scaleSlider.value / 100.0
            onSelectionChanged()
        }

        deltaPanel.isVisible = false

        // Привязываем popup menu к дереву
        val contextGroup = ActionManager.getInstance()
            .getAction("Paparazzi.ContextMenu") as ActionGroup
        val popupMenu = ActionManager.getInstance()
            .createActionPopupMenu("PaparazziContextMenu", contextGroup)
        tree.componentPopupMenu = popupMenu.component

        rebuildTree()
    }

    override fun getData(dataId: String): Any? {
        return when {
            PaparazziDataKeys.TREE.`is`(dataId) -> tree
            PaparazziDataKeys.SELECTED_TESTS.`is`(dataId) -> getSelectedTests()
            else -> null
        }
    }

    private fun getSelectedTests(): List<PaparazziTest> {
        return tree.selectionPaths
            ?.mapNotNull { path ->
                (path.lastPathComponent as? DefaultMutableTreeNode)
                    ?.userObject as? PaparazziTest
            }
            ?: emptyList()
    }

    // ------------------------------------------------------------------
    // Дерево
    // ------------------------------------------------------------------

    private fun updateTreeStructure(tests: List<PaparazziTest>) {

        val expandedPaths = TreeUtil.collectExpandedPaths(tree)
        val selectedPaths = tree.selectionPaths

        if (root.childCount == 0) {
            tests
                .sortedBy { it.gradleModulePath }
                .forEach { test ->

                    val moduleNode = moduleNodes.getOrPut(test.gradleModulePath) {
                        val node = DefaultMutableTreeNode(test.gradleModulePath)
                        root.add(node)
                        node
                    }

                    val classKey = "${test.gradleModulePath}:${test.fqClassName}"
                    val classNode = classNodes.getOrPut(classKey) {
                        val node = DefaultMutableTreeNode(test.fqClassName.substringAfterLast('.'))
                        moduleNode.add(node)
                        node
                    }

                    val testNode = DefaultMutableTreeNode(test)
                    testNodes[test] = testNode
                    classNode.add(testNode)
                }

            sortAll()
            treeModel.reload()
            TreeUtil.expandAll(tree)
            return
        }

        val existingTests = testNodes.keys.toMutableSet()
        val newTests = tests.toSet()

        // --- УДАЛЕНИЕ ---
        (existingTests - newTests).forEach { test ->
            val node = testNodes.remove(test) ?: return@forEach
            val classNode = node.parent as? DefaultMutableTreeNode ?: return@forEach
            treeModel.removeNodeFromParent(node)

            // если класс пуст → удаляем
            if (classNode.childCount == 0) {
                val moduleNode = classNode.parent as? DefaultMutableTreeNode
                classNodes.entries.removeIf { it.value == classNode }
                treeModel.removeNodeFromParent(classNode)

                // если модуль пуст → удаляем
                if (moduleNode != null && moduleNode.childCount == 0) {
                    moduleNodes.entries.removeIf { it.value == moduleNode }
                    treeModel.removeNodeFromParent(moduleNode)
                }
            }
        }

        // --- ДОБАВЛЕНИЕ ---
        (newTests - existingTests)
            .sortedBy { it.gradleModulePath }
            .forEach { test ->

                val moduleNode = moduleNodes.getOrPut(test.gradleModulePath) {
                    val node = DefaultMutableTreeNode(test.gradleModulePath)
                    treeModel.insertNodeInto(node, root, root.childCount)
                    node
                }

                val classKey = "${test.gradleModulePath}:${test.fqClassName}"
                val classNode = classNodes.getOrPut(classKey) {
                    val node = DefaultMutableTreeNode(test.fqClassName.substringAfterLast('.'))
                    treeModel.insertNodeInto(node, moduleNode, moduleNode.childCount)
                    node
                }

                val testNode = DefaultMutableTreeNode(test)
                testNodes[test] = testNode
                treeModel.insertNodeInto(testNode, classNode, classNode.childCount)
            }

        sortAll()
        TreeUtil.restoreExpandedPaths(tree, expandedPaths)
        tree.selectionPaths = selectedPaths
    }

    private fun sortAll() {
        // сортируем модули
        sortChildren(root)

        // сортируем классы и тесты
        moduleNodes.values.forEach { moduleNode ->
            sortChildren(moduleNode)

            moduleNode.children().toList().forEach { classNode ->
                classNode as DefaultMutableTreeNode
                sortChildren(classNode)
            }
        }

        treeModel.reload(root)
    }

    private fun sortChildren(parent: DefaultMutableTreeNode) {
        val children = parent.children().toList() as List<DefaultMutableTreeNode>

        val sorted = children.sortedBy {
            val obj = it.userObject
            when (obj) {
                is PaparazziTest -> obj.methodName
                else -> obj.toString()
            }
        }

        parent.removeAllChildren()
        sorted.forEach { parent.add(it) }
    }

    private fun <T> Enumeration<T>.toList(): List<T> =
        Collections.list(this)

    private fun updateNode(test: PaparazziTest) {
        val node = testNodes[test] ?: return
        treeModel.nodeChanged(node)
    }

    private fun rebuildTree() {
        val selectedTests = tree.selectionPaths
            ?.mapNotNull { path ->
                (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? PaparazziTest
            }
            ?.toSet() ?: emptySet()

        root.removeAllChildren()

        // 🆕 Двойная фильтрация: по тексту И по статусу
        val tests = registry.allTests.filter { test ->
            val matchesText = if (filterText.isEmpty()) true else test.matchesFilter(filterText)
            val matchesStatus = if (showOnlyFailed) registry.getStatus(test) == TestStatus.FAILED else true

            matchesText && matchesStatus
        }

        // groupBy автоматически исключит модули и классы, в которых не осталось тестов после фильтрации!
        tests.groupBy { it.gradleModulePath }.forEach { (module, moduleTests) ->
            val moduleNode = DefaultMutableTreeNode(module)
            moduleTests.groupBy { it.fqClassName }.forEach { (fqClass, classTests) ->
                val classNode = DefaultMutableTreeNode(fqClass.substringAfterLast('.'))
                classTests.forEach { test ->
                    classNode.add(DefaultMutableTreeNode(test))
                }
                moduleNode.add(classNode)
            }
            root.add(moduleNode)
        }

        treeModel.reload()

        // Если включен фильтр "Только упавшие", имеет смысл сразу развернуть всё, чтобы ошибки были видны
        if (showOnlyFailed) {
            TreeUtil.expandAll(tree)
        } else {
            TreeUtil.expandAll(tree) // Или можно сохранить состояние раскрытия, как было раньше
        }

        val pathsToSelect = mutableListOf<TreePath>()
        selectedTests.forEach { test ->
            findPathForTest(test)?.let { pathsToSelect.add(it) }
        }

        if (pathsToSelect.isNotEmpty()) {
            tree.selectionPaths = pathsToSelect.toTypedArray()
            pathsToSelect.forEach { tree.scrollPathToVisible(it) }
        }
    }

    /** Ищем TreePath для конкретного теста */
    private fun findPathForTest(test: PaparazziTest): TreePath? {
        val rootNode = treeModel.root as? DefaultMutableTreeNode ?: return null

        fun search(node: DefaultMutableTreeNode): TreePath? {
            if (node.userObject == test) return TreePath(node.path)
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as DefaultMutableTreeNode
                search(child)?.let { return it }
            }
            return null
        }

        return search(rootNode)
    }

    private fun onFilterChanged() {
        filterText = searchField.text.trim()
        rebuildTree()
    }

    private fun PaparazziTest.matchesFilter(filter: String): Boolean {
        if (filter.isEmpty()) return true
        val q = filter.lowercase()
        return methodName.lowercase().contains(q) || fqClassName.lowercase().contains(q)
    }

    // ------------------------------------------------------------------
    // Превью
    // ------------------------------------------------------------------

    private fun onSelectionChanged() {
        referencePanel.removeAll()
        referencePanel.repaint()
        deltaPanel.removeAll()
        deltaPanel.repaint()

        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val test = node.userObject as? PaparazziTest ?: return
        val projectRoot = Paths.get(project.basePath ?: return)

        val refs = test.referenceSnapshots(projectRoot)
        if (refs.isNotEmpty()) {
            val images = refs.map { it.first }
            val labels = refs.map { it.second }

            referencePanel.removeAll()
            referencePanel.add(createColumnedImagePanel(images, labels, scaleFactor))
            referencePanel.revalidate()
            referencePanel.repaint()
        } else {
            referencePanel.removeAll()
            referencePanel.revalidate()
            referencePanel.repaint()
        }

        if (registry.getStatus(test) == TestStatus.FAILED) {
            deltaPanel.removeAll()
            val diffs = test.loadFailureDiffs(projectRoot)

            if (diffs.isNotEmpty()) {
                val images = diffs.map { it.first }
                val labels = diffs.map { it.second }

                deltaPanel.add(createScrollableImagePanelWithLabels(images, labels, scaleFactor))
                deltaPanel.isVisible = true
            } else {
                deltaPanel.add(JLabel("No diff images found", SwingConstants.CENTER))
                deltaPanel.isVisible = true
            }
        } else {
            deltaPanel.removeAll()
            deltaPanel.isVisible = false
        }

        deltaPanel.revalidate()
        deltaPanel.repaint()
    }

    private fun createColumnedImagePanel(
        images: List<BufferedImage>,
        labels: List<String>,
        scale: Double,
    ): JScrollPane {

        val container = JPanel(FlowLayout(FlowLayout.LEFT, 10, 10))

        // Группируем по индексу [0], [1], ...
        val grouped = mutableMapOf<Int, MutableList<Pair<BufferedImage, String>>>()

        // Разбиваем по индексу в квадратных скобках
        images.zip(labels).forEach { (img, lbl) ->
            val match = Regex("""\[(\d+)]""").find(lbl)
            val index = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            grouped.computeIfAbsent(index) { mutableListOf() }.add(img to lbl)
        }

        // Сортируем колонки по индексу
        grouped.toSortedMap().forEach { (_, items) ->
            val columnPanel = JPanel()
            columnPanel.layout = BoxLayout(columnPanel, BoxLayout.Y_AXIS)
            columnPanel.alignmentY = TOP_ALIGNMENT

            // ---- Картинки (вертикально) для этой колонки ----
            items.forEach { (img, lbl) ->
                val imgPanel = JPanel()
                imgPanel.layout = BoxLayout(imgPanel, BoxLayout.Y_AXIS)
                imgPanel.alignmentX = LEFT_ALIGNMENT

                val w = (img.width * scale).toInt()
                val h = (img.height * scale).toInt()

                val textLabel = createLabelWithWrappedText(lbl, w)
                textLabel.alignmentX = LEFT_ALIGNMENT
                imgPanel.add(textLabel)

                val imageLabel = JLabel(ImageIcon(img.getScaledInstance(w, h, BufferedImage.SCALE_SMOOTH)))
                imageLabel.alignmentX = LEFT_ALIGNMENT
                imgPanel.add(imageLabel)

                imgPanel.add(Box.createVerticalStrut(10))
                columnPanel.add(imgPanel)
            }

            container.add(columnPanel)
        }

        val scrollPane = JScrollPane(container)
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.preferredSize = Dimension(1200, 500)
        scrollPane.addMouseWheelListener { e ->
            if (e.isControlDown) {
                val delta = if (e.preciseWheelRotation < 0) 1.1 else 0.9
                scaleFactor = (scaleFactor * delta).coerceIn(0.1, 3.0)

                onSelectionChanged()
                e.consume()
            }
        }

        return scrollPane
    }

    private fun createLabelWithWrappedText(text: String, maxWidth: Int): JLabel {
        // Разбиваем текст каждые 30 символов или по maxWidth
        val wrappedText = text.chunked(30).joinToString("<br>") // <br> для HTML-переноса
        val htmlText = "<html><div style='text-align:center; max-width:${maxWidth}px;'>$wrappedText</div></html>"
        return JLabel(htmlText, SwingConstants.CENTER)
    }

    private fun createScrollableImagePanelWithLabels(
        images: List<BufferedImage>,
        labels: List<String>,
        scale: Double
    ): JScrollPane {
        // Контейнер для всех картинок — вертикально
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.alignmentX = LEFT_ALIGNMENT

        images.forEachIndexed { idx, img ->
            // Панель для одного скрина + подписи
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            panel.alignmentX = LEFT_ALIGNMENT
            panel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5) // отступы

            val w = (img.width * scale).toInt()
            val h = (img.height * scale).toInt()

            val text = labels.getOrNull(idx) ?: "Screenshot"
            val textLabel = JLabel(text, SwingConstants.CENTER)
            textLabel.alignmentX = CENTER_ALIGNMENT
            panel.add(textLabel)
            panel.add(Box.createVerticalStrut(5)) // расстояние между надписью и картинкой

            // Картинка
            val imageLabel = JLabel(ImageIcon(img.getScaledInstance(w, h, BufferedImage.SCALE_SMOOTH)))
            imageLabel.alignmentX = CENTER_ALIGNMENT
            panel.add(imageLabel)

            container.add(panel)
            container.add(Box.createVerticalStrut(10)) // расстояние между скриншотами
        }

        val scrollPane = JScrollPane(container)
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.preferredSize = Dimension(1200, 400)

        return scrollPane
    }

    // ------------------------------------------------------------------
    // Навигация к тесту
    // ------------------------------------------------------------------

    private fun navigateToSelected() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val test = node.userObject as? PaparazziTest ?: return
        val vFile = LocalFileSystem.getInstance().findFileByNioFile(test.sourceFile) ?: return
        OpenFileDescriptor(project, vFile, test.lineNumber - 1, 0).navigate(true)
    }

    // ------------------------------------------------------------------
    // Тулбар
    // ------------------------------------------------------------------

    private fun createToolbar(): ActionToolbar {
        val group = ActionManager.getInstance()
            .getAction("Paparazzi.ToolbarActions") as DefaultActionGroup
        return ActionManager.getInstance()
            .createActionToolbar("PaparazziToolbar", group, true)
            .also { it.targetComponent = this }
    }
}

// ---------------------------------------------------------------------------
// Рендерер ячеек дерева — показывает иконку статуса
// ---------------------------------------------------------------------------

class PaparazziTreeCellRenderer(
    private val registry: PaparazziTestRegistry
) : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode ?: return

        when (val obj = node.userObject) {
            is PaparazziTest -> {
                val status = registry.getStatus(obj)
                icon = when (status) {
                    TestStatus.PASSED -> AllIcons.RunConfigurations.TestPassed
                    TestStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
                    TestStatus.RUNNING -> AnimatedIcon.Default()
                    TestStatus.RECORDED -> AllIcons.Actions.Refresh
                    TestStatus.UNKNOWN -> AllIcons.RunConfigurations.TestNotRan
                }
                append(obj.displayName)
                if (status == TestStatus.FAILED) {
                    append("  failed", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
            }

            is String -> {
                // Проверяем, это модуль или класс
                val isModule = node.parent == node.root()

                if (isModule) {
                    // Для модуля показываем счётчики
                    icon = AllIcons.Nodes.Module
                    append(obj)

                    // Подсчитываем тесты и ошибки в этом модуле
                    val (totalTests, failedTests) = countTestsInModule(node)

                    if (totalTests > 0) {
                        append("  ")
                        append("($totalTests tests", SimpleTextAttributes.GRAYED_ATTRIBUTES)

                        if (failedTests > 0) {
                            append(", ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                            append("$failedTests failed", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        }

                        append(")", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                } else {
                    // Для класса показываем только иконку и имя
                    icon = AllIcons.Nodes.TestGroup
                    append(obj)
                }
            }
        }
    }

    /**
     * Подсчитывает общее количество тестов и количество упавших тестов в модуле
     */
    private fun countTestsInModule(moduleNode: DefaultMutableTreeNode): Pair<Int, Int> {
        var totalTests = 0
        var failedTests = 0

        // Проходим по всем дочерним узлам (классам)
        for (i in 0 until moduleNode.childCount) {
            val classNode = moduleNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue

            // Проходим по всем тестам в классе
            for (j in 0 until classNode.childCount) {
                val testNode = classNode.getChildAt(j) as? DefaultMutableTreeNode ?: continue
                val test = testNode.userObject as? PaparazziTest ?: continue

                totalTests++
                if (registry.getStatus(test) == TestStatus.FAILED) {
                    failedTests++
                }
            }
        }

        return totalTests to failedTests
    }

    private fun DefaultMutableTreeNode.root(): TreeNode {
        var n: TreeNode = this
        while (n.parent != null) n = n.parent
        return n
    }
}
