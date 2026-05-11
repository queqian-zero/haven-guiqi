package com.haven.guiqi

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ApiHelper - 负责跟 AI 的 API 通信
 *
 * 支持三种格式：
 * - "openai"  → OpenAI 格式（GPT、DeepSeek、通义千问、Moonshot、中转站）
 * - "claude"  → Claude 原生格式（api.anthropic.com）
 * - "gemini"  → Gemini 原生格式（generativelanguage.googleapis.com）
 */
class ApiHelper(
    private val apiUrl: String,
    private val apiKey: String,
    private val model: String,
    private val apiType: String = "openai"  // "openai" / "claude" / "gemini"
) {

    fun sendChat(messages: List<ChatMessage>): String {
        return when (apiType) {
            "claude" -> sendClaude(messages)
            "gemini" -> sendGemini(messages)
            else -> sendOpenAI(messages)
        }
    }

    // ===== OpenAI 格式 =====
    // 适用于：GPT、DeepSeek、通义千问、Moonshot、各种中转站
    // 地址格式：https://api.openai.com/v1 或 https://中转站/v1
    private fun sendOpenAI(messages: List<ChatMessage>): String {
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
            messagesArray.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
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
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } else {
            throw Exception(readError(connection, responseCode))
        }
    }

    // ===== Claude 原生格式 =====
    // 适用于：api.anthropic.com 直连
    // 地址格式：https://api.anthropic.com（不需要加 /v1）
    private fun sendClaude(messages: List<ChatMessage>): String {
        // 拼接 URL
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
        // Claude 用 x-api-key 而不是 Bearer token
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", "2023-06-01")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.doOutput = true

        // Claude 的 system 消息要单独放，不能混在 messages 里
        var systemContent = ""
        val messagesArray = JSONArray()
        for (msg in messages) {
            if (msg.role == "system") {
                // 多条 system 消息合并
                if (systemContent.isNotEmpty()) systemContent += "\n"
                systemContent += msg.content
            } else {
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }

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

            // Claude 的响应格式：{ content: [{ type: "text", text: "..." }] }
            val json = JSONObject(response)
            val contentArray = json.getJSONArray("content")
            val textParts = StringBuilder()
            for (i in 0 until contentArray.length()) {
                val block = contentArray.getJSONObject(i)
                if (block.getString("type") == "text") {
                    textParts.append(block.getString("text"))
                }
            }
            return textParts.toString()
        } else {
            throw Exception(readError(connection, responseCode))
        }
    }

    // ===== Gemini 原生格式 =====
    // 适用于：generativelanguage.googleapis.com 直连
    // 地址格式：https://generativelanguage.googleapis.com
    private fun sendGemini(messages: List<ChatMessage>): String {
        // Gemini 的 URL 格式比较特殊，model 名字嵌在路径里，key 放在参数里
        val baseUrl = apiUrl.trimEnd('/')
        val chatUrl = "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey"

        val connection = URL(chatUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.doOutput = true

        // Gemini 用 "user" 和 "model" 作为角色名（不是 "assistant"）
        // system 消息放在 systemInstruction 里
        var systemContent = ""
        val contentsArray = JSONArray()
        for (msg in messages) {
            if (msg.role == "system") {
                if (systemContent.isNotEmpty()) systemContent += "\n"
                systemContent += msg.content
            } else {
                val geminiRole = if (msg.role == "assistant") "model" else "user"
                contentsArray.put(JSONObject().apply {
                    put("role", geminiRole)
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", msg.content)
                        })
                    })
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

            // Gemini 的响应：{ candidates: [{ content: { parts: [{ text: "..." }] } }] }
            val json = JSONObject(response)
            return json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        } else {
            throw Exception(readError(connection, responseCode))
        }
    }

    // ===== 读取错误信息 =====
    private fun readError(connection: HttpURLConnection, code: Int): String {
        val errorStream = connection.errorStream ?: return "请求失败 ($code)"
        return try {
            val errText = BufferedReader(InputStreamReader(errorStream)).readText()
            val errJson = JSONObject(errText)
            // 不同平台的错误格式不一样，多试几种
            errJson.optJSONObject("error")?.optString("message")
                ?: errJson.optString("message")
                ?: "请求失败 ($code)"
        } catch (e: Exception) {
            "请求失败 ($code)"
        }
    }
}

/**
 * 一条聊天消息
 */
data class ChatMessage(
    val role: String,    // "system" / "user" / "assistant"
    val content: String
)