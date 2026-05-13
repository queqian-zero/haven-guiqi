package com.haven.guiqi

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * API 回复结果
 * thinking = AI 的思考过程（可能为空）
 * text = AI 的正式回复
 */
data class ApiResponse(
    val thinking: String,
    val text: String
)

/**
 * ApiHelper - 负责跟 AI 的 API 通信
 * 支持 OpenAI / Claude / Gemini 三种格式
 * 自动提取思维链（thinking/reasoning）
 */
class ApiHelper(
    private val apiUrl: String,
    private val apiKey: String,
    private val model: String,
    private val apiType: String = "openai"
) {

    fun sendChat(messages: List<ChatMessage>): ApiResponse {
        return when (apiType) {
            "claude" -> sendClaude(messages)
            "gemini" -> sendGemini(messages)
            else -> sendOpenAI(messages)
        }
    }

    // ===== OpenAI 格式 =====
    private fun sendOpenAI(messages: List<ChatMessage>): ApiResponse {
        val chatUrl = if (apiUrl.endsWith("/")) {
            "${apiUrl}chat/completions"
        } else {
            "$apiUrl/chat/completions"
        }

        val connection = URL(chatUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.doOutput = true

        val messagesArray = JSONArray()
        for (msg in messages) {
            if (msg.imageBase64.isNotEmpty()) {
                // 带图片的消息：content 用数组格式
                val contentArray = JSONArray()
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,${msg.imageBase64}")
                    })
                })
                if (msg.content.isNotEmpty()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "text")
                        put("text", msg.content)
                    })
                }
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", contentArray)
                })
            } else {
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
        }

        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()

        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            val json = JSONObject(response)
            val choice = json.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val content = message.getString("content")

            // DeepSeek 等模型有 reasoning_content 字段
            val reasoning = message.optString("reasoning_content", "")

            if (reasoning.isNotEmpty()) {
                return ApiResponse(thinking = reasoning, text = content)
            }

            // 有些模型用 <think> 标签包裹思考过程
            return extractThinkTags(content)
        } else {
            throw Exception(readError(connection, responseCode))
        }
    }

    // ===== Claude 原生格式 =====
    private fun sendClaude(messages: List<ChatMessage>): ApiResponse {
        val baseUrl = apiUrl.trimEnd('/')
        val chatUrl = if (baseUrl.endsWith("/v1/messages")) {
            baseUrl
        } else if (baseUrl.endsWith("/v1")) {
            "$baseUrl/messages"
        } else {
            "$baseUrl/v1/messages"
        }

        val connection = URL(chatUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", "2023-06-01")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 30000
        connection.readTimeout = 120000  // Claude 思考时间可能较长
        connection.doOutput = true

        var systemContent = ""
        val messagesArray = JSONArray()
        for (msg in messages) {
            if (msg.role == "system") {
                if (systemContent.isNotEmpty()) systemContent += "\n"
                systemContent += msg.content
            } else if (msg.imageBase64.isNotEmpty()) {
                // Claude 图片格式
                val contentArray = JSONArray()
                contentArray.put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", msg.imageBase64)
                    })
                })
                if (msg.content.isNotEmpty()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "text")
                        put("text", msg.content)
                    })
                }
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", contentArray)
                })
            } else {
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 16000)
            put("messages", messagesArray)
            if (systemContent.isNotEmpty()) {
                put("system", systemContent)
            }
            // 启用扩展思维（extended thinking）
            // 支持思维链的模型会返回 thinking 块，不支持的会忽略
            put("thinking", JSONObject().apply {
                put("type", "enabled")
                put("budget_tokens", 10000)
            })
        }

        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()

        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            val json = JSONObject(response)
            val contentArray = json.getJSONArray("content")

            var thinking = ""
            var text = ""

            for (i in 0 until contentArray.length()) {
                val block = contentArray.getJSONObject(i)
                when (block.getString("type")) {
                    "thinking" -> thinking += block.getString("thinking")
                    "text" -> text += block.getString("text")
                }
            }

            return ApiResponse(thinking = thinking, text = text)
        } else {
            // 如果开启 thinking 导致报错，重试不带 thinking
            val errorMsg = readError(connection, responseCode)
            if (errorMsg.contains("thinking") || errorMsg.contains("not supported")) {
                return sendClaudeWithoutThinking(messages, chatUrl, systemContent, messagesArray)
            }
            throw Exception(errorMsg)
        }
    }

    // Claude 不支持 thinking 时的降级方案
    private fun sendClaudeWithoutThinking(
        messages: List<ChatMessage>,
        chatUrl: String,
        systemContent: String,
        messagesArray: JSONArray
    ): ApiResponse {
        val connection = URL(chatUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", "2023-06-01")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.doOutput = true

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 8192)
            put("messages", messagesArray)
            if (systemContent.isNotEmpty()) {
                put("system", systemContent)
            }
        }

        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()

        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            val json = JSONObject(response)
            val contentArray = json.getJSONArray("content")
            val textParts = StringBuilder()
            for (i in 0 until contentArray.length()) {
                val block = contentArray.getJSONObject(i)
                if (block.getString("type") == "text") {
                    textParts.append(block.getString("text"))
                }
            }
            return ApiResponse(thinking = "", text = textParts.toString())
        } else {
            throw Exception(readError(connection, responseCode))
        }
    }

    // ===== Gemini 原生格式 =====
    private fun sendGemini(messages: List<ChatMessage>): ApiResponse {
        val baseUrl = apiUrl.trimEnd('/')
        val chatUrl = "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey"

        val connection = URL(chatUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.doOutput = true

        var systemContent = ""
        val contentsArray = JSONArray()
        for (msg in messages) {
            if (msg.role == "system") {
                if (systemContent.isNotEmpty()) systemContent += "\n"
                systemContent += msg.content
            } else {
                val geminiRole = if (msg.role == "assistant") "model" else "user"
                val partsArray = JSONArray()
                // Gemini 图片格式
                if (msg.imageBase64.isNotEmpty()) {
                    partsArray.put(JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", msg.imageBase64)
                        })
                    })
                }
                if (msg.content.isNotEmpty()) {
                    partsArray.put(JSONObject().apply {
                        put("text", msg.content)
                    })
                }
                contentsArray.put(JSONObject().apply {
                    put("role", geminiRole)
                    put("parts", partsArray)
                })
            }
        }

        val body = JSONObject().apply {
            put("contents", contentsArray)
            if (systemContent.isNotEmpty()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemContent)
                        })
                    })
                })
            }
            // Gemini 2.5 开启思考
            put("generationConfig", JSONObject().apply {
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", 8000)
                })
            })
        }

        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()

        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            val json = JSONObject(response)
            val parts = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")

            var thinking = ""
            var text = ""

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("thought") && part.getBoolean("thought")) {
                    // Gemini 思考部分有 thought: true 标记
                    thinking += part.getString("text")
                } else if (part.has("text")) {
                    text += part.getString("text")
                }
            }

            // 如果没有 thought 标记，尝试提取 <think> 标签
            if (thinking.isEmpty()) {
                val extracted = extractThinkTags(text)
                return extracted
            }

            return ApiResponse(thinking = thinking, text = text)
        } else {
            throw Exception(readError(connection, responseCode))
        }
    }

    // ===== 从文本中提取 <think> 标签 =====
    private fun extractThinkTags(content: String): ApiResponse {
        // 匹配 <think>...</think> 或 <thinking>...</thinking>
        val thinkRegex = Regex("<think(?:ing)?>(.*?)</think(?:ing)?>", RegexOption.DOT_MATCHES_ALL)
        val match = thinkRegex.find(content)

        return if (match != null) {
            val thinking = match.groupValues[1].trim()
            val text = content.replace(match.value, "").trim()
            ApiResponse(thinking = thinking, text = text)
        } else {
            ApiResponse(thinking = "", text = content)
        }
    }

    // ===== 读取错误信息 =====
    private fun readError(connection: HttpURLConnection, code: Int): String {
        val errorStream = connection.errorStream ?: return "请求失败 ($code)"
        return try {
            val errText = BufferedReader(InputStreamReader(errorStream)).readText()
            val errJson = JSONObject(errText)
            errJson.optJSONObject("error")?.optString("message")
                ?: errJson.optString("message")
                ?: "请求失败 ($code)"
        } catch (e: Exception) {
            "请求失败 ($code)"
        }
    }
}

data class ChatMessage(
    val role: String,
    val content: String,
    val imageBase64: String = ""  // 图片的 base64（可选，发图时用）
)