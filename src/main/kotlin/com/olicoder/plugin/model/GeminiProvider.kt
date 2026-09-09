package com.olicoder.plugin.model

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
 * 适配 Google Gemini API（streamGenerateContent + SSE）。
 * baseUrl 默认填 https://generativelanguage.googleapis.com ，apiKey 作为 query 参数传递。
 */
class GeminiProvider : ModelProvider {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val executor = Executors.newCachedThreadPool()

    private fun toGeminiContent(m: ChatMessage): JsonObject {
        val obj = JsonObject()
        val role = if (m.role == "assistant") "model" else "user"
        obj.addProperty("role", role)
        val parts = JsonArray()
        if (m.content.isNotBlank()) {
            val textPart = JsonObject()
            textPart.addProperty("text", m.content)
            parts.add(textPart)
        }
        m.images.forEach { img ->
            val imgPart = JsonObject()
            val inlineData = JsonObject()
            inlineData.addProperty("mime_type", img.mimeType)
            inlineData.addProperty("data", img.base64Data)
            imgPart.add("inline_data", inlineData)
            parts.add(imgPart)
        }
        obj.add("parts", parts)
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
                val url = "${config.baseUrl.trim().trimEnd('/')}/v1beta/models/${config.model}:streamGenerateContent?alt=sse&key=$apiKey"

                val systemMsg = messages.firstOrNull { it.role == "system" }?.content
                val convoMessages = messages.filter { it.role != "system" }

                val contentsArray = JsonArray()
                convoMessages.forEach { contentsArray.add(toGeminiContent(it)) }

                val root = JsonObject()
                if (!systemMsg.isNullOrBlank()) {
                    val sysInstruction = JsonObject()
                    val parts = JsonArray()
                    val textPart = JsonObject()
                    textPart.addProperty("text", systemMsg)
                    parts.add(textPart)
                    sysInstruction.add("parts", parts)
                    root.add("system_instruction", sysInstruction)
                }
                root.add("contents", contentsArray)

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
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
                            val candidates = json.getAsJsonArray("candidates")
                            if (candidates != null && candidates.size() > 0) {
                                val parts = candidates[0].asJsonObject
                                    .getAsJsonObject("content")
                                    ?.getAsJsonArray("parts")
                                val text = parts?.firstOrNull()?.asJsonObject?.get("text")
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
