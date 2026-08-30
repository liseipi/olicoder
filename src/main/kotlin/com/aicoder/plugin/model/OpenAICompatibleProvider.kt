package com.aicoder.plugin.model

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

/**
 * 适配所有兼容 OpenAI /v1/chat/completions 协议的厂商：
 * DeepSeek、通义千问(Qwen/DashScope兼容模式)、Kimi(Moonshot)、智谱GLM、MiniMax、
 * 以及国外的 OpenAI、Groq、Together AI、大部分开源模型托管服务等。
 *
 * 用户只需要在设置里填 baseUrl（例如 https://api.deepseek.com）和 model 名称即可。
 */
class OpenAICompatibleProvider : ModelProvider {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val executor = Executors.newCachedThreadPool()

    /**
     * 智能拼接 chat/completions 端点，兼容用户填 baseUrl 时是否已经带了 /v1：
     *   https://api.deepseek.com          -> https://api.deepseek.com/v1/chat/completions
     *   https://api.deepseek.com/v1       -> https://api.deepseek.com/v1/chat/completions
     *   https://xxx/v1/chat/completions   -> 原样使用（用户填了完整路径）
     */
    private fun buildChatCompletionsUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    override fun chatStream(
        config: ProviderConfig,
        apiKey: String,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        executor.submit {
            try {
                val url = buildChatCompletionsUrl(config.baseUrl)

                val messagesJson = messages.joinToString(",", prefix = "[", postfix = "]") { m ->
                    """{"role":"${m.role}","content":${jsonEscape(m.content)}}"""
                }

                val body = """
                    {
                      "model": "${config.model}",
                      "stream": true,
                      "temperature": ${config.temperature},
                      "messages": $messagesJson
                    }
                """.trimIndent()

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $apiKey")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofLines())

                if (response.statusCode() !in 200..299) {
                    val bodySnippet = try {
                        response.body().limit(10).toList().joinToString("\n")
                    } catch (_: Exception) { "" }
                    onError(RuntimeException("HTTP ${response.statusCode()} 请求地址：$url\n返回内容：${bodySnippet.take(500)}"))
                    return@submit
                }

                response.body().forEach { line ->
                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") return@forEach
                        if (data.isBlank()) return@forEach
                        try {
                            val json = JsonParser.parseString(data).asJsonObject
                            val choices = json.getAsJsonArray("choices")
                            if (choices != null && choices.size() > 0) {
                                val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                                val content = delta?.get("content")
                                if (content != null && !content.isJsonNull) {
                                    onToken(content.asString)
                                }
                            }
                        } catch (_: Exception) {
                            // 忽略无法解析的心跳/空行，不中断整体流程
                        }
                    }
                }
                onComplete()
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    private fun jsonEscape(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
