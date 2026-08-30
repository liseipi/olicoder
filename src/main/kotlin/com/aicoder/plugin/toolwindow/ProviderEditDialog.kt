package com.aicoder.plugin.toolwindow

import com.aicoder.plugin.model.ProviderConfig
import com.aicoder.plugin.model.ProviderType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * 新增 / 编辑一个模型配置档的弹窗，直接嵌在聊天面板里触发，无需打开系统 Settings。
 */
class ProviderEditDialog(
    project: Project,
    private val existing: ProviderConfig?
) : DialogWrapper(project, true) {

    private val nameField = JBTextField(existing?.name ?: "")
    private val typeCombo = ComboBox(ProviderType.values()).apply {
        selectedItem = existing?.providerType ?: ProviderType.OPENAI_COMPATIBLE
    }
    private val baseUrlField = JBTextField(existing?.baseUrl ?: "")
    private val modelField = JBTextField(existing?.model ?: "")
    private val apiKeyField = JBPasswordField()

    init {
        title = if (existing == null) "新增模型配置" else "编辑模型配置：${existing.name}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("配置名称：") { cell(nameField).resizableColumn() }
                .rowComment("随便起个名字，方便切换时识别，比如 \"DeepSeek\"、\"公司中转-Claude\"")
            row("协议类型：") { cell(typeCombo).resizableColumn() }
            row("Base URL：") { cell(baseUrlField).resizableColumn() }
                .rowComment("例如 https://api.deepseek.com 。带不带结尾 /v1 都可以，会自动识别")
            row("模型名称：") { cell(modelField).resizableColumn() }
                .rowComment("例如 deepseek-chat、claude-sonnet-4-6、gemini-1.5-pro、qwen-max")
            row("API Key：") { cell(apiKeyField).resizableColumn() }
                .rowComment(
                    if (existing == null) "仅加密保存在本机，不会上传到任何服务器"
                    else "留空 = 不修改已保存的 Key；重新填写则覆盖旧 Key"
                )
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("请填写配置名称", nameField)
        if (baseUrlField.text.isBlank()) return ValidationInfo("请填写 Base URL", baseUrlField)
        if (modelField.text.isBlank()) return ValidationInfo("请填写模型名称", modelField)
        if (existing == null && apiKeyField.password.isEmpty()) return ValidationInfo("请填写 API Key", apiKeyField)
        return null
    }

    /** 返回填好的配置对象（id 沿用编辑前的，新增则生成新 id） */
    fun buildResult(): ProviderConfig {
        val config = existing?.copy() ?: ProviderConfig()
        config.name = nameField.text.trim()
        config.providerType = typeCombo.selectedItem as ProviderType
        config.baseUrl = baseUrlField.text.trim()
        config.model = modelField.text.trim()
        return config
    }

    /** 用户本次是否真的输入了新 Key（编辑时可能留空表示不修改） */
    fun getTypedApiKeyOrNull(): String? {
        val typed = String(apiKeyField.password)
        return typed.ifBlank { null }
    }
}
