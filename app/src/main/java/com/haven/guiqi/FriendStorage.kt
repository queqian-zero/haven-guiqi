package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * FriendStorage - 好友数据管理
 *
 * 所有好友信息存在一个 JSON 文件里：friends.json
 * 每个好友有唯一的 id（编码），用来关联聊天记录
 *
 * 文件格式：
 * {
 *   "friends": [
 *     {
 *       "id": "HV-A3F8",           // 好友编码（唯一标识）
 *       "name": "星河",             // 显示名称
 *       "group": "好友",            // 分组
 *       "icon": "★",               // 头像字符（以后换成图片路径）
 *       "bio": "",                  // AI 简介（自我认识）
 *       "api_url": "",             // 单独的 API 地址（空则用全局）
 *       "api_key": "",             // 单独的 API 密钥
 *       "api_model": "",           // 单独的模型名
 *       "created_at": 1715000000   // 创建时间
 *     }
 *   ]
 * }
 */
class FriendStorage(private val context: Context) {

    private val file: File
        get() = File(context.filesDir, "friends.json")

    // ===== 读取所有好友 =====
    fun loadFriends(): List<Friend> {
        if (!file.exists()) return emptyList()

        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("friends")
            val friends = mutableListOf<Friend>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                friends.add(Friend(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    group = obj.optString("group", "好友"),
                    icon = obj.optString("icon", "★"),
                    bio = obj.optString("bio", ""),
                    apiUrl = obj.optString("api_url", ""),
                    apiKey = obj.optString("api_key", ""),
                    apiModel = obj.optString("api_model", ""),
                    apiType = obj.optString("api_type", "openai"),
                    createdAt = obj.optLong("created_at", System.currentTimeMillis())
                ))
            }

            friends
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ===== 保存所有好友 =====
    fun saveFriends(friends: List<Friend>) {
        val array = JSONArray()
        for (f in friends) {
            array.put(JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("group", f.group)
                put("icon", f.icon)
                put("bio", f.bio)
                put("api_url", f.apiUrl)
                put("api_key", f.apiKey)
                put("api_model", f.apiModel)
                put("api_type", f.apiType)
                put("created_at", f.createdAt)
            })
        }

        val json = JSONObject().apply {
            put("friends", array)
        }
        file.writeText(json.toString())
    }

    // ===== 添加好友（返回新好友的对象） =====
    fun addFriend(name: String, group: String = "好友"): Friend {
        val friends = loadFriends().toMutableList()
        val newFriend = Friend(
            id = generateCode(),
            name = name,
            group = group,
            icon = "★",
            createdAt = System.currentTimeMillis()
        )
        friends.add(newFriend)
        saveFriends(friends)
        return newFriend
    }

    // ===== 更新好友信息 =====
    fun updateFriend(updatedFriend: Friend) {
        val friends = loadFriends().toMutableList()
        val index = friends.indexOfFirst { it.id == updatedFriend.id }
        if (index >= 0) {
            friends[index] = updatedFriend
            saveFriends(friends)
        }
    }

    // ===== 删除好友 =====
    fun deleteFriend(friendId: String) {
        val friends = loadFriends().toMutableList()
        friends.removeAll { it.id == friendId }
        saveFriends(friends)

        // 同时删除聊天记录
        ChatStorage(context).deleteMessages(friendId)
    }

    // ===== 根据 ID 查找好友 =====
    fun getFriend(friendId: String): Friend? {
        return loadFriends().find { it.id == friendId }
    }

    // ===== 生成好友编码（格式：HV-XXXX） =====
    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = (1..4).map { chars.random() }.joinToString("")
        return "HV-$code"
    }
}

/**
 * 好友数据
 */
data class Friend(
    val id: String,                // 好友编码（唯一标识）
    val name: String,              // 显示名称
    val group: String = "好友",     // 分组
    val icon: String = "★",        // 头像字符
    val bio: String = "",          // AI 简介
    val apiUrl: String = "",       // 单独 API（空则用全局配置）
    val apiKey: String = "",
    val apiModel: String = "",
    val apiType: String = "openai", // "openai" / "claude" / "gemini"
    val createdAt: Long = System.currentTimeMillis()
)