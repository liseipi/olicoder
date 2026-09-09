package com.olicoder.plugin.model

object ProviderFactory {
    private val openAiCompatible by lazy { OpenAICompatibleProvider() }
    private val anthropic by lazy { AnthropicProvider() }
    private val gemini by lazy { GeminiProvider() }

    fun get(type: ProviderType): ModelProvider = when (type) {
        ProviderType.OPENAI_COMPATIBLE -> openAiCompatible
        ProviderType.ANTHROPIC -> anthropic
        ProviderType.GEMINI -> gemini
    }
}
