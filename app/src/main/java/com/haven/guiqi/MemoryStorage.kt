package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * MemoryStorage - AI 的核心记忆管理
 *
 * AI 可以通过在回复里写 [MEMORY:内容] 来保存一条核心记忆
 * 也可以通过 [FORGET:记忆ID] 来删除一条记忆
 * 也可以通过 [EDIT_MEMORY:记忆ID:新内容] 来修改一条记忆
 *
 * 数据存在 memories/{friendId}.json 里，每个好友单独一个文件
 *
 * JSON 格式：
 * {
 *   "memories": [
 *     {
 *       "id": "MEM-1715000000",
 *       "content": "沈眠喜欢猫",
 *       "createdAt": 1715000000,
 *       "updatedAt": 1715000000
 *     }
 *   ]
 * }
 */
class MemoryStorage(private val context: Context) {

    // 记忆文件存放目录
    private val memoryDir: File
        get() {
            val dir = File(context.filesDir, "memories")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    // 获取某个好友的记忆文件
    private fun getFile(friendId: String): File = File(memoryDir, "$friendId.json")

    /**
     * 添加一条核心记忆
     * @return 新记忆的对象
     */
    fun addMemory(friendId: String, content: String): Memory {
        val memory = Memory(
            id = "MEM-${System.currentTimeMillis()}",
            content = content.trim(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val list = loadMemories(friendId).toMutableList()
        list.add(memory)
        save(friendId, list)
        return memory
    }

    /**
     * 修改一条核心记忆
     */
    fun editMemory(friendId: String, memoryId: String, newContent: String): Boolean {
        val list = loadMemories(friendId).toMutableList()
        val target = list.find { it.id == memoryId } ?: return false
        val index = list.indexOf(target)
        list[index] = target.copy(
            content = newContent.trim(),
            updatedAt = System.currentTimeMillis()
        )
        save(friendId, list)
        return true
    }

    /**
     * 删除一条核心记忆（移到废纸篓）
     * 废纸篓存在 memories/trash_{friendId}.json
     */
    fun deleteMemory(friendId: String, memoryId: String): Boolean {
        val list = loadMemories(friendId).toMutableList()
        val target = list.find { it.id == memoryId } ?: return false
        list.remove(target)
        save(friendId, list)

        // 放进废纸篓
        val trash = loadTrash(friendId).toMutableList()
        trash.add(target)
        saveTrash(friendId, trash)
        return true
    }

    /**
     * 加载所有核心记忆（按创建时间排序，最早的在前面）
     */
    fun loadMemories(friendId: String): List<Memory> {
        val file = getFile(friendId)
        if (!file.exists()) return emptyList()

        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("memories")
            val list = mutableListOf<Memory>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(Memory(
                    id = obj.getString("id"),
                    content = obj.getString("content"),
                    createdAt = obj.optLong("createdAt", 0L),
                    updatedAt = obj.optLong("updatedAt", 0L)
                ))
            }
            list.sortedBy { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 把所有记忆拼成一段文字，塞给 AI 的 system prompt
     * 如果没有记忆就返回空字符串
     */
    fun buildMemoryPrompt(friendId: String): String {
        val memories = loadMemories(friendId)
        if (memories.isEmpty()) return "\n\n[我的核心记忆]\n还没有记过什么。想记的时候用 [MEMORY:内容] 就行。"

        val sb = StringBuilder("\n\n[我的核心记忆]\n")
        for ((index, m) in memories.withIndex()) {
            sb.append("${index + 1}. [${m.id}] ${m.content}\n")
        }
        sb.append("\n写新记忆: [MEMORY:内容]  |  删记忆: [FORGET:记忆ID]  |  改记忆: [EDIT_MEMORY:记忆ID:新内容]\n")
        sb.append("删掉的会去废纸篓，不会真的消失。")
        return sb.toString()
    }

    /**
     * 从 AI 的回复里提取记忆指令并执行
     *
     * 支持三种指令：
     *   [MEMORY:要记住的内容]      → 保存新记忆
     *   [FORGET:MEM-xxx]           → 删除记忆
     *   [EDIT_MEMORY:MEM-xxx:新内容] → 修改记忆
     *
     * @return 去掉指令后的纯文本（给用户看的部分）
     */
    fun processAiResponse(friendId: String, response: String): ProcessResult {
        var text = response
        val actions = mutableListOf<String>()

        // 提取 [MEMORY:xxx]
        val memoryPattern = Regex("\\[MEMORY:(.+?)]")
        memoryPattern.findAll(response).forEach { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotEmpty()) {
                val mem = addMemory(friendId, content)
                actions.add("📌 记住了: $content")
            }
            text = text.replace(match.value, "")
        }

        // 提取 [FORGET:xxx]
        val forgetPattern = Regex("\\[FORGET:(.+?)]")
        forgetPattern.findAll(response).forEach { match ->
            val memId = match.groupValues[1].trim()
            if (deleteMemory(friendId, memId)) {
                actions.add("🗑️ 忘掉了一条记忆")
            }
            text = text.replace(match.value, "")
        }

        // 提取 [EDIT_MEMORY:xxx:yyy]
        val editPattern = Regex("\\[EDIT_MEMORY:(.+?):(.+?)]")
        editPattern.findAll(response).forEach { match ->
            val memId = match.groupValues[1].trim()
            val newContent = match.groupValues[2].trim()
            if (editMemory(friendId, memId, newContent)) {
                actions.add("✏️ 修改了记忆: $newContent")
            }
            text = text.replace(match.value, "")
        }

        return ProcessResult(text.trim(), actions)
    }

    /**
     * 记忆总数
     */
    fun count(friendId: String): Int = loadMemories(friendId).size

    // ===== 废纸篓相关 =====

    private fun getTrashFile(friendId: String): File = File(memoryDir, "trash_$friendId.json")

    fun loadTrash(friendId: String): List<Memory> {
        val file = getTrashFile(friendId)
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("memories")
            val list = mutableListOf<Memory>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(Memory(
                    id = obj.getString("id"),
                    content = obj.getString("content"),
                    createdAt = obj.optLong("createdAt", 0L),
                    updatedAt = obj.optLong("updatedAt", 0L)
                ))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 从废纸篓恢复一条记忆
     */
    fun restoreFromTrash(friendId: String, memoryId: String): Boolean {
        val trash = loadTrash(friendId).toMutableList()
        val target = trash.find { it.id == memoryId } ?: return false
        trash.remove(target)
        saveTrash(friendId, trash)

        val list = loadMemories(friendId).toMutableList()
        list.add(target)
        save(friendId, list)
        return true
    }

    /**
     * 从废纸篓永久删除
     */
    fun permanentDelete(friendId: String, memoryId: String): Boolean {
        val trash = loadTrash(friendId).toMutableList()
        val target = trash.find { it.id == memoryId } ?: return false
        trash.remove(target)
        saveTrash(friendId, trash)
        return true
    }

    // ===== 内部保存方法 =====

    private fun save(friendId: String, list: List<Memory>) {
        val array = JSONArray()
        for (m in list) {
            array.put(JSONObject().apply {
                put("id", m.id)
                put("content", m.content)
                put("createdAt", m.createdAt)
                put("updatedAt", m.updatedAt)
            })
        }
        getFile(friendId).writeText(JSONObject().apply {
            put("memories", array)
        }.toString())
    }

    private fun saveTrash(friendId: String, list: List<Memory>) {
        val array = JSONArray()
        for (m in list) {
            array.put(JSONObject().apply {
                put("id", m.id)
                put("content", m.content)
                put("createdAt", m.createdAt)
                put("updatedAt", m.updatedAt)
            })
        }
        getTrashFile(friendId).writeText(JSONObject().apply {
            put("memories", array)
        }.toString())
    }
}

/**
 * 一条核心记忆
 */
data class Memory(
    val id: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * AI 回复处理结果
 * text = 去掉指令后给用户看的纯文本
 * actions = 执行了哪些记忆操作（用于显示小提示）
 */
data class ProcessResult(
    val text: String,
    val actions: List<String>
)