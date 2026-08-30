package com.aicoder.plugin.settings

import com.aicoder.plugin.model.ProviderConfig
import com.aicoder.plugin.model.ProviderType
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * Settings -> Tools -> AI Coder 设置页
 * 左边是配置档列表（可增删），右边是当前选中档案的编辑表单。
 */
class AiCoderSettingsConfigurable : Configurable {

    private val state = AiCoderSettingsState.getInstance()

    // 工作副本：只有点"Apply"才真正写回 state，符合 Configurable 的规范
    private var workingCopies: MutableList<ProviderConfig> = state.providers.map { it.copy() }.toMutableList()
    private val tokenEdits = mutableMapOf<String, String>() // providerId -> 新填的token（未保存）

    private val listModel = DefaultListModel<ProviderConfig>()
    private val profileList = JBList(listModel)

    private val nameField = JBTextField()
    private val typeCombo = ComboBox(ProviderType.values())
    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val apiKeyField = JBPasswordField()

    private var mainPanel: JComponent? = null
    private var currentEditing: ProviderConfig? = null

    override fun getDisplayName(): String = "AI Coder"

    override fun createComponent(): JComponent {
        listModel.clear()
        workingCopies.forEach { listModel.addElement(it) }

        profileList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): java.awt.Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is ProviderConfig) {
                    text = if (value.name.isBlank()) "(未命名)" else value.name
                }
                return c
            }
        }

        profileList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                saveFormIntoCurrentEditing()
                loadFormFrom(profileList.selectedValue)
            }
        }

        val listPanel = ToolbarDecorator.createDecorator(profileList)
            .setAddAction {
                val newConfig = ProviderConfig(name = "新配置")
                workingCopies.add(newConfig)
                listModel.addElement(newConfig)
                profileList.selectedIndex = listModel.size() - 1
            }
            .setRemoveAction {
                val selected = profileList.selectedValue ?: return@setRemoveAction
                workingCopies.remove(selected)
                listModel.removeElement(selected)
                tokenEdits.remove(selected.id)
            }
            .createPanel()
        listPanel.preferredSize = Dimension(220, 300)

        val formPanel = panel {
            row("配置名称：") { cell(nameField).resizableColumn() }
            row("协议类型：") { cell(typeCombo).resizableColumn() }
            row("Base URL：") { cell(baseUrlField).resizableColumn() }
                .rowComment("例如 https://api.deepseek.com 、https://api.anthropic.com 、https://generativelanguage.googleapis.com；也可填第三方中转地址")
            row("模型名称：") { cell(modelField).resizableColumn() }
                .rowComment("例如 deepseek-chat、claude-sonnet-4-6、gemini-1.5-pro、qwen-max 等，需与厂商文档一致")
            row("API Key：") { cell(apiKeyField).resizableColumn() }
                .rowComment("仅保存在本机加密存储中，不会上传到任何服务器")
        }

        val root = JPanel(BorderLayout())
        root.add(listPanel, BorderLayout.WEST)
        root.add(formPanel, BorderLayout.CENTER)

        if (listModel.size() > 0) {
            profileList.selectedIndex = 0
        }

        mainPanel = root
        return root
    }

    private fun loadFormFrom(config: ProviderConfig?) {
        currentEditing = config
        if (config == null) {
            nameField.text = ""
            baseUrlField.text = ""
            modelField.text = ""
            apiKeyField.text = ""
            return
        }
        nameField.text = config.name
        typeCombo.selectedItem = config.providerType
        baseUrlField.text = config.baseUrl
        modelField.text = config.model
        // 出于安全考虑，已保存的 Key 不回显明文，留空表示"不修改"
        apiKeyField.text = tokenEdits[config.id] ?: ""
    }

    private fun saveFormIntoCurrentEditing() {
        val editing = currentEditing ?: return
        editing.name = nameField.text
        editing.providerType = typeCombo.selectedItem as ProviderType
        editing.baseUrl = baseUrlField.text.trim()
        editing.model = modelField.text.trim()
        val typed = String(apiKeyField.password)
        if (typed.isNotBlank()) {
            tokenEdits[editing.id] = typed
        }
        profileList.repaint()
    }

    override fun isModified(): Boolean = true // 简化处理：始终允许 Apply

    override fun apply() {
        saveFormIntoCurrentEditing()
        state.providers = workingCopies
        if (state.activeProviderId == null && workingCopies.isNotEmpty()) {
            state.activeProviderId = workingCopies.first().id
        }
        // 只把用户本次真正输入过的新 token 写入加密存储
        tokenEdits.forEach { (id, token) ->
            TokenStorage.saveToken(id, token)
        }
    }

    override fun reset() {
        workingCopies = state.providers.map { it.copy() }.toMutableList()
        tokenEdits.clear()
        listModel.clear()
        workingCopies.forEach { listModel.addElement(it) }
        if (listModel.size() > 0) profileList.selectedIndex = 0
    }
}
