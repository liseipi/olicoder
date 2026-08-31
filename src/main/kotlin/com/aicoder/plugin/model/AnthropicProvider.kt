package com.aicoder.plugin.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

/**
 * 适配 Anthropic 官方 Messages API（/v1/messages）。
 * 与 OpenAI 协议的主要差异：
 *   1. system prompt 是独立字段，不放在 messages 数组里
 *   2. 鉴权用 x-api-key，而不是 Authorization: Bearer
 *   3. 流式事件格式是具名事件（event: content_block_delta），不是纯 data: 行
 */
class AnthropicProvider : ModelProvider {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val executor = Executors.newCachedThreadPool()

    private fun buildMessagesUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/v1/messages")) base else "$base/v1/messages"
    }

    private fun toAnthropicMessage(m: ChatMessage): JsonObject {
        val obj = JsonObject()
        obj.addProperty("role", m.role)
        val contentArray = JsonArray()
        if (m.content.isNotBlank()) {
            val textPart = JsonObject()
            textPart.addProperty("type", "text")
            textPart.addProperty("text", m.content)
            contentArray.add(textPart)
        }
        m.images.forEach { img ->
            val imgPart = JsonObject()
            imgPart.addProperty("type", "image")
            val source = JsonObject()
            source.addProperty("type", "base64")
            source.addProperty("media_type", img.mimeType)
            source.addProperty("data", img.base64Data)
            imgPart.add("source", source)
            contentArray.add(imgPart)
        }
        obj.add("content", contentArray)
        return obj
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
                val url = buildMessagesUrl(config.baseUrl)

                val systemMsg = messages.firstOrNull { it.role == "system" }?.content ?: ""
                val convoMessages = messages.filter { it.role != "system" }

                val messagesArray = JsonArray()
                convoMessages.forEach { messagesArray.add(toAnthropicMessage(it)) }

                val root = JsonObject()
                root.addProperty("model", config.model)
                root.addProperty("max_tokens", 4096)
                root.addProperty("stream", true)
                root.addProperty("temperature", config.temperature)
                if (systemMsg.isNotBlank()) root.addProperty("system", systemMsg)
                root.add("messages", messagesArray)

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
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
                        if (data.isBlank()) return@forEach
                        try {
                            val json = JsonParser.parseString(data).asJsonObject
                            val type = json.get("type")?.asString
                            if (type == "content_block_delta") {
                                val delta = json.getAsJsonObject("delta")
                                val text = delta?.get("text")
                                if (text != null && !text.isJsonNull) {
                                    onToken(text.asString)
                                }
                            }
                        } catch (_: Exception) {
                            // 忽略无法解析的事件行
                        }
                    }
                }
                onComplete()
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}
