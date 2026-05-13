package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ChatStorage - 聊天记录的本地存储
 *
 * 每个好友的聊天记录存成一个 JSON 文件
 * 文件位置：应用内部存储/chat_logs/好友ID.json
 *
 * 文件格式：
 * {
 *   "messages": [
 *     {
 *       "role": "user",           // user / assistant / system
 *       "content": "你好呀",       // 消息内容
 *       "timestamp": 1715000000   // 发送时间（毫秒时间戳）
 *     },
 *     ...
 *   ]
 * }
 */
class ChatStorage(private val context: Context) {

    // 聊天记录存储的文件夹
    private val chatDir: File
        get() {
            val dir = File(context.filesDir, "chat_logs")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    /**
     * 保存整个聊天记录
     * @param friendId 好友的唯一标识（目前先用名字，以后换成编码）
     * @param messages 所有消息
     */
    fun saveMessages(friendId: String, messages: List<StoredMessage>) {
        val jsonArray = JSONArray()
        for (msg in messages) {
            jsonArray.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
                put("timestamp", msg.timestamp)
                if (msg.thinking.isNotEmpty()) {
                    put("thinking", msg.thinking)
                }
                if (msg.imagePath.isNotEmpty()) {
                    put("image_path", msg.imagePath)
                }
            })
        }

        val json = JSONObject().apply {
            put("messages", jsonArray)
        }

        val file = File(chatDir, "${sanitizeId(friendId)}.json")
        file.writeText(json.toString())
    }

    /**
     * 读取聊天记录
     * @param friendId 好友的唯一标识
     * @return 消息列表，如果没有记录则返回空列表
     */
    fun loadMessages(friendId: String): List<StoredMessage> {
        val file = File(chatDir, "${sanitizeId(friendId)}.json")
        if (!file.exists()) return emptyList()

        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("messages")
            val messages = mutableListOf<StoredMessage>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                messages.add(StoredMessage(
                    role = obj.getString("role"),
                    content = obj.getString("content"),
                    timestamp = obj.optLong("timestamp", 0L),
                    thinking = obj.optString("thinking", ""),
                    imagePath = obj.optString("image_path", "")
                ))
            }

            messages
        } catch (e: Exception) {
            // 文件损坏就返回空列表，不崩溃
            emptyList()
        }
    }

    /**
     * 追加一条消息（不用每次都重写整个文件）
     * 实际上还是读取-追加-保存，但对外接口更方便
     */
    fun appendMessage(friendId: String, message: StoredMessage) {
        val existing = loadMessages(friendId).toMutableList()
        existing.add(message)
        saveMessages(friendId, existing)
    }

    /**
     * 删除某个好友的全部聊天记录
     */
    fun deleteMessages(friendId: String) {
        val file = File(chatDir, "${sanitizeId(friendId)}.json")
        if (file.exists()) file.delete()
    }

    /**
     * 把好友ID处理成安全的文件名（去掉特殊字符）
     */
    private fun sanitizeId(id: String): String {
        return id.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fff_-]"), "_")
    }
}

/**
 * 存储用的消息格式
 * 跟 ChatMessage 的区别是多了一个 timestamp（时间戳）
 */
data class StoredMessage(
    val role: String,           // "user" / "assistant" / "system"
    val content: String,        // 消息内容
    val timestamp: Long,        // 发送时间（毫秒）
    val thinking: String = "",  // AI 的思考过程（可能为空）
    val imagePath: String = ""  // 图片路径（可能为空）
)