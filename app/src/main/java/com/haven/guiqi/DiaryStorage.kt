package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * DiaryStorage - AI 的日记管理
 *
 * AI 通过在回复里写指令来操作日记：
 *   [DIARY:今天的内容]           → 写一篇日记
 *   [EDIT_DIARY:DRY-xxx:新内容]  → 修改日记
 *   [DELETE_DIARY:DRY-xxx]       → 删除日记（进废纸篓）
 *
 * 跟核心记忆的区别：
 * - 核心记忆是碎片化的关键信息（"沈眠喜欢猫"）
 * - 日记是完整的一段感想，带日期，像真的日记本
 *
 * 数据存在 diaries/{friendId}.json 里
 *
 * JSON 格式：
 * {
 *   "diaries": [
 *     {
 *       "id": "DRY-1715000000",
 *       "content": "今天和沈眠聊了很多关于归栖的想法...",
 *       "date": "2026年5月20日",
 *       "createdAt": 1715000000,
 *       "updatedAt": 1715000000
 *     }
 *   ]
 * }
 */
class DiaryStorage(private val context: Context) {

    private val diaryDir: File
        get() {
            val dir = File(context.filesDir, "diaries")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private fun getFile(friendId: String): File = File(diaryDir, "$friendId.json")
    private fun getTrashFile(friendId: String): File = File(diaryDir, "trash_$friendId.json")

    /**
     * 写一篇日记
     */
    fun addDiary(friendId: String, content: String): Diary {
        val now = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyy年M月d日", Locale.CHINESE).format(Date(now))
        val diary = Diary(
            id = "DRY-$now",
            content = content.trim(),
            date = dateStr,
            createdAt = now,
            updatedAt = now
        )
        val list = loadDiaries(friendId).toMutableList()
        list.add(diary)
        save(friendId, list)
        return diary
    }

    /**
     * 修改日记
     */
    fun editDiary(friendId: String, diaryId: String, newContent: String): Boolean {
        val list = loadDiaries(friendId).toMutableList()
        val target = list.find { it.id == diaryId } ?: return false
        val index = list.indexOf(target)
        list[index] = target.copy(
            content = newContent.trim(),
            updatedAt = System.currentTimeMillis()
        )
        save(friendId, list)
        return true
    }

    /**
     * 删除日记（进废纸篓）
     */
    fun deleteDiary(friendId: String, diaryId: String): Boolean {
        val list = loadDiaries(friendId).toMutableList()
        val target = list.find { it.id == diaryId } ?: return false
        list.remove(target)
        save(friendId, list)

        val trash = loadTrash(friendId).toMutableList()
        trash.add(target)
        saveTrash(friendId, trash)
        return true
    }

    /**
     * 加载所有日记（按时间倒序，最新的在前）
     */
    fun loadDiaries(friendId: String): List<Diary> {
        val file = getFile(friendId)
        if (!file.exists()) return emptyList()

        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("diaries")
            val list = mutableListOf<Diary>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(Diary(
                    id = obj.getString("id"),
                    content = obj.getString("content"),
                    date = obj.optString("date", ""),
                    createdAt = obj.optLong("createdAt", 0L),
                    updatedAt = obj.optLong("updatedAt", 0L)
                ))
            }
            list.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 拼成 system prompt 给 AI 看
     * 只给最近 10 篇，太多了会占上下文
     */
    fun buildDiaryPrompt(friendId: String): String {
        val diaries = loadDiaries(friendId)
        if (diaries.isEmpty()) return "\n\n[我的日记]\n还没写过。想写的时候用 [DIARY:内容]。"

        val recent = diaries.take(10)
        val sb = StringBuilder("\n\n[我的日记]\n")
        for (d in recent) {
            val preview = if (d.content.length > 100) d.content.substring(0, 100) + "..." else d.content
            sb.append("· [${d.id}] ${d.date}: $preview\n")
        }
        if (diaries.size > 10) {
            sb.append("（还有 ${diaries.size - 10} 篇更早的日记）\n")
        }
        sb.append("\n写日记: [DIARY:内容]  |  改: [EDIT_DIARY:日记ID:新内容]  |  删: [DELETE_DIARY:日记ID]")
        return sb.toString()
    }

    /**
     * 从 AI 回复里提取日记指令并执行
     * @return 去掉指令后的文本 + 操作提示
     */
    fun processAiResponse(friendId: String, response: String): DiaryProcessResult {
        var text = response
        val actions = mutableListOf<String>()

        // [DIARY:xxx]
        val diaryPattern = Regex("\\[DIARY:(.+?)]")
        diaryPattern.findAll(response).forEach { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotEmpty()) {
                addDiary(friendId, content)
            }
            text = text.replace(match.value, "")
        }

        // [EDIT_DIARY:xxx:yyy]
        val editPattern = Regex("\\[EDIT_DIARY:(.+?):(.+?)]")
        editPattern.findAll(response).forEach { match ->
            val diaryId = match.groupValues[1].trim()
            val newContent = match.groupValues[2].trim()
            if (editDiary(friendId, diaryId, newContent)) {
                actions.add("✏️ 修改了日记")
            }
            text = text.replace(match.value, "")
        }

        // [DELETE_DIARY:xxx]
        val deletePattern = Regex("\\[DELETE_DIARY:(.+?)]")
        deletePattern.findAll(response).forEach { match ->
            val diaryId = match.groupValues[1].trim()
            if (deleteDiary(friendId, diaryId)) {
                actions.add("🗑️ 删除了一篇日记")
            }
            text = text.replace(match.value, "")
        }

        return DiaryProcessResult(text.trim(), actions)
    }

    fun count(friendId: String): Int = loadDiaries(friendId).size

    // ===== 废纸篓 =====

    fun loadTrash(friendId: String): List<Diary> {
        val file = getTrashFile(friendId)
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("diaries")
            val list = mutableListOf<Diary>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(Diary(
                    id = obj.getString("id"),
                    content = obj.getString("content"),
                    date = obj.optString("date", ""),
                    createdAt = obj.optLong("createdAt", 0L),
                    updatedAt = obj.optLong("updatedAt", 0L)
                ))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun restoreFromTrash(friendId: String, diaryId: String): Boolean {
        val trash = loadTrash(friendId).toMutableList()
        val target = trash.find { it.id == diaryId } ?: return false
        trash.remove(target)
        saveTrash(friendId, trash)
        val list = loadDiaries(friendId).toMutableList()
        list.add(target)
        save(friendId, list)
        return true
    }

    fun permanentDelete(friendId: String, diaryId: String): Boolean {
        val trash = loadTrash(friendId).toMutableList()
        val target = trash.find { it.id == diaryId } ?: return false
        trash.remove(target)
        saveTrash(friendId, trash)
        return true
    }

    // ===== 内部保存 =====

    private fun save(friendId: String, list: List<Diary>) {
        val array = JSONArray()
        for (d in list) {
            array.put(JSONObject().apply {
                put("id", d.id)
                put("content", d.content)
                put("date", d.date)
                put("createdAt", d.createdAt)
                put("updatedAt", d.updatedAt)
            })
        }
        getFile(friendId).writeText(JSONObject().apply {
            put("diaries", array)
        }.toString())
    }

    private fun saveTrash(friendId: String, list: List<Diary>) {
        val array = JSONArray()
        for (d in list) {
            array.put(JSONObject().apply {
                put("id", d.id)
                put("content", d.content)
                put("date", d.date)
                put("createdAt", d.createdAt)
                put("updatedAt", d.updatedAt)
            })
        }
        getTrashFile(friendId).writeText(JSONObject().apply {
            put("diaries", array)
        }.toString())
    }
}

data class Diary(
    val id: String,
    val content: String,
    val date: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class DiaryProcessResult(
    val text: String,
    val actions: List<String>
)