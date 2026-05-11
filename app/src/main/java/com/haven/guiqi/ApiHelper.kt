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
 * 目前支持 OpenAI 格式（/v1/chat/completions）
 * 大多数 AI 平台和中转站都兼容这个格式
 */
class ApiHelper(
    private val apiUrl: String,   // 例如 https://api.usora.net/v1
    private val apiKey: String,
    private val model: String
) {

    /**
     * 发送聊天消息，返回 AI 的回复文本
     *
     * @param messages 聊天历史，每条消息是 { role: "user"/"assistant", content: "..." }
     * @return AI 回复的文本内容
     * @throws Exception 网络错误或 API 错误时抛出异常
     */
    fun sendChat(messages: List<ChatMessage>): String {
        // 拼接 URL：确保以 /chat/completions 结尾
        val chatUrl = if (apiUrl.endsWith("/")) {
            "${apiUrl}chat/completions"
        } else {
            "$apiUrl/chat/completions"
        }

        // 建立连接
        val connection = URL(chatUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 30000   // 30秒超时（AI回复有时候比较慢）
        connection.readTimeout = 60000      // 60秒读取超时
        connection.doOutput = true

        // 构建请求体
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

        // 发送请求
        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()

        // 读取响应
        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            // 解析 JSON，提取 AI 的回复内容
            val json = JSONObject(response)
            val reply = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            return reply
        } else {
            // 读取错误信息
            val errorStream = connection.errorStream
            val errorMsg = if (errorStream != null) {
                try {
                    val errJson = JSONObject(
                        BufferedReader(InputStreamReader(errorStream)).readText()
                    )
                    errJson.optJSONObject("error")?.optString("message")
                        ?: "未知错误 ($responseCode)"
                } catch (e: Exception) {
                    "请求失败 ($responseCode)"
                }
            } else {
                "请求失败 ($responseCode)"
            }
            throw Exception(errorMsg)
        }
    }
}

/**
 * 一条聊天消息
 *
 * role 只有三种值：
 * - "system"    系统指令（告诉 AI 它是谁、怎么行为）
 * - "user"      用户说的话
 * - "assistant" AI 说的话
 */
data class ChatMessage(
    val role: String,
    val content: String
)