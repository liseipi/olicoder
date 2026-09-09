package com.aicoder.plugin.toolwindow

import com.aicoder.plugin.model.ChatMessage
import com.aicoder.plugin.model.ImageAttachment
import com.aicoder.plugin.model.ProviderConfig
import com.aicoder.plugin.model.ProviderFactory
import com.aicoder.plugin.settings.AiCoderSettingsState
import com.aicoder.plugin.settings.TokenStorage
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.text.html.HTMLEditorKit

class ChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val settingsState = AiCoderSettingsState.getInstance()
    private val history = mutableListOf<ChatMessage>()

    // "@文件名" -> 对应的项目内 VirtualFile（通过输入 @ 引用）
    private val mentionedFiles = mutableMapOf<String, VirtualFile>()

    // 通过工具栏"book"图标从磁盘选择的文件（不要求在项目索引里），name -> 文本内容
    private val pendingLocalFiles = LinkedHashMap<String, String>()

    // 通过工具栏"图片"图标选择的图片附件，name -> ImageAttachment
    private val pendingImages = LinkedHashMap<String, ImageAttachment>()

    // AI 回复里抽取出的代码块，供"应用到编辑器"超链接按索引查找（多代码块场景的手动兜底方案）
    private val pendingCodeBlocks = mutableListOf<String>()

    // 单代码块场景：自动应用到文件后的变更记录，供"保留/撤销/查看变更"使用
    private enum class ChangeState { PENDING, KEPT, UNDONE }
    private data class PendingChange(
        val virtualFile: VirtualFile,
        val before: String,
        val after: String,
        var state: ChangeState = ChangeState.PENDING
    )
    private val pendingChanges = mutableListOf<PendingChange>()

    // 当前这一轮对话里，用户 "@" 引用的唯一文件（如果有且只有一个），用于自动应用时判断目标文件
    private var lastReferencedFile: VirtualFile? = null

    private val transcript = JEditorPane().apply {
        contentType = "text/html"
        val kit = HTMLEditorKit()
        // Swing 的 HTML 渲染引擎对内联 style 的级联支持并不完整，字号这类全局设置
        // 用 StyleSheet 规则来定义才能可靠地应用到所有子元素上，避免出现字体忽大忽小的问题。
        kit.styleSheet.addRule("body { font-family: sans-serif; font-size: 11px; color: #dddddd; }")
        kit.styleSheet.addRule("td, div, span, b, i { font-size: 11px; }")
        kit.styleSheet.addRule("pre { font-family: Monospaced; font-size: 10.5px; line-height: 1.35; }")
        kit.styleSheet.addRule("a { color: #6cb6ff; text-decoration: none; }")
        editorKit = kit
        isEditable = false
        text = wrapHtml("")
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                handleHyperlink(e.description)
            }
        }
    }

    private val inputArea = JBTextArea(4, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private val providerCombo = ComboBox<ProviderConfig>()
    private val sendButton = JButton("发送 (Ctrl+Enter)")
    private val statusLabel = JLabel(" ")
    private val attachmentBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))

    private var suppressMentionListener = false

    init {
        preferredSize = Dimension(400, 600)

        refreshProviderCombo()
        providerCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): java.awt.Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is ProviderConfig) text = value.name.ifBlank { "(未命名)" }
                return c
            }
        }

        val topBar = JPanel(BorderLayout())
        val addButton = JButton("＋").apply {
            toolTipText = "新增模型配置"
            addActionListener { openAddDialog() }
        }
        val editButton = JButton("✎").apply {
            toolTipText = "编辑当前选中的配置"
            addActionListener { openEditDialog() }
        }
        val deleteButton = JButton("🗑").apply {
            toolTipText = "删除当前选中的配置"
            addActionListener { deleteCurrentConfig() }
        }
        val topRight = JPanel()
        topRight.add(JLabel("模型："))
        topRight.add(providerCombo)
        topRight.add(addButton)
        topRight.add(editButton)
        topRight.add(deleteButton)
        topBar.add(topRight, BorderLayout.EAST)

        val scrollTranscript = JBScrollPane(transcript)

        // ---- 输入框上方的图标工具栏：@ 引用 / 图片 / 本地文件 / 更多 ----
        val iconToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        val mentionIconButton = JButton("@").apply {
            toolTipText = "引用项目里的文件"
            addActionListener { showFileMentionPopup(inputArea.caretPosition, removeTypedAt = false) }
        }
        val imageIconButton = JButton("Img").apply {
            toolTipText = "上传图片（需要模型支持视觉理解）"
            addActionListener { pickImageFile() }
        }
        val localFileIconButton = JButton("File").apply {
            toolTipText = "从磁盘选择文件作为上下文"
            addActionListener { pickLocalFile() }
        }
        val pasteIconButton = JButton("Paste").apply {
            toolTipText = "粘贴剪贴板里的图片或文件（如果 Ctrl/Cmd+V 没反应就点这个）"
            addActionListener { pasteFromClipboardButton() }
        }
        val moreIconButton = JButton("...").apply {
            toolTipText = "更多"
            addActionListener { showMoreMenu(this) }
        }
        iconToolbar.add(mentionIconButton)
        iconToolbar.add(imageIconButton)
        iconToolbar.add(localFileIconButton)
        iconToolbar.add(pasteIconButton)
        iconToolbar.add(moreIconButton)

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(iconToolbar, BorderLayout.NORTH)

        val inputScroll = JBScrollPane(inputArea)
        val inputWithChips = JPanel(BorderLayout())
        inputWithChips.add(attachmentBar, BorderLayout.NORTH)
        inputWithChips.add(inputScroll, BorderLayout.CENTER)
        attachmentBar.isVisible = false
        bottomPanel.add(inputWithChips, BorderLayout.CENTER)

        val hintLabel = JLabel("输入 @ 引用文件 / 点图标上传 / 也可以直接粘贴图片或文件")
        hintLabel.foreground = java.awt.Color(140, 140, 140)

        val sendPanel = JPanel(BorderLayout())
        sendPanel.add(sendButton, BorderLayout.EAST)
        sendPanel.add(statusLabel, BorderLayout.WEST)

        val bottomSouth = JPanel(BorderLayout())
        bottomSouth.add(hintLabel, BorderLayout.NORTH)
        bottomSouth.add(sendPanel, BorderLayout.SOUTH)
        bottomPanel.add(bottomSouth, BorderLayout.SOUTH)

        add(topBar, BorderLayout.NORTH)
        add(scrollTranscript, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)

        sendButton.addActionListener { onSend() }
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    onSend()
                }
            }
        })

        // 监听输入框里手动敲的 "@"，触发文件选择弹窗
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                if (suppressMentionListener) return
                if (e.length == 1) {
                    val offset = e.offset
                    val insertedChar = e.document.getText(offset, 1)
                    if (insertedChar == "@") {
                        SwingUtilities.invokeLater { showFileMentionPopup(offset, removeTypedAt = true) }
                    }
                }
            }
            override fun removeUpdate(e: DocumentEvent) {}
            override fun changedUpdate(e: DocumentEvent) {}
        })

        // 支持在输入框里直接粘贴图片（截图/复制的图片）或粘贴文件（文件管理器里复制的文件）
        inputArea.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(DataFlavor.imageFlavor) ||
                        support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                        support.isDataFlavorSupported(DataFlavor.stringFlavor)
            }

            override fun importData(support: TransferSupport): Boolean {
                return handlePastedTransferable(support.transferable)
            }
        }

        // 额外显式绑定 Ctrl/Cmd+V，直接读系统剪贴板处理。
        // 有些环境下（比如某些截图工具、或 IDE 对 Swing 组件的按键分发）走 TransferHandler
        // 收不到图片数据，直接绑定按键、自己读剪贴板内容更可靠。
        val pasteKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        inputArea.inputMap.put(pasteKeyStroke, "aiCoderPaste")
        inputArea.actionMap.put("aiCoderPaste", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val contents = clipboard.getContents(null) ?: return
                handlePastedTransferable(contents)
            }
        })
    }

    /** 统一处理一份剪贴板内容：优先标准图片 flavor，其次任意 image 开头的 MIME 流，然后文件列表，最后纯文本 */
    private fun handlePastedTransferable(transferable: java.awt.datatransfer.Transferable): Boolean {
        try {
            if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                val image = transferable.getTransferData(DataFlavor.imageFlavor) as Image
                addPastedImage(image)
                return true
            }

            // 兜底：有些截图工具/平台不会暴露标准的 DataFlavor.imageFlavor，
            // 但会提供一个 mime type 是 image/* 的输入流 flavor，这里扫一遍尝试用 ImageIO 解析。
            for (flavor in transferable.transferDataFlavors) {
                val mime = flavor.mimeType?.lowercase() ?: continue
                if (mime.startsWith("image/") && java.io.InputStream::class.java.isAssignableFrom(flavor.representationClass)) {
                    val inputStream = transferable.getTransferData(flavor) as java.io.InputStream
                    val bytes = inputStream.readBytes()
                    val bufferedImage = ImageIO.read(java.io.ByteArrayInputStream(bytes))
                    if (bufferedImage != null) {
                        addPastedImage(bufferedImage)
                        return true
                    }
                }
            }

            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                files.forEach { addPastedFile(it) }
                return true
            }
            if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val text = transferable.getTransferData(DataFlavor.stringFlavor) as String
                inputArea.replaceSelection(text)
                return true
            }
        } catch (e: Exception) {
            appendSystemNotice("粘贴失败：${e.message}")
        }
        return false
    }

    /** 工具栏"Paste"按钮：不依赖任何键盘事件路由，直接读一次系统剪贴板 */
    private fun pasteFromClipboardButton() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val contents = clipboard.getContents(null)
        if (contents == null) {
            appendSystemNotice("剪贴板是空的")
            return
        }
        val handled = handlePastedTransferable(contents)
        if (!handled) {
            val flavors = contents.transferDataFlavors.joinToString(", ") { it.mimeType ?: it.toString() }
            appendSystemNotice("剪贴板里没有识别到图片/文件/文本，可用的数据类型：$flavors")
        }
    }

    /** 粘贴板里直接是图片数据（比如截图工具复制的图）时，编码成附件 */
    private fun addPastedImage(image: Image) {
        try {
            val bufferedImage = image as? BufferedImage ?: run {
                val bi = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
                val g = bi.createGraphics()
                g.drawImage(image, 0, 0, null)
                g.dispose()
                bi
            }
            val baos = ByteArrayOutputStream()
            ImageIO.write(bufferedImage, "png", baos)
            val base64 = Base64.getEncoder().encodeToString(baos.toByteArray())
            val name = "粘贴的图片-${pendingImages.size + 1}.png"
            pendingImages[name] = ImageAttachment("image/png", base64)
            refreshAttachmentChips()
        } catch (e: Exception) {
            appendSystemNotice("处理粘贴的图片失败：${e.message}")
        }
    }

    /** 粘贴板里是文件（从文件管理器复制过来）时，按图片/文本分别处理成附件 */
    private fun addPastedFile(file: File) {
        val ext = file.extension.lowercase()
        if (ext in setOf("png", "jpg", "jpeg", "gif", "webp")) {
            try {
                val bytes = Files.readAllBytes(file.toPath())
                val base64 = Base64.getEncoder().encodeToString(bytes)
                pendingImages[file.name] = ImageAttachment(guessImageMimeType(file), base64)
                refreshAttachmentChips()
            } catch (e: Exception) {
                appendSystemNotice("读取粘贴的图片文件失败：${e.message}")
            }
        } else {
            try {
                val content = Files.readString(file.toPath())
                val truncated = if (content.length > 8000) content.take(8000) + "\n...(内容过长，已截断)" else content
                pendingLocalFiles[file.name] = truncated
                refreshAttachmentChips()
            } catch (e: Exception) {
                appendSystemNotice("读取粘贴的文件失败（可能是二进制文件）：${e.message}")
            }
        }
    }

    /** 供外部 Action（如"发送选中代码到对话"）调用，往输入框里塞文本 */
    fun insertIntoInput(text: String) {
        inputArea.text = (inputArea.text + "\n" + text).trim()
    }

    // ---------------- 更多菜单 ----------------

    private fun showMoreMenu(anchor: JComponent) {
        val menu = JPopupMenu()
        val clearChat = JMenuItem("清空对话记录")
        clearChat.addActionListener {
            val confirm = Messages.showYesNoDialog(project, "确定要清空当前对话记录吗？", "清空对话", Messages.getQuestionIcon())
            if (confirm == Messages.YES) {
                history.clear()
                messageBuffer.setLength(0)
                lastAssistantBubbleStart = -1
                renderTranscript()
            }
        }
        val clearAttachments = JMenuItem("清除所有已附加的文件/图片")
        clearAttachments.addActionListener {
            pendingLocalFiles.clear()
            pendingImages.clear()
            refreshAttachmentChips()
        }
        menu.add(clearChat)
        menu.add(clearAttachments)
        menu.show(anchor, 0, anchor.height)
    }

    // ---------------- 图片 / 本地文件 附件 ----------------

    private fun pickImageFile() {
        val chooser = JFileChooser()
        chooser.fileFilter = FileNameExtensionFilter("图片文件", "png", "jpg", "jpeg", "gif", "webp")
        val result = chooser.showOpenDialog(this)
        if (result != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile ?: return
        try {
            val bytes = Files.readAllBytes(file.toPath())
            val base64 = Base64.getEncoder().encodeToString(bytes)
            val mimeType = guessImageMimeType(file)
            pendingImages[file.name] = ImageAttachment(mimeType, base64)
            refreshAttachmentChips()
        } catch (e: Exception) {
            appendSystemNotice("读取图片失败：${e.message}")
        }
    }

    private fun guessImageMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun pickLocalFile() {
        val chooser = JFileChooser()
        val result = chooser.showOpenDialog(this)
        if (result != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile ?: return
        try {
            val content = Files.readString(file.toPath())
            val truncated = if (content.length > 8000) content.take(8000) + "\n...(内容过长，已截断)" else content
            pendingLocalFiles[file.name] = truncated
            refreshAttachmentChips()
        } catch (e: Exception) {
            appendSystemNotice("读取文件失败（可能是二进制文件）：${e.message}")
        }
    }

    private fun refreshAttachmentChips() {
        attachmentBar.removeAll()

        pendingLocalFiles.keys.toList().forEach { name ->
            attachmentBar.add(buildFileChip(name) {
                pendingLocalFiles.remove(name)
                refreshAttachmentChips()
            })
        }
        pendingImages.entries.toList().forEach { (name, attachment) ->
            attachmentBar.add(buildImageChip(name, attachment) {
                pendingImages.remove(name)
                refreshAttachmentChips()
            })
        }

        val hasAttachments = pendingLocalFiles.isNotEmpty() || pendingImages.isNotEmpty()
        attachmentBar.isVisible = hasAttachments
        attachmentBar.revalidate()
        attachmentBar.repaint()
    }

    /** 圆角小胶囊样式的附件条目：图标 + 文件名 + 删除按钮 */
    private fun buildChip(icon: JComponent, name: String, onRemove: () -> Unit): JPanel {
        val chip = object : JPanel(FlowLayout(FlowLayout.LEFT, 5, 2)) {
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = java.awt.Color(70, 70, 76)
                g2.fillRoundRect(0, 0, width, height, 12, 12)
                g2.dispose()
                super.paintComponent(g)
            }
        }
        chip.isOpaque = false
        chip.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
        chip.add(icon)
        val nameLabel = JLabel(name)
        nameLabel.foreground = java.awt.Color(220, 220, 220)
        chip.add(nameLabel)
        val removeBtn = JButton("×")
        removeBtn.isBorderPainted = false
        removeBtn.isContentAreaFilled = false
        removeBtn.margin = java.awt.Insets(0, 2, 0, 2)
        removeBtn.foreground = java.awt.Color(170, 170, 170)
        removeBtn.addActionListener { onRemove() }
        chip.add(removeBtn)
        return chip
    }

    private fun buildFileChip(name: String, onRemove: () -> Unit): JPanel {
        return buildChip(JLabel("📄"), name, onRemove)
    }

    private fun buildImageChip(name: String, attachment: ImageAttachment, onRemove: () -> Unit): JPanel {
        val thumbIcon = decodeImageIcon(attachment, 16)
        val iconLabel = if (thumbIcon != null) JLabel(thumbIcon) else JLabel("🖼")
        val chip = buildChip(iconLabel, name, onRemove)
        attachImageHoverPreview(chip, attachment)
        return chip
    }

    /** 把 base64 图片数据解码成缩略图 ImageIcon（保持宽高比缩放到 maxDim），失败时返回 null */
    private fun decodeImageIcon(attachment: ImageAttachment, maxDim: Int): ImageIcon? {
        return try {
            val bytes = Base64.getDecoder().decode(attachment.base64Data)
            val original = ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return null
            val w = original.width
            val h = original.height
            val scale = maxDim.toDouble() / maxOf(w, h)
            val newW = (w * scale).toInt().coerceAtLeast(1)
            val newH = (h * scale).toInt().coerceAtLeast(1)
            val scaled = original.getScaledInstance(newW, newH, Image.SCALE_SMOOTH)
            ImageIcon(scaled)
        } catch (e: Exception) {
            null
        }
    }

    private var hoverPreviewWindow: JWindow? = null
    private var hoverPreviewTimer: Timer? = null

    /** 鼠标移到图片附件条目上时，延迟一小会儿弹出一个较大尺寸的预览窗口 */
    private fun attachImageHoverPreview(component: JComponent, attachment: ImageAttachment) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hoverPreviewTimer?.stop()
                hoverPreviewTimer = Timer(250) { showHoverPreview(component, attachment) }.apply {
                    isRepeats = false
                    start()
                }
            }
            override fun mouseExited(e: MouseEvent) {
                hoverPreviewTimer?.stop()
                hideHoverPreview()
            }
        })
    }

    private fun showHoverPreview(anchor: JComponent, attachment: ImageAttachment) {
        hideHoverPreview()
        val icon = decodeImageIcon(attachment, 260) ?: return
        val label = JLabel(icon)
        label.border = BorderFactory.createLineBorder(java.awt.Color(90, 90, 90), 1)
        val window = JWindow()
        window.focusableWindowState = false
        window.contentPane.add(label)
        window.pack()
        try {
            val loc = anchor.locationOnScreen
            window.setLocation(loc.x, loc.y - window.height - 8)
        } catch (e: Exception) {
            // 组件还没显示到屏幕上就忽略，不弹预览
            return
        }
        window.isVisible = true
        hoverPreviewWindow = window
    }

    private fun hideHoverPreview() {
        hoverPreviewWindow?.dispose()
        hoverPreviewWindow = null
    }

    // ---------------- @ 文件引用（已打开文件优先，其次是项目内文件，不包含依赖库/SDK） ----------------

    /** 不希望出现在 @ 引用列表里的目录名（IDE 配置、VCS、构建产物等噪音目录） */
    private val excludedDirNames = setOf(".idea", ".git", ".gradle", ".hg", ".svn", "node_modules")

    private fun isInExcludedDir(vf: VirtualFile): Boolean {
        var parent = vf.parent
        while (parent != null) {
            if (parent.name in excludedDirNames) return true
            parent = parent.parent
        }
        return false
    }

    /** 收集候选文件：已打开的文件在前，然后是项目内容范围里的其他文件（不含依赖库、SDK、.idea/.git 等噪音目录） */
    private fun collectCandidateFiles(): List<VirtualFile> {
        val openFiles = FileEditorManager.getInstance(project).openFiles.toList()
        val openSet = openFiles.toHashSet()

        val projectFiles = mutableListOf<VirtualFile>()
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        fileIndex.iterateContent { vf ->
            if (!vf.isDirectory && vf !in openSet && !isInExcludedDir(vf)) {
                projectFiles.add(vf)
            }
            projectFiles.size < 3000 // 文件太多的项目做个上限，避免卡顿
        }
        projectFiles.sortBy { it.name.lowercase() }
        return openFiles + projectFiles
    }

    private fun labelForFile(vf: VirtualFile): String {
        val basePath = project.basePath
        val parentPath = vf.parent?.path
        val relativeDir = if (parentPath != null && basePath != null && parentPath.startsWith(basePath)) {
            parentPath.removePrefix(basePath).trimStart('/')
        } else {
            parentPath ?: ""
        }
        return if (relativeDir.isBlank()) vf.name else "${vf.name}   —   $relativeDir"
    }

    private fun showFileMentionPopup(atOffset: Int, removeTypedAt: Boolean) {
        val allCandidates = collectCandidateFiles()
        val openSet = FileEditorManager.getInstance(project).openFiles.toHashSet()

        val listModel = DefaultListModel<VirtualFile>()
        allCandidates.take(500).forEach { listModel.addElement(it) }
        val list = JBList(listModel)
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                l: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): java.awt.Component {
                val c = super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus)
                if (value is VirtualFile) {
                    val mark = if (value in openSet) "🟢 " else "　 "
                    text = mark + labelForFile(value)
                }
                return c
            }
        }
        val searchField = JBTextField()

        val panel = JPanel(BorderLayout())
        panel.add(searchField, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.preferredSize = Dimension(420, 280)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, searchField)
            .setRequestFocus(true)
            .setResizable(true)
            .setTitle("选择要引用的文件（🟢 = 已打开）")
            .createPopup()

        fun applyFilter() {
            val query = searchField.text.trim()
            listModel.clear()
            val filtered = if (query.isBlank()) allCandidates else allCandidates.filter { it.name.contains(query, ignoreCase = true) }
            filtered.take(500).forEach { listModel.addElement(it) }
            if (listModel.size() > 0) list.selectedIndex = 0
        }

        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })

        fun choose() {
            val selected = list.selectedValue ?: return
            insertFileMention(selected, atOffset, removeTypedAt)
            popup.closeOk(null)
        }

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) choose()
            }
        })
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> list.selectedIndex = (list.selectedIndex + 1).coerceAtMost(listModel.size() - 1)
                    KeyEvent.VK_UP -> list.selectedIndex = (list.selectedIndex - 1).coerceAtLeast(0)
                    KeyEvent.VK_ENTER -> choose()
                }
            }
        })

        popup.showInScreenCoordinates(inputArea, inputArea.locationOnScreen)
    }

    private fun insertFileMention(vf: VirtualFile, atOffset: Int, removeTypedAt: Boolean) {
        val fileName = vf.name
        mentionedFiles[fileName] = vf

        val insertText = "@$fileName "
        suppressMentionListener = true
        try {
            if (removeTypedAt) {
                inputArea.document.remove(atOffset, 1)
            }
            inputArea.document.insertString(atOffset, insertText, null)
            inputArea.caretPosition = (atOffset + insertText.length).coerceAtMost(inputArea.document.length)
        } finally {
            suppressMentionListener = false
        }
        inputArea.requestFocusInWindow()
    }

    /** 把用户消息里 "@文件名" 引用、以及工具栏里手动选的本地文件，都拼成额外上下文 */
    private fun buildContextualMessages(userText: String): List<ChatMessage> {
        val referencedByAt = mentionedFiles.filterKeys { userText.contains("@$it") }
        if (referencedByAt.isEmpty() && pendingLocalFiles.isEmpty()) return history

        val blocks = mutableListOf<String>()
        referencedByAt.forEach { (name, vf) ->
            val content = try {
                VfsUtilCore.loadText(vf)
            } catch (e: Exception) {
                "(读取文件失败：${e.message})"
            }
            val truncated = if (content.length > 6000) content.take(6000) + "\n...(内容过长，已截断)" else content
            blocks.add("文件 $name 的内容：\n```\n$truncated\n```")
        }
        pendingLocalFiles.forEach { (name, content) ->
            blocks.add("文件 $name 的内容：\n```\n$content\n```")
        }

        val withContext = history.toMutableList()
        withContext.add(ChatMessage("system", blocks.joinToString("\n\n")))
        return withContext
    }

    // ---------------- Provider 增删改 ----------------

    private fun refreshProviderCombo(selectId: String? = null) {
        providerCombo.removeAllItems()
        settingsState.providers.forEach { providerCombo.addItem(it) }
        val target = settingsState.providers.firstOrNull { it.id == selectId }
            ?: settingsState.getActiveProvider()
        if (target != null) providerCombo.selectedItem = target
    }

    private fun openAddDialog() {
        val dialog = ProviderEditDialog(project, existing = null)
        if (dialog.showAndGet()) {
            val newConfig = dialog.buildResult()
            settingsState.providers.add(newConfig)
            settingsState.activeProviderId = newConfig.id
            dialog.getTypedApiKeyOrNull()?.let { TokenStorage.saveToken(newConfig.id, it) }
            refreshProviderCombo(selectId = newConfig.id)
        }
    }

    private fun openEditDialog() {
        val selected = providerCombo.selectedItem as? ProviderConfig
        if (selected == null) {
            openAddDialog()
            return
        }
        val dialog = ProviderEditDialog(project, existing = selected)
        if (dialog.showAndGet()) {
            val updated = dialog.buildResult()
            val index = settingsState.providers.indexOfFirst { it.id == updated.id }
            if (index >= 0) settingsState.providers[index] = updated
            dialog.getTypedApiKeyOrNull()?.let { TokenStorage.saveToken(updated.id, it) }
            refreshProviderCombo(selectId = updated.id)
        }
    }

    private fun deleteCurrentConfig() {
        val selected = providerCombo.selectedItem as? ProviderConfig ?: return
        val confirm = Messages.showYesNoDialog(
            project,
            "确定要删除配置 \"${selected.name}\" 吗？",
            "删除模型配置",
            Messages.getQuestionIcon()
        )
        if (confirm == Messages.YES) {
            settingsState.providers.removeIf { it.id == selected.id }
            TokenStorage.removeToken(selected.id)
            if (settingsState.activeProviderId == selected.id) {
                settingsState.activeProviderId = settingsState.providers.firstOrNull()?.id
            }
            refreshProviderCombo()
        }
    }

    // ---------------- 发送 / 接收消息 ----------------

    private fun onSend() {
        val userText = inputArea.text.trim()
        if (userText.isBlank() && pendingImages.isEmpty()) return
        val config = providerCombo.selectedItem as? ProviderConfig
        if (config == null) {
            appendSystemNotice("还没有任何模型配置，请点右上角\"＋\"新增一个")
            return
        }
        val apiKey = TokenStorage.getToken(config.id)
        if (apiKey.isNullOrBlank()) {
            appendSystemNotice("配置 \"${config.name}\" 还没有 API Key，请点\"✎\"补充")
            return
        }

        val imageNames = pendingImages.keys.toList()
        val imagesForThisMessage = pendingImages.values.toList()
        val referencedByAt = mentionedFiles.filterKeys { userText.contains("@$it") }
        lastReferencedFile = referencedByAt.values.singleOrNull()
        val contextMessages = buildContextualMessages(userText)
        val userMessage = ChatMessage("user", userText, images = imagesForThisMessage)
        val messagesToSend = contextMessages + userMessage

        inputArea.text = ""
        appendUserMessage(userText, imageNames)
        history.add(userMessage)

        pendingImages.clear()
        pendingLocalFiles.clear()
        refreshAttachmentChips()

        setBusy(true)
        val provider = ProviderFactory.get(config.providerType)
        val assistantSoFar = StringBuilder()
        appendAssistantMessagePlaceholder()

        provider.chatStream(
            config = config,
            apiKey = apiKey,
            messages = messagesToSend,
            onToken = { token ->
                assistantSoFar.append(token)
                ApplicationManager.getApplication().invokeLater {
                    updateLastAssistantMessage(assistantSoFar.toString())
                }
            },
            onComplete = {
                ApplicationManager.getApplication().invokeLater {
                    history.add(ChatMessage("assistant", assistantSoFar.toString()))
                    finalizeAssistantMessage(assistantSoFar.toString())
                    setBusy(false)
                }
            },
            onError = { throwable ->
                ApplicationManager.getApplication().invokeLater {
                    updateLastAssistantMessage("⚠️ 请求出错：${throwable.message}")
                    setBusy(false)
                }
            }
        )
    }

    private fun setBusy(busy: Boolean) {
        sendButton.isEnabled = !busy
        statusLabel.text = if (busy) "AI 正在生成…" else " "
    }

    // ---------------- 气泡渲染（用户右侧 / AI 左侧，仿 CodeBuddy 风格）----------------

    private val messageBuffer = StringBuilder()
    private var lastAssistantBubbleStart = -1

    private fun appendUserMessage(content: String, imageNames: List<String>) {
        val safe = escapeHtml(content)
        val imagesLine = if (imageNames.isNotEmpty())
            "<div style=\"color:#cde; font-size:10px; margin-top:3px;\">🖼 ${imageNames.joinToString(", ") { escapeHtml(it) }}</div>"
        else ""
        messageBuffer.append(
            """
            <table width="100%" cellpadding="0" cellspacing="0"><tr><td align="right">
              <div style="color:#888; font-size:10px; margin:6px 4px 2px 0;">你</div>
              <table cellpadding="6" cellspacing="0" style="background-color:#2b5278; border-radius:6px;">
                <tr><td style="color:#ffffff;">$safe$imagesLine</td></tr>
              </table>
            </td></tr></table>
            """.trimIndent()
        )
        renderTranscript()
    }

    private fun appendAssistantMessagePlaceholder() {
        lastAssistantBubbleStart = messageBuffer.length
        messageBuffer.append(renderAssistantBubble("…"))
        renderTranscript()
    }

    private fun updateLastAssistantMessage(content: String) {
        if (lastAssistantBubbleStart < 0) return
        val before = messageBuffer.substring(0, lastAssistantBubbleStart)
        messageBuffer.setLength(0)
        messageBuffer.append(before)
        messageBuffer.append(renderAssistantBubble(content))
        renderTranscript()
    }

    /** 回复流结束后解析代码块：只有一段代码时自动应用到目标文件并给出保留/撤销/查看变更；多段代码时走手动兜底方案 */
    private fun finalizeAssistantMessage(content: String) {
        if (lastAssistantBubbleStart < 0) return
        val before = messageBuffer.substring(0, lastAssistantBubbleStart)
        messageBuffer.setLength(0)
        messageBuffer.append(before)

        val codeBlockRegex = Regex("```[a-zA-Z0-9_+-]*\\n([\\s\\S]*?)```")
        val matches = codeBlockRegex.findAll(content).toList()

        messageBuffer.append(renderAssistantBubble(content))

        when {
            matches.size == 1 -> {
                val code = matches[0].groupValues[1]
                val targetFile = resolveTargetFile()
                if (targetFile != null) {
                    autoApplyChange(targetFile, code)
                } else {
                    // 没有能确定的目标文件（没有 @ 引用，也没有打开的编辑器），退回手动方式
                    val idx = pendingCodeBlocks.size
                    pendingCodeBlocks.add(code)
                    appendManualApplyLinks(listOf(idx))
                }
            }
            matches.size > 1 -> {
                // 多段代码可能对应多个文件，无法安全地自动判断，走原来的手动确认方式
                val indices = matches.map { match ->
                    pendingCodeBlocks.add(match.groupValues[1])
                    pendingCodeBlocks.size - 1
                }
                appendManualApplyLinks(indices)
            }
        }
        renderTranscript()
    }

    private fun appendManualApplyLinks(indices: List<Int>) {
        val linksHtml = indices.joinToString(" &nbsp;|&nbsp; ") { idx ->
            "<a href=\"apply:$idx\">📋 应用第${idx + 1}段代码到编辑器</a>"
        }
        messageBuffer.append(
            """<table width="100%"><tr><td align="left"><div style="margin:2px 0 10px 6px; font-size:11px;">$linksHtml</div></td></tr></table>"""
        )
    }

    /** 判断这次要自动改哪个文件：优先用本轮 @ 引用的唯一文件，其次用当前打开的编辑器 */
    private fun resolveTargetFile(): VirtualFile? {
        lastReferencedFile?.let { return it }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return FileDocumentManager.getInstance().getFile(editor.document)
    }

    /** 直接把代码写入目标文件（自动应用），并记录变更供"保留/撤销/查看变更"使用 */
    private fun autoApplyChange(targetFile: VirtualFile, newContent: String) {
        val beforeContent = try {
            VfsUtilCore.loadText(targetFile)
        } catch (e: Exception) {
            ""
        }

        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(targetFile)
            if (document != null) {
                document.setText(newContent)
            } else {
                VfsUtil.saveText(targetFile, newContent)
            }
        }

        val changeId = pendingChanges.size
        pendingChanges.add(PendingChange(targetFile, beforeContent, newContent))
        messageBuffer.append(renderChangeActionBar(changeId))
    }

    /** 渲染某个 change 的操作条（保留/撤销/查看变更），根据当前状态决定链接是否可点。
     *  用注释标记包起来，方便之后原地替换成"已处理"的样子。 */
    private fun renderChangeActionBar(changeId: Int): String {
        val change = pendingChanges[changeId]
        val fileName = escapeHtml(change.virtualFile.name)

        val (headline, keepPart, undoPart) = when (change.state) {
            ChangeState.PENDING -> Triple(
                "✅ 已自动应用修改到 <b>$fileName</b>",
                "<a href=\"keep:$changeId\">保留</a>",
                "<a href=\"undo:$changeId\">撤销</a>"
            )
            ChangeState.KEPT -> Triple(
                "✅ 已保留对 <b>$fileName</b> 的修改",
                "<span style=\"color:#777;\">保留</span>",
                "<span style=\"color:#777;\">撤销</span>"
            )
            ChangeState.UNDONE -> Triple(
                "↩️ 已撤销对 <b>$fileName</b> 的这一步修改",
                "<span style=\"color:#777;\">保留</span>",
                "<span style=\"color:#777;\">撤销</span>"
            )
        }
        val diffPart = "<a href=\"diff:$changeId\">查看变更</a>"

        return """<!--CB$changeId--><table width="100%"><tr><td align="left">
                <div style="margin:2px 0 10px 6px; font-size:11px;">
                  $headline &nbsp;
                  $keepPart &nbsp;|&nbsp;
                  $undoPart &nbsp;|&nbsp;
                  $diffPart
                </div></td></tr></table><!--/CB$changeId-->"""
    }

    /** 在 messageBuffer 里原地找到某个 change 的操作条并替换成最新状态（比如从"待处理"变成"已保留"） */
    private fun refreshChangeActionBarInBuffer(changeId: Int) {
        val startMarker = "<!--CB$changeId-->"
        val endMarker = "<!--/CB$changeId-->"
        val start = messageBuffer.indexOf(startMarker)
        val end = messageBuffer.indexOf(endMarker)
        if (start < 0 || end < 0) return
        messageBuffer.replace(start, end + endMarker.length, renderChangeActionBar(changeId))
    }

    private fun renderAssistantBubble(content: String): String {
        val rendered = renderContentWithCodeBlocks(content)
        return """
            <table width="100%" cellpadding="0" cellspacing="0"><tr><td align="left">
              <div style="color:#888; font-size:10px; margin:6px 0 2px 4px;">AI</div>
              <table cellpadding="6" cellspacing="0" style="background-color:#3c3f41; border-radius:6px;">
                <tr><td style="color:#dddddd;">$rendered</td></tr>
              </table>
            </td></tr></table>
            """.trimIndent()
    }

    /**
     * 把回复文本按 ``` 代码围栏切开，普通文本正常转义换行，代码部分渲染成等宽字体的深色代码框。
     * 流式输出中途如果代码围栏还没闭合（``` 数量是奇数），最后一段也按代码块处理，
     * 这样打字机效果里代码块会从一开始就是"代码框"的样子，而不是先出纯文本再突然变代码框。
     */
    private fun renderContentWithCodeBlocks(content: String): String {
        val segments = content.split("```")
        val sb = StringBuilder()
        segments.forEachIndexed { index, segment ->
            if (index % 2 == 0) {
                if (segment.isNotEmpty()) sb.append(escapeHtml(segment))
            } else {
                val firstNewline = segment.indexOf('\n')
                val lang = if (firstNewline >= 0) segment.substring(0, firstNewline).trim() else segment.trim()
                val code = if (firstNewline >= 0) segment.substring(firstNewline + 1) else ""
                sb.append(renderCodeBlockHtml(code, lang))
            }
        }
        return sb.toString()
    }

    private fun renderCodeBlockHtml(code: String, lang: String): String {
        val escapedCode = code
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val langLabel = if (lang.isNotBlank())
            "<div style=\"color:#8a8f98; font-size:9px; margin:3px 0 1px 2px;\">${escapeHtml(lang)}</div>"
        else ""
        return """<div style="margin:2px 0;">$langLabel<pre style="background-color:#1e1f22; color:#d4d4d4; padding:6px; border-radius:5px; overflow-x:auto; font-family:Monospaced; font-size:10.5px; margin:0; white-space:pre-wrap;">$escapedCode</pre></div>"""
    }

    private fun appendSystemNotice(text: String) {
        messageBuffer.append(
            """<table width="100%"><tr><td align="center"><i style="color:#999; font-size:11px;">${escapeHtml(text)}</i></td></tr></table>"""
        )
        renderTranscript()
    }

    private fun renderTranscript() {
        transcript.text = wrapHtml(messageBuffer.toString())
        transcript.caretPosition = transcript.document.length
    }

    private fun wrapHtml(body: String): String =
        "<html><body style='font-family:sans-serif;font-size:11px;'>$body</body></html>"

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>")

    // ---------------- 代码应用回编辑器 ----------------

    private fun handleHyperlink(description: String) {
        when {
            description.startsWith("apply:") -> {
                val idx = description.removePrefix("apply:").toIntOrNull() ?: return
                val code = pendingCodeBlocks.getOrNull(idx) ?: return
                applyCodeToEditor(code)
            }
            description.startsWith("keep:") -> {
                val idx = description.removePrefix("keep:").toIntOrNull() ?: return
                handleKeepChange(idx)
            }
            description.startsWith("undo:") -> {
                val idx = description.removePrefix("undo:").toIntOrNull() ?: return
                handleUndoChange(idx)
            }
            description.startsWith("diff:") -> {
                val idx = description.removePrefix("diff:").toIntOrNull() ?: return
                handleShowDiff(idx)
            }
        }
    }

    private fun handleKeepChange(idx: Int) {
        val change = pendingChanges.getOrNull(idx) ?: return
        if (change.state != ChangeState.PENDING) return // 已经处理过了，忽略重复点击
        change.state = ChangeState.KEPT
        refreshChangeActionBarInBuffer(idx)
        renderTranscript()
    }

    private fun handleUndoChange(idx: Int) {
        val change = pendingChanges.getOrNull(idx) ?: return
        if (change.state != ChangeState.PENDING) return // 已经处理过了，忽略重复点击

        val fileEditorManager = FileEditorManager.getInstance(project)

        // 撤销要基于"这个文件自己的撤销历史栈"来做，而不是简单粗暴地把整份文件重置成旧快照，
        // 否则会把这次修改之后、文件上发生的其他修改也一起冲掉。
        var editors = fileEditorManager.getEditors(change.virtualFile)
        if (editors.isEmpty()) {
            editors = fileEditorManager.openFile(change.virtualFile, false)
        }
        val fileEditor = editors.firstOrNull()
        if (fileEditor == null) {
            appendSystemNotice("没能定位到 ${change.virtualFile.name} 对应的编辑器，撤销失败")
            return
        }

        val undoManager = UndoManager.getInstance(project)
        if (undoManager.isUndoAvailable(fileEditor)) {
            undoManager.undo(fileEditor)
            change.state = ChangeState.UNDONE
            refreshChangeActionBarInBuffer(idx)
            renderTranscript()
        } else {
            appendSystemNotice("这一步修改已经没法撤销了（可能已经撤销过，或者后面又有新的改动）")
        }
    }

    private fun handleShowDiff(idx: Int) {
        val change = pendingChanges.getOrNull(idx) ?: return
        val contentFactory = DiffContentFactory.getInstance()
        val beforeContent = contentFactory.create(project, change.before)
        val afterContent = contentFactory.create(project, change.after)
        val request = SimpleDiffRequest(
            "变更对比：${change.virtualFile.name}",
            beforeContent, afterContent,
            "修改前", "修改后"
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun applyCodeToEditor(code: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            appendSystemNotice("当前没有打开的编辑器，请先打开要修改的文件")
            return
        }

        val hasSelection = editor.selectionModel.hasSelection()
        val message = if (hasSelection)
            "将替换编辑器中当前选中的内容，确定吗？"
        else
            "编辑器里没有选中内容，将把代码插入到光标所在位置，确定吗？"

        val confirm = Messages.showYesNoDialog(project, message, "应用代码到编辑器", Messages.getQuestionIcon())
        if (confirm != Messages.YES) return

        WriteCommandAction.runWriteCommandAction(project) {
            val document = editor.document
            if (hasSelection) {
                val start = editor.selectionModel.selectionStart
                val end = editor.selectionModel.selectionEnd
                document.replaceString(start, end, code)
                editor.caretModel.moveToOffset(start + code.length)
            } else {
                val offset = editor.caretModel.offset
                document.insertString(offset, code)
                editor.caretModel.moveToOffset(offset + code.length)
            }
        }
        appendSystemNotice("代码已应用到编辑器")
    }
}
