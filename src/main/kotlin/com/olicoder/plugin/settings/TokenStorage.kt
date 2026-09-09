package com.olicoder.plugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * 每个 ProviderConfig 的 API Key 单独用 IntelliJ 自带的 PasswordSafe 加密存储在本机，
 * 不会写进插件自己的配置文件，也不会上传到任何服务器。
 */
object TokenStorage {

    private fun attributes(providerConfigId: String): CredentialAttributes =
        CredentialAttributes(generateServiceName("OliCoderPlugin", providerConfigId))

    fun saveToken(providerConfigId: String, token: String) {
        PasswordSafe.instance.set(attributes(providerConfigId), Credentials("token", token))
    }

    fun getToken(providerConfigId: String): String? {
        return PasswordSafe.instance.get(attributes(providerConfigId))?.getPasswordAsString()
    }

    fun removeToken(providerConfigId: String) {
        PasswordSafe.instance.set(attributes(providerConfigId), null)
    }
}
