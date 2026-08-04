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

/** 用户主动停止本轮回复时使用，不应显示成网络错误。 */
class ApiRequestCancelledException : Exception("本轮回复已停止")

/**
 * ApiHelper - 负责跟 AI 的 API 通信
 * 支持 OpenAI / Claude / Gemini 三种格式
 * 自动提取思维链（thinking/reasoning）
 *
 * 每个实例只服务一轮连续工具会话。cancel() 会断开当前连接，后续同轮请求也会立即拒绝，
 * 因而可以把“第一次回复 → 工具调用 → 工具结果回传”整条链一起掐断。
 */
class ApiHelper(
    private val apiUrl: String,
    private val apiKey: String,
    private val model: String,
    private val apiType: String = "openai"
) {

    @Volatile
    private var cancelled = false

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        runCatching { activeConnection?.disconnect() }
    }

    fun sendChat(messages: List<ChatMessage>): ApiResponse {
        ensureActive()
        return when (apiType) {
            "claude" -> sendClaude(messages)
            "gemini" -> sendGemini(messages)
            else -> sendOpenAI(messages)
        }
    }

    private fun ensureActive() {
        if (cancelled || Thread.currentThread().isInterrupted) {
            throw ApiRequestCancelledException()
        }
    }

    private fun track(connection: HttpURLConnection): HttpURLConnection {
        ensureActive()
        activeConnection = connection
        return connection
    }

    private inline fun <T> useConnection(
        connection: HttpURLConnection,
        block: (HttpURLConnection) -> T
    ): T {
        try {
            ensureActive()
            return block(connection)
        } catch (e: Exception) {
            if (cancelled || Thread.currentThread().isInterrupted) {
                throw ApiRequestCancelledException()
            }
            throw e
        } finally {
            if (activeConnection === connection) activeConnection = null
            runCatching { connection.disconnect() }
        }
    }

    // ===== OpenAI 格式 =====
    private fun sendOpenAI(messages: List<ChatMessage>): ApiResponse {
        val chatUrl = if (apiUrl.endsWith("/")) {
            "${apiUrl}chat/completions"
        } else {
            "$apiUrl/chat/completions"
        }

        val connection = track(URL(chatUrl).openConnection() as HttpURLConnection)
        return useConnection(connection) { conn ->
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.doOutput = true

            val messagesArray = JSONArray()
            for (msg in messages) {
                if (msg.imageBase64List.isNotEmpty()) {
                    val contentArray = JSONArray()
                    for (base64 in msg.imageBase64List) {
                        contentArray.put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64")
                            })
                        })
                    }
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

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }
            ensureActive()

            val responseCode = conn.responseCode
            ensureActive()
            if (responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                ensureActive()

                val json = JSONObject(response)
                val choice = json.getJSONArray("choices").getJSONObject(0)
                val message = choice.getJSONObject("message")
                val content = message.getString("content")
                val reasoning = message.optString("reasoning_content", "")

                if (reasoning.isNotEmpty()) {
                    ApiResponse(thinking = reasoning, text = content)
                } else {
                    extractThinkTags(content)
                }
            } else {
                throw Exception(readError(conn, responseCode))
            }
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

        val connection = track(URL(chatUrl).openConnection() as HttpURLConnection)
        return useConnection(connection) { conn ->
            conn.requestMethod = "POST"
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 30000
            conn.readTimeout = 120000
            conn.doOutput = true

            var systemContent = ""
            val messagesArray = JSONArray()
            for (msg in messages) {
                if (msg.role == "system") {
                    if (systemContent.isNotEmpty()) systemContent += "\n"
                    systemContent += msg.content
                } else if (msg.imageBase64List.isNotEmpty()) {
                    val contentArray = JSONArray()
                    for (base64 in msg.imageBase64List) {
                        contentArray.put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", base64)
                            })
                        })
                    }
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
                if (systemContent.isNotEmpty()) put("system", systemContent)
                put("thinking", JSONObject().apply {
                    put("type", "enabled")
                    put("budget_tokens", 10000)
                })
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }
            ensureActive()

            val responseCode = conn.responseCode
            ensureActive()
            if (responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                ensureActive()
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
                ApiResponse(thinking = thinking, text = text)
            } else {
                val errorMsg = readError(conn, responseCode)
                ensureActive()
                if (errorMsg.contains("thinking") || errorMsg.contains("not supported")) {
                    sendClaudeWithoutThinking(chatUrl, systemContent, messagesArray)
                } else {
                    throw Exception(errorMsg)
                }
            }
        }
    }

    // Claude 不支持 thinking 时的降级方案
    private fun sendClaudeWithoutThinking(
        chatUrl: String,
        systemContent: String,
        messagesArray: JSONArray
    ): ApiResponse {
        ensureActive()
        val connection = track(URL(chatUrl).openConnection() as HttpURLConnection)
        return useConnection(connection) { conn ->
            conn.requestMethod = "POST"
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.doOutput = true

            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 8192)
                put("messages", messagesArray)
                if (systemContent.isNotEmpty()) put("system", systemContent)
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }
            ensureActive()

            val responseCode = conn.responseCode
            ensureActive()
            if (responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                ensureActive()
                val json = JSONObject(response)
                val contentArray = json.getJSONArray("content")
                val textParts = StringBuilder()
                for (i in 0 until contentArray.length()) {
                    val block = contentArray.getJSONObject(i)
                    if (block.getString("type") == "text") {
                        textParts.append(block.getString("text"))
                    }
                }
                ApiResponse(thinking = "", text = textParts.toString())
            } else {
                throw Exception(readError(conn, responseCode))
            }
        }
    }

    // ===== Gemini 原生格式 =====
    private fun sendGemini(messages: List<ChatMessage>): ApiResponse {
        val baseUrl = apiUrl.trimEnd('/')
        val chatUrl = "$baseUrl/v1beta/models/$model:generateContent"

        val connection = track(URL(chatUrl).openConnection() as HttpURLConnection)
        return useConnection(connection) { conn ->
            conn.requestMethod = "POST"
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.doOutput = true

            var systemContent = ""
            val contentsArray = JSONArray()
            for (msg in messages) {
                if (msg.role == "system") {
                    if (systemContent.isNotEmpty()) systemContent += "\n"
                    systemContent += msg.content
                } else {
                    val geminiRole = if (msg.role == "assistant") "model" else "user"
                    val partsArray = JSONArray()
                    for (base64 in msg.imageBase64List) {
                        partsArray.put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64)
                            })
                        })
                    }
                    if (msg.content.isNotEmpty()) {
                        partsArray.put(JSONObject().apply { put("text", msg.content) })
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
                            put(JSONObject().apply { put("text", systemContent) })
                        })
                    })
                }
                put("generationConfig", JSONObject().apply {
                    put("thinkingConfig", JSONObject().apply {
                        put("thinkingBudget", 8000)
                    })
                })
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }
            ensureActive()

            val responseCode = conn.responseCode
            ensureActive()
            if (responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                ensureActive()
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
                        thinking += part.getString("text")
                    } else if (part.has("text")) {
                        text += part.getString("text")
                    }
                }

                if (thinking.isEmpty()) extractThinkTags(text)
                else ApiResponse(thinking = thinking, text = text)
            } else {
                throw Exception(readError(conn, responseCode))
            }
        }
    }

    // ===== 从文本中提取 <think> 标签 =====
    private fun extractThinkTags(content: String): ApiResponse {
        val thinkRegex = Regex("<think(?:ing)?>(.*?)</think(?:ing)?>", RegexOption.DOT_MATCHES_ALL)
        val matches = thinkRegex.findAll(content).toList()

        return if (matches.isNotEmpty()) {
            val thinking = matches.joinToString("\n\n") { it.groupValues[1].trim() }
            val text = thinkRegex.replace(content, "").trim()
            ApiResponse(thinking = thinking, text = text)
        } else {
            ApiResponse(thinking = "", text = content)
        }
    }

    // ===== 读取错误信息 =====
    private fun readError(connection: HttpURLConnection, code: Int): String {
        ensureActive()
        val errorStream = connection.errorStream ?: return "请求失败 ($code)"
        return try {
            val errText = BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
            val errJson = JSONObject(errText)
            val msg1 = errJson.optJSONObject("error")?.optString("message") ?: ""
            val msg2 = errJson.optString("message")
            when {
                msg1.isNotEmpty() -> msg1
                msg2.isNotEmpty() -> msg2
                else -> "请求失败 ($code): ${errText.take(120)}"
            }
        } catch (e: Exception) {
            ensureActive()
            "请求失败 ($code)"
        }
    }
}

data class ChatMessage(
    val role: String,
    val content: String,
    val imageBase64List: List<String> = emptyList()
)
