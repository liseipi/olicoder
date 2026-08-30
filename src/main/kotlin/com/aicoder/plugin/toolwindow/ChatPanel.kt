package com.aicoder.plugin.toolwindow

import com.aicoder.plugin.model.ChatMessage
import com.aicoder.plugin.model.ProviderConfig
import com.aicoder.plugin.model.ProviderFactory
import com.aicoder.plugin.settings.AiCoderSettingsState
import com.aicoder.plugin.settings.TokenStorage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.text.html.HTMLEditorKit

class ChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val settingsState = AiCoderSettingsState.getInstance()
    private val history = mutableListOf<ChatMessage>()

    private val transcript = JEditorPane().apply {
        contentType = "text/html"
        editorKit = HTMLEditorKit()
        isEditable = false
        text = "<html><body style='font-family:sans-serif;font-size:12px;'></body></html>"
    }

    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private val providerCombo = ComboBox<ProviderConfig>()
    private val sendButton = JButton("发送 (Ctrl+Enter)")
    private val statusLabel = JLabel(" ")

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

        val bottomPanel = JPanel(BorderLayout())
        val inputScroll = JBScrollPane(inputArea)
        bottomPanel.add(inputScroll, BorderLayout.CENTER)

        val sendPanel = JPanel(BorderLayout())
        sendPanel.add(sendButton, BorderLayout.EAST)
        sendPanel.add(statusLabel, BorderLayout.WEST)
        bottomPanel.add(sendPanel, BorderLayout.SOUTH)

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
    }

    /** 供外部 Action（如"发送选中代码到对话"）调用，往输入框里塞文本 */
    fun insertIntoInput(text: String) {
        inputArea.text = (inputArea.text + "\n" + text).trim()
    }

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

    private fun onSend() {
        val userText = inputArea.text.trim()
        if (userText.isBlank()) return
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

        inputArea.text = ""
        appendMessage("user", userText)
        history.add(ChatMessage("user", userText))

        setBusy(true)
        val provider = ProviderFactory.get(config.providerType)
        val assistantSoFar = StringBuilder()
        appendMessage("assistant", "") // 占位，后续流式追加

        provider.chatStream(
            config = config,
            apiKey = apiKey,
            messages = history,
            onToken = { token ->
                assistantSoFar.append(token)
                ApplicationManager.getApplication().invokeLater {
                    updateLastAssistantMessage(assistantSoFar.toString())
                }
            },
            onComplete = {
                ApplicationManager.getApplication().invokeLater {
                    history.add(ChatMessage("assistant", assistantSoFar.toString()))
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

    private val messageBuffer = StringBuilder()
    private var lastAssistantStart = -1

    private fun appendMessage(role: String, content: String) {
        val label = if (role == "user") "🧑 你" else "🤖 AI"
        val color = if (role == "user") "#4A90D9" else "#5CB85C"
        val safe = escapeHtml(content)
        if (role == "assistant") {
            lastAssistantStart = messageBuffer.length
        }
        messageBuffer.append("<p><b style='color:$color'>$label：</b><br/>$safe</p>")
        renderTranscript()
    }

    private fun updateLastAssistantMessage(content: String) {
        if (lastAssistantStart < 0) return
        val before = messageBuffer.substring(0, lastAssistantStart)
        val safe = escapeHtml(content).replace("\n", "<br/>")
        messageBuffer.setLength(0)
        messageBuffer.append(before)
        messageBuffer.append("<p><b style='color:#5CB85C'>🤖 AI：</b><br/>$safe</p>")
        renderTranscript()
    }

    private fun appendSystemNotice(text: String) {
        messageBuffer.append("<p><i style='color:#999'>系统提示：${escapeHtml(text)}</i></p>")
        renderTranscript()
    }

    private fun renderTranscript() {
        transcript.text = "<html><body style='font-family:sans-serif;font-size:12px;'>$messageBuffer</body></html>"
        transcript.caretPosition = transcript.document.length
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>")
}
