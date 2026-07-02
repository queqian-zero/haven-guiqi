package com.haven.guiqi

import android.content.Context
import android.net.Uri
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * BackupManager — 导入导出
 *
 * 从 ChatActivity 拆出来。
 * 职责：把好友 + 聊天记录导出成 JSON，或从 JSON 恢复。
 */
class BackupManager(
    private val context: Context,
    private val friendStorage: FriendStorage,
    private val chatStorage: ChatStorage
) {

    /**
     * 导出所有数据到指定 Uri
     */
    fun doExport(uri: Uri) {
        try {
            val friends = friendStorage.loadFriends()

            val friendsArray = JSONArray()
            for (f in friends) {
                val chatMessages = chatStorage.loadMessages(f.id)
                val msgsArray = JSONArray()
                for (msg in chatMessages) {
                    msgsArray.put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                        put("timestamp", msg.timestamp)
                        if (msg.thinking.isNotEmpty()) put("thinking", msg.thinking)
                        if (msg.imagePath.isNotEmpty()) put("image_path", msg.imagePath)
                        if (msg.type != "text") put("type", msg.type)
                        if (msg.extras.isNotEmpty()) put("extras", msg.extras)
                    })
                }

                friendsArray.put(JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    put("group", f.group)
                    put("icon", f.icon)
                    put("bio", f.bio)
                    put("display_code", f.displayCode)
                    put("api_url", f.apiUrl)
                    put("api_key", f.apiKey)
                    put("api_model", f.apiModel)
                    put("api_type", f.apiType)
                    put("dream_api_url", f.dreamApiUrl)
                    put("dream_api_key", f.dreamApiKey)
                    put("dream_api_model", f.dreamApiModel)
                    put("dream_api_type", f.dreamApiType)
                    put("created_at", f.createdAt)
                    put("messages", msgsArray)
                })
            }

            val prefs = context.getSharedPreferences("haven_prefs", Context.MODE_PRIVATE)
            val exportData = JSONObject().apply {
                put("app", "haven_guiqi")
                put("version", "0.1.0")
                put("export_time", System.currentTimeMillis())
                put("user_name", prefs.getString("user_name", "") ?: "")
                put("friends", friendsArray)
            }

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(exportData.toString(2).toByteArray())
            }

            val totalMsgs = friends.sumOf { chatStorage.loadMessages(it.id).size }
            Toast.makeText(context,
                "导出成功 ♡\n${friends.size} 个好友，$totalMsgs 条消息",
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 从指定 Uri 导入数据
     * @return 导入的好友列表（用于外部刷新 UI），失败返回 null
     */
    fun doImport(uri: Uri): List<Friend>? {
        try {
            val jsonStr = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: throw Exception("无法读取文件")

            val data = JSONObject(jsonStr)

            if (data.optString("app") != "haven_guiqi") {
                Toast.makeText(context, "这不是归栖的备份文件", Toast.LENGTH_SHORT).show()
                return null
            }

            // 恢复用户名
            val userName = data.optString("user_name", "")
            if (userName.isNotEmpty()) {
                context.getSharedPreferences("haven_prefs", Context.MODE_PRIVATE)
                    .edit().putString("user_name", userName).apply()
            }

            // 恢复好友和聊天记录
            val friendsArray = data.getJSONArray("friends")
            val friends = mutableListOf<Friend>()

            for (i in 0 until friendsArray.length()) {
                val obj = friendsArray.getJSONObject(i)
                val friend = Friend(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    group = obj.optString("group", "好友"),
                    icon = obj.optString("icon", "★"),
                    bio = obj.optString("bio", ""),
                    displayCode = obj.optString("display_code", ""),
                    apiUrl = obj.optString("api_url", ""),
                    apiKey = obj.optString("api_key", ""),
                    apiModel = obj.optString("api_model", ""),
                    apiType = obj.optString("api_type", "openai"),
                    dreamApiUrl = obj.optString("dream_api_url", ""),
                    dreamApiKey = obj.optString("dream_api_key", ""),
                    dreamApiModel = obj.optString("dream_api_model", ""),
                    dreamApiType = obj.optString("dream_api_type", "openai"),
                    createdAt = obj.optLong("created_at", System.currentTimeMillis())
                )
                friends.add(friend)

                val msgsArray = obj.optJSONArray("messages")
                if (msgsArray != null && msgsArray.length() > 0) {
                    val messages = mutableListOf<StoredMessage>()
                    for (j in 0 until msgsArray.length()) {
                        val msgObj = msgsArray.getJSONObject(j)
                        messages.add(StoredMessage(
                            role = msgObj.getString("role"),
                            content = msgObj.getString("content"),
                            timestamp = msgObj.optLong("timestamp", 0L),
                            thinking = msgObj.optString("thinking", ""),
                            imagePath = msgObj.optString("image_path", ""),
                            type = msgObj.optString("type", "text"),
                            extras = msgObj.optString("extras", "")
                        ))
                    }
                    chatStorage.saveMessages(friend.id, messages)
                }
            }

            friendStorage.saveFriends(friends)

            Toast.makeText(context,
                "导入成功 ♡\n${friends.size} 个好友已恢复",
                Toast.LENGTH_SHORT).show()
            return friends
        } catch (e: Exception) {
            Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            return null
        }
    }
}