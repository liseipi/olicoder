package com.olicoder.plugin.settings

import com.olicoder.plugin.model.ProviderConfig
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 应用级（跨项目共享）设置：保存用户配置的所有"模型档案"列表，以及当前激活的档案 id。
 * 注意：这里只存非敏感字段，API Key 走 TokenStorage 单独加密存储。
 */
@State(
    name = "OliCoderSettings",
    storages = [Storage("oli-coder.xml")]
)
class OliCoderSettingsState : PersistentStateComponent<OliCoderSettingsState> {

    var providers: MutableList<ProviderConfig> = mutableListOf()
    var activeProviderId: String? = null

    override fun getState(): OliCoderSettingsState = this

    override fun loadState(state: OliCoderSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun getActiveProvider(): ProviderConfig? =
        providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()

    companion object {
        fun getInstance(): OliCoderSettingsState = service()
    }
}
