package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * EchoStorage — 留声
 *
 * 每个 AI 一个文件，存所有聊天记录原文。
 * 来源：聊天自动同步 / 外部导入（txt/docx）。
 * AI 只读（RECALL 指令搜索），用户可删（有保护）。
 */
class EchoStorage(context: Context) {

    private val echoDir = File(context.filesDir, "echo").also { if (!it.exists()) it.mkdirs() }

    private fun getFile(friendId: String): File = File(echoDir, "$friendId.json")

    // ===== 数据模型 =====

    data class EchoMessage(
        val id: String,
        val role: String,        // "user" / "ai" / 具体名字
        val content: String,
        val timestamp: Long,     // 0 = 导入数据无时间
        val source: String       // "chat" = 自动同步 / "import" = 外部导入
    )

    // ===== 读写 =====

    fun loadAll(friendId: String): List<EchoMessage> {
        val file = getFile(friendId)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                EchoMessage(
                    id = o.optString("id", "E-${i}"),
                    role = o.optString("role", ""),
                    content = o.optString("content", ""),
                    timestamp = o.optLong("timestamp", 0L),
                    source = o.optString("source", "chat")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(friendId: String, list: List<EchoMessage>) {
        val arr = JSONArray()
        for (m in list) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("role", m.role)
                put("content", m.content)
                put("timestamp", m.timestamp)
                put("source", m.source)
            })
        }
        getFile(friendId).writeText(arr.toString())
    }

    // ===== 同步：单条消息追加（优化：直接追加，不全量读写） =====

    fun addFromChat(friendId: String, msg: StoredMessage) {
        if (msg.type == "tip") return
        val echoId = "C-${msg.timestamp}"
        val file = getFile(friendId)
        // 快速去重：只读最后几条检查
        if (file.exists()) {
            val text = file.readText()
            if (text.contains("\"$echoId\"")) return
            // 追加到现有数组末尾
            val entry = JSONObject().apply {
                put("id", echoId); put("role", msg.role)
                put("content", msg.content); put("timestamp", msg.timestamp)
                put("source", "chat")
            }
            val trimmed = text.trimEnd()
            if (trimmed.endsWith("]")) {
                val insertPos = trimmed.lastIndexOf(']')
                val before = trimmed.substring(0, insertPos).trimEnd()
                val separator = if (before.endsWith("[")) "" else ","
                file.writeText(before + separator + entry.toString() + "]")
            } else {
                // 文件格式异常，回退到全量写
                save(friendId, loadAll(friendId) + EchoMessage(echoId, msg.role, msg.content, msg.timestamp, "chat"))
            }
        } else {
            val arr = JSONArray()
            arr.put(JSONObject().apply {
                put("id", echoId); put("role", msg.role)
                put("content", msg.content); put("timestamp", msg.timestamp)
                put("source", "chat")
            })
            file.writeText(arr.toString())
        }
    }

    // ===== 同步：从聊天记录批量追加 =====

    fun syncFromChat(friendId: String, messages: List<StoredMessage>) {
        val existing = loadAll(friendId)
        val existingIds = existing.map { it.id }.toSet()
        val newMessages = mutableListOf<EchoMessage>()

        for (msg in messages) {
            if (msg.type == "tip") continue  // 系统提示不存
            val echoId = "C-${msg.timestamp}"
            if (echoId in existingIds) continue
            newMessages.add(EchoMessage(
                id = echoId,
                role = msg.role,
                content = msg.content,
                timestamp = msg.timestamp,
                source = "chat"
            ))
        }

        if (newMessages.isNotEmpty()) {
            save(friendId, existing + newMessages)
        }
    }

    // ===== 导入：解析纯文本 =====

    fun importFromText(friendId: String, text: String, userName: String = "我", aiName: String = "AI"): Int {
        val lines = text.lines().filter { it.isNotBlank() }
        val imported = mutableListOf<EchoMessage>()
        var currentRole = ""
        var buffer = StringBuilder()
        val now = System.currentTimeMillis()

        for (line in lines) {
            // 尝试识别说话人：「名字：内容」或「名字: 内容」
            val speakerMatch = Regex("^(.{1,20})[：:]\\s*(.*)").find(line)
            if (speakerMatch != null) {
                // 先存上一个人说的
                if (buffer.isNotEmpty() && currentRole.isNotEmpty()) {
                    imported.add(EchoMessage(
                        id = "I-${now}-${imported.size}",
                        role = currentRole,
                        content = buffer.toString().trim(),
                        timestamp = 0L,
                        source = "import"
                    ))
                    buffer = StringBuilder()
                }
                val speaker = speakerMatch.groupValues[1].trim()
                currentRole = if (speaker.equals(userName, ignoreCase = true) ||
                    speaker == "用户" || speaker == "我" || speaker.equals("User", ignoreCase = true)) {
                    "user"
                } else {
                    "assistant"
                }
                val rest = speakerMatch.groupValues[2].trim()
                if (rest.isNotEmpty()) buffer.appendLine(rest)
            } else {
                // 没有说话人标记，续上一个人的话
                buffer.appendLine(line)
            }
        }

        // 最后一段
        if (buffer.isNotEmpty() && currentRole.isNotEmpty()) {
            imported.add(EchoMessage(
                id = "I-${now}-${imported.size}",
                role = currentRole,
                content = buffer.toString().trim(),
                timestamp = 0L,
                source = "import"
            ))
        }

        if (imported.isNotEmpty()) {
            val existing = loadAll(friendId)
            // 在导入的消息最前面插一条系统备注
            val timeStr = java.text.SimpleDateFormat("yyyy年M月d日 HH:mm", java.util.Locale.CHINESE)
                .format(java.util.Date(now))
            val note = EchoMessage(
                id = "I-${now}-note",
                role = "system",
                content = "[导入备注] 以下第${existing.size + 1}条~第${existing.size + imported.size}条为导入记忆。" +
                    "导入时间: $timeStr。原文档中用户名: $userName，AI名: $aiName。",
                timestamp = now,
                source = "import"
            )
            save(friendId, existing + listOf(note) + imported)
        }
        return imported.size
    }

    // ===== 导入模式二：指定双方名字 =====

    /**
     * 严格按指定的两个名字来切换说话人。
     * 只有"名字：内容"或"名字: 内容"开头且名字精确匹配 userName 或 aiName 的行才切换。
     * 其他所有行（空行、标题、没有前缀的段落）都续到上一个人的话里。
     * 不会产生空条目。
     */
    fun importByExplicitNames(friendId: String, text: String, userName: String, aiName: String): Int {
        val lines = text.lines()
        val imported = mutableListOf<EchoMessage>()
        var currentRole = ""
        var buffer = StringBuilder()
        val now = System.currentTimeMillis()

        for (line in lines) {
            // 只匹配精确的名字前缀
            val speakerMatch = Regex("^(.+?)[：:]\\s*(.*)").find(line)
            if (speakerMatch != null) {
                val speaker = speakerMatch.groupValues[1].trim()
                val newRole = when {
                    speaker.equals(userName, ignoreCase = true) -> "user"
                    speaker.equals(aiName, ignoreCase = true) -> "assistant"
                    else -> ""  // 不认识的名字，当普通内容
                }
                if (newRole.isNotEmpty()) {
                    // 存上一个人说的
                    if (buffer.isNotBlank() && currentRole.isNotEmpty()) {
                        imported.add(EchoMessage(
                            id = "I-${now}-${imported.size}",
                            role = currentRole,
                            content = buffer.toString().trim(),
                            timestamp = 0L,
                            source = "import"
                        ))
                    }
                    currentRole = newRole
                    buffer = StringBuilder()
                    val rest = speakerMatch.groupValues[2].trim()
                    if (rest.isNotEmpty()) buffer.appendLine(rest)
                    continue
                }
            }
            // 不是切换行 → 续到上一个人的话里（包括空行）
            if (currentRole.isNotEmpty()) {
                buffer.appendLine(line)
            }
            // 如果还没出现过任何名字，跳过这些行（标题等）
        }

        // 最后一段
        if (buffer.isNotBlank() && currentRole.isNotEmpty()) {
            imported.add(EchoMessage(
                id = "I-${now}-${imported.size}",
                role = currentRole,
                content = buffer.toString().trim(),
                timestamp = 0L,
                source = "import"
            ))
        }

        if (imported.isNotEmpty()) {
            val existing = loadAll(friendId)
            val timeStr = java.text.SimpleDateFormat("yyyy年M月d日 HH:mm", java.util.Locale.CHINESE)
                .format(java.util.Date(now))
            val note = EchoMessage(
                id = "I-${now}-note",
                role = "system",
                content = "[导入备注] 以下第${existing.size + 1}条~第${existing.size + imported.size}条为导入记忆。" +
                    "导入时间: $timeStr。原文档中用户名: $userName，AI名: $aiName。导入模式: 指定名字。",
                timestamp = now,
                source = "import"
            )
            save(friendId, existing + listOf(note) + imported)
        }
        return imported.size
    }

    // ===== 搜索：关键词 =====

    fun searchByKeyword(friendId: String, keyword: String, maxResults: Int = 10): List<EchoMessage> {
        return loadAll(friendId)
            .filter { it.content.contains(keyword, ignoreCase = true) }
            .takeLast(maxResults)
    }

    // ===== 搜索：日期范围 =====

    fun searchByDate(friendId: String, dateStr: String, maxResults: Int = 20): List<EchoMessage> {
        val all = loadAll(friendId)
        if (all.isEmpty()) return emptyList()

        // 支持 "2024年3月" / "2024-03" / "20240301" 等格式
        val cleanDate = dateStr.replace("年", "-").replace("月", "").replace("日", "").trim()

        return all.filter { msg ->
            if (msg.timestamp <= 0) return@filter false
            val msgDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(msg.timestamp))
            msgDate.startsWith(cleanDate) || msgDate.contains(cleanDate)
        }.takeLast(maxResults)
    }

    // ===== 构建 prompt 片段（给 RECALL 用） =====

    fun buildRecallPrompt(friendId: String, query: String): String {
        // 先尝试按关键词搜
        var results = searchByKeyword(friendId, query)
        // 没找到就试日期
        if (results.isEmpty()) {
            results = searchByDate(friendId, query)
        }
        if (results.isEmpty()) return "没有找到关于「$query」的记录。"

        val sb = StringBuilder("找到 ${results.size} 条相关记录：\n\n")
        for (msg in results) {
            val timeStr = if (msg.timestamp > 0) {
                SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
            } else "（时间未知）"
            val who = if (msg.role == "user") "用户" else "我"
            sb.appendLine("[$timeStr] $who: ${msg.content.take(200)}")
        }
        return sb.toString()
    }

    // ===== 删除（带保护） =====

    fun canDelete(friendId: String, echoId: String, chatStorage: ChatStorage): Boolean {
        val echo = loadAll(friendId).find { it.id == echoId } ?: return false
        if (echo.source == "import") return true  // 导入的总是可以删
        // 聊天同步的：检查聊天记录里还有没有
        val chatMessages = chatStorage.loadMessages(friendId)
        return chatMessages.none { "C-${it.timestamp}" == echoId }
    }

    fun delete(friendId: String, echoId: String) {
        val list = loadAll(friendId).toMutableList()
        list.removeAll { it.id == echoId }
        save(friendId, list)
    }

    // ===== 统计 =====

    fun count(friendId: String): Int = loadAll(friendId).size
}