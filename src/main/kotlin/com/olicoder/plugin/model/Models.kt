package com.olicoder.plugin.model

/**
 * 一张图片附件（base64 编码），随消息一起发给支持视觉的模型
 */
data class ImageAttachment(
    val mimeType: String,  // 例如 image/png、image/jpeg
    val base64Data: String
)

/**
 * 一条对话消息
 */
data class ChatMessage(
    val role: String,   // "system" | "user" | "assistant"
    val content: String,
    val images: List<ImageAttachment> = emptyList()
)

/**
 * 支持的厂商协议类型
 */
enum class ProviderType(val displayName: String) {
    OPENAI_COMPATIBLE("OpenAI 兼容协议（DeepSeek/通义千问/Kimi/智谱GLM/MiniMax/OpenAI等）"),
    ANTHROPIC("Anthropic (Claude)"),
    GEMINI("Google Gemini");

    override fun toString(): String = displayName
}

/**
 * 一个"模型配置档"，用户可以创建多个，随时切换。
 * 注意：这里只存非敏感信息，apiKey 单独用 PasswordSafe 加密存储，见 TokenStorage。
 */
data class ProviderConfig(
    var id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",              // 用户自定义的配置名，如 "DeepSeek-官方"
    var providerType: ProviderType = ProviderType.OPENAI_COMPATIBLE,
    var baseUrl: String = "",           // 例如 https://api.deepseek.com  或 https://api.anthropic.com
    var model: String = "",             // 例如 deepseek-chat / claude-sonnet-4-6 / gemini-1.5-pro
    var temperature: Double = 0.7
) {
    // no-arg 构造，供 XML 持久化反序列化使用
    constructor() : this(id = java.util.UUID.randomUUID().toString())
}
