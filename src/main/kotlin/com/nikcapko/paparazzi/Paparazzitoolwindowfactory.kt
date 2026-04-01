package com.nikcapko.paparazzi

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO
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

class PaparazziPanel(private val project: Project) : JPanel(BorderLayout()) {

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
    private val searchField = SearchTextField().apply {
        addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onFilterChanged()
            override fun removeUpdate(e: DocumentEvent?) = onFilterChanged()
            override fun changedUpdate(e: DocumentEvent?) = onFilterChanged()
        })
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
                updateNode(test)
            }
        }

        val toolbar = createToolbar()

        val leftPanel = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }

        val previewTabs = JTabbedPane().apply {
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

        rebuildTree()
    }

    // ------------------------------------------------------------------
    // Дерево
    // ------------------------------------------------------------------

    private fun updateTreeStructure(tests: List<PaparazziTest>) {

        if (root.childCount == 0) {
            // ПЕРВАЯ ИНИЦИАЛИЗАЦИЯ (важно!)
            tests.forEach { test ->

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

            treeModel.reload() // 🔥 один раз

            TreeUtil.expandAll(tree)
            return
        }

        val existingTests = testNodes.keys.toMutableSet()
        val newTests = tests.toSet()

        // --- УДАЛЕНИЕ ---
        (existingTests - newTests).forEach { test ->
            val node = testNodes.remove(test) ?: return@forEach
            val parent = node.parent as? DefaultMutableTreeNode ?: return@forEach
            treeModel.removeNodeFromParent(node)
        }

        // --- ДОБАВЛЕНИЕ ---
        (newTests - existingTests).forEach { test ->

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
    }

    private fun updateNode(test: PaparazziTest) {
        val node = testNodes[test] ?: return
        treeModel.nodeChanged(node)
    }

    private fun rebuildTree() {
        // 1️⃣ Сохраняем текущие выделенные тесты
        val selectedTests = tree.selectionPaths
            ?.mapNotNull { path ->
                (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? PaparazziTest
            }
            ?.toSet()
            ?: emptySet()

        // 2️⃣ Пересоздаём дерево
        root.removeAllChildren()

        val tests = registry.allTests
            .filter { it.matchesFilter(filterText) }

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
        TreeUtil.expandAll(tree)

        // 3️⃣ Восстанавливаем выделение для всех ранее выбранных тестов
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
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val test = node.userObject as? PaparazziTest ?: return
        val projectRoot = Paths.get(project.basePath ?: return)

        val refs = test.referenceSnapshots(projectRoot)
        if (refs.isNotEmpty()){
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
            val match = Regex("\\[(\\d+)]").find(lbl)
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
                // Узел модуля или класса
                icon = if (node.parent == node.root()) AllIcons.Nodes.Module
                else AllIcons.Nodes.TestGroup
                append(obj)
            }
        }
    }

    private fun DefaultMutableTreeNode.root(): TreeNode {
        var n: TreeNode = this
        while (n.parent != null) n = n.parent
        return n
    }
}
