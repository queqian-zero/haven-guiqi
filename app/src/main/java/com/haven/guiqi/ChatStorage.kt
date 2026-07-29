package com.haven.guiqi

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ChatStorage - 聊天记录的本地存储（安全加固版）
 *
 * ★ 这次改造解决的三个问题：
 *
 * 1.【防数据丢失】所有整文件写入都改成"先写临时文件，再原子替换"。
 *   就算写到一半 App 被杀、手机断电，原文件也完好无损。
 *   读取时逐行解析，坏了哪一行只跳过那一行，绝不会把整本记录当空的。
 *
 * 2.【防并发打架】所有读写都加了全局锁（synchronized）。
 *   聊天页面和后台服务（自然醒/独处）同时写，也会乖乖排队，不会互相覆盖。
 *
 * 3.【性能】文件格式从"一个大 JSON"改成 JSONL（一行一条消息）。
 *   追加新消息 = 在文件末尾贴一行，不再"整本读出来重抄一遍"。
 *   聊 1 万条也不会越来越卡。
 *
 * ★ 旧格式自动迁移：
 *   第一次读到旧的 .json 文件会自动转成 .jsonl，
 *   旧文件改名为 .json.old 保留（以防万一，可手动删除）。
 *
 * 文件位置：应用内部存储/chat_logs/好友ID.jsonl
 * 每行一条：{"role":"user","content":"你好呀","timestamp":1715000000}
 */
class ChatStorage(private val context: Context) {

    companion object {
        /**
         * 全局锁：整个 App 里所有 ChatStorage 实例共用这一把。
         * 谁要读写聊天文件，先拿锁，用完释放，其他人排队。
         */
        private val LOCK = Any()

        private const val META_PREFS = "chat_storage_meta"
    }

    /**
     * 只缓存“消息条数 + 对应文件版本”。
     *
     * 消息内容仍以 JSONL 文件为唯一真相；缓存不新鲜时会重新统计，
     * 因而不会影响 AI 上下文或聊天记录本身。
     */
    private val metaPrefs by lazy {
        context.applicationContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
    }

    // 聊天记录存储的文件夹
    private val chatDir: File
        get() {
            val dir = File(context.filesDir, "chat_logs")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    /** 新格式文件（一行一条消息） */
    private fun jsonlFile(friendId: String): File =
        File(chatDir, "${sanitizeId(friendId)}.jsonl")

    /** 旧格式文件（一个大 JSON），只用于迁移 */
    private fun legacyFile(friendId: String): File =
        File(chatDir, "${sanitizeId(friendId)}.json")

    // ==================== 对外接口（和原来完全一样） ====================

    /**
     * 保存整个聊天记录（覆盖写，原子操作）
     */
    fun saveMessages(friendId: String, messages: List<StoredMessage>) {
        synchronized(LOCK) {
            migrateIfNeeded(friendId)
            val sb = StringBuilder()
            for (msg in messages) {
                sb.append(msgToJson(msg).toString()).append('\n')
            }
            val file = jsonlFile(friendId)
            if (atomicWrite(file, sb.toString())) {
                cacheMessageCount(friendId, file, messages.size)
            } else {
                invalidateMessageCount(friendId)
            }
        }
    }

    /**
     * 读取聊天记录
     * 逐行解析：某一行损坏只跳过那一行，不影响其他消息
     */
    fun loadMessages(friendId: String): List<StoredMessage> {
        synchronized(LOCK) {
            migrateIfNeeded(friendId)
            val file = jsonlFile(friendId)
            if (!file.exists()) return emptyList()

            val messages = mutableListOf<StoredMessage>()
            try {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        try {
                            messages.add(jsonToMsg(JSONObject(trimmed)))
                        } catch (e: Exception) {
                            // 这一行坏了（比如断电时只写了半行），跳过它，其他消息安然无恙
                        }
                    }
                }
            } catch (e: Exception) {
                // 连文件都读不了（极罕见），返回已解析出来的部分
            }
            return messages
        }
    }

    /**
     * 追加一条消息 —— 真·追加
     * 只在文件末尾写一行，不读旧内容、不重写全文件，O(1) 开销
     */
    fun appendMessage(friendId: String, message: StoredMessage) {
        synchronized(LOCK) {
            migrateIfNeeded(friendId)
            val file = jsonlFile(friendId)
            val line = msgToJson(message).toString() + "\n"
            val cachedBeforeAppend = getCachedMessageCount(friendId, file)
            try {
                file.appendText(line)
                if (cachedBeforeAppend != null) {
                    cacheMessageCount(friendId, file, cachedBeforeAppend + 1)
                } else {
                    invalidateMessageCount(friendId)
                }
            } catch (e: Exception) {
                // 追加失败（磁盘满等极端情况），最多丢这一条，不伤害历史记录
            }
        }
    }

    /**
     * 快速获取消息条数（只数行数，不做 JSON 解析）
     * 给"聊天总结触发判断"这类只需要数量的场景用，省掉全量解析
     */
    fun getMessageCount(friendId: String): Int {
        synchronized(LOCK) {
            migrateIfNeeded(friendId)
            val file = jsonlFile(friendId)
            if (!file.exists()) {
                invalidateMessageCount(friendId)
                return 0
            }

            getCachedMessageCount(friendId, file)?.let { return it }

            var count = 0
            try {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isNotBlank()) count++
                    }
                }
                cacheMessageCount(friendId, file, count)
            } catch (e: Exception) {
                invalidateMessageCount(friendId)
            }
            return count
        }
    }

    /**
     * 只读取最后一条消息。
     *
     * 给来信列表使用：从文件末尾取一行，不再为了显示一句摘要而解析整份聊天。
     */
    fun getLastMessage(friendId: String): StoredMessage? {
        synchronized(LOCK) {
            migrateIfNeeded(friendId)
            val file = jsonlFile(friendId)
            if (!file.exists()) return null
            val line = try {
                readTailNonBlankLines(file, 1).lastOrNull()
            } catch (_: Exception) {
                null
            } ?: return null

            return try {
                jsonToMsg(JSONObject(line.trim()))
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 只从聊天文件尾部向前查“连续聊天天数”。
     *
     * 一旦遇到日期断档就停止，不需要把几百、几千条历史全部装进内存。
     * 这是前端火花统计专用接口，不改变任何 AI 读取逻辑。
     */
    fun getConsecutiveChatStreak(friendId: String, today: LocalDate = LocalDate.now()): Int {
        synchronized(LOCK) {
            migrateIfNeeded(friendId)
            val file = jsonlFile(friendId)
            if (!file.exists()) return 0

            val zone = ZoneId.systemDefault()
            var expectedDay: LocalDate? = null
            var lastSeenDay: LocalDate? = null
            var streak = 0
            var started = false

            try {
                forEachNonBlankLineReversed(file) { line ->
                    val timestamp = try {
                        JSONObject(line).optLong("timestamp", 0L)
                    } catch (_: Exception) {
                        0L
                    }
                    if (timestamp <= 0L) return@forEachNonBlankLineReversed true

                    val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
                    if (day == lastSeenDay) return@forEachNonBlankLineReversed true
                    lastSeenDay = day

                    if (!started) {
                        if (day != today && day != today.minusDays(1)) {
                            return@forEachNonBlankLineReversed false
                        }
                        expectedDay = day
                        started = true
                    }

                    val expected = expectedDay ?: return@forEachNonBlankLineReversed false
                    when {
                        day == expected -> {
                            streak++
                            expectedDay = expected.minusDays(1)
                            true
                        }
                        // 极少数旧消息可能以非严格时间顺序写入；比当前期望日更新的跳过。
                        day.isAfter(expected) -> true
                        // 已经越过期望日，说明中间有断档，可以停止。
                        else -> false
                    }
                }
            } catch (_: Exception) {
                return 0
            }
            return streak
        }
    }

    /**
     * 只读取最近 n 条消息（进入聊天、构建 AI 上下文用）。
     *
     * 旧实现是 file.readLines().takeLast(n)：虽然最后只解析 n 条，
     * 但仍会从文件开头读到结尾，并把全部行装进内存。
     * 现在改成从文件末尾按块向前找换行符，只读取真正需要的尾部内容。
     */
    fun loadRecentMessages(friendId: String, n: Int): List<StoredMessage> =
        loadRecentMessagesPage(friendId, n).messages

    /**
     * 最近消息 + 前面是否还有更早记录。
     * 多读一条只用于判断“加载更早”按钮，不需要先把整份记录数一遍。
     */
    fun loadRecentMessagesPage(friendId: String, n: Int): RecentMessagesPage {
        synchronized(LOCK) {
            migrateIfNeeded(friendId)
            val file = jsonlFile(friendId)
            if (!file.exists() || n <= 0) return RecentMessagesPage(emptyList(), false)

            val tailLines = try {
                readTailNonBlankLines(file, n + 1)
            } catch (e: Exception) {
                return RecentMessagesPage(emptyList(), false)
            }
            val hasOlder = tailLines.size > n
            val linesToParse = if (hasOlder) tailLines.takeLast(n) else tailLines

            val messages = mutableListOf<StoredMessage>()
            for (line in linesToParse) {
                try {
                    messages.add(jsonToMsg(JSONObject(line.trim())))
                } catch (e: Exception) {
                    // 单行损坏就跳过，其他记录仍然可用
                }
            }
            return RecentMessagesPage(messages, hasOlder)
        }
    }

    /**
     * 聊天文件的轻量版本标记。只读取文件元数据，不扫描消息内容。
     * 用于页面离开/回来时判断后台是否追加了新消息。
     */
    fun getFileRevision(friendId: String): ChatFileRevision {
        synchronized(LOCK) {
            migrateIfNeeded(friendId)
            val file = jsonlFile(friendId)
            return if (file.exists()) {
                ChatFileRevision(file.length(), file.lastModified())
            } else {
                ChatFileRevision.EMPTY
            }
        }
    }

    /**
     * 第一条消息的时间戳（"相遇那天"）；没有记录返回 0
     * 只解析第一行，开销极小
     */
    fun getFirstTimestamp(friendId: String): Long {
        synchronized(LOCK) {
            migrateIfNeeded(friendId)
            val file = jsonlFile(friendId)
            if (!file.exists()) return 0L
            try {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        return try { JSONObject(line.trim()).optLong("timestamp", 0L) } catch (e: Exception) { 0L }
                    }
                }
            } catch (e: Exception) { }
            return 0L
        }
    }

    /**
     * 最后一条消息的时间戳；没有记录返回 0
     */
    fun getLastTimestamp(friendId: String): Long =
        getLastMessage(friendId)?.timestamp ?: 0L

    /**
     * 删除最后一条消息（发送失败撤回用）
     * 在锁内完成"读-删-写"，不会误删并发写入的其他消息
     */
    fun removeLastMessage(friendId: String) {
        synchronized(LOCK) {
            migrateIfNeeded(friendId)
            val file = jsonlFile(friendId)
            if (!file.exists()) return
            try {
                val cachedBeforeRemove = getCachedMessageCount(friendId, file)
                val lines = file.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return
                val sb = StringBuilder()
                for (i in 0 until lines.size - 1) {
                    sb.append(lines[i]).append('\n')
                }
                if (atomicWrite(file, sb.toString())) {
                    if (cachedBeforeRemove != null) {
                        cacheMessageCount(friendId, file, (cachedBeforeRemove - 1).coerceAtLeast(0))
                    } else {
                        invalidateMessageCount(friendId)
                    }
                }
            } catch (e: Exception) {
                invalidateMessageCount(friendId)
            }
        }
    }

    /**
     * 删除某个好友的全部聊天记录
     */
    fun deleteMessages(friendId: String) {
        synchronized(LOCK) {
            val f1 = jsonlFile(friendId)
            if (f1.exists()) f1.delete()
            val f2 = legacyFile(friendId)
            if (f2.exists()) f2.delete()
            val f3 = File(chatDir, "${sanitizeId(friendId)}.json.old")
            if (f3.exists()) f3.delete()
            invalidateMessageCount(friendId)
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 从文件末尾向前读取最多 limit 条非空行。
     *
     * JSONL 的一条消息就是一行，所以无需从第一条开始扫描。
     * 这里按 8KB 分块向前找换行符，找到足够的行后就停止；
     * 即使聊天有几千条，进入页面通常也只碰文件尾部的一小段。
     */
    private fun readTailNonBlankLines(file: File, limit: Int): List<String> {
        if (limit <= 0 || !file.exists() || file.length() == 0L) return emptyList()

        RandomAccessFile(file, "r").use { raf ->
            var position = raf.length()
            var newlineCount = 0
            val blocks = mutableListOf<ByteArray>()
            val blockSize = 8 * 1024
            // 多找两个换行：一个用于 hasOlder，另一个用于处理起点落在半行中间。
            val targetNewlines = limit + 2

            while (position > 0 && newlineCount < targetNewlines) {
                val size = minOf(blockSize.toLong(), position).toInt()
                position -= size
                raf.seek(position)
                val block = ByteArray(size)
                raf.readFully(block)
                blocks.add(block)
                for (b in block) {
                    if (b.toInt() == '\n'.code) newlineCount++
                }
            }

            val startsAtLineBoundary = if (position == 0L) {
                true
            } else {
                raf.seek(position - 1)
                raf.read() == '\n'.code
            }

            val totalBytes = blocks.sumOf { it.size }
            val joined = ByteArray(totalBytes)
            var offset = 0
            for (block in blocks.asReversed()) {
                block.copyInto(joined, offset)
                offset += block.size
            }

            var text = joined.toString(Charsets.UTF_8)
            // 第一块通常从某条消息中间开始；丢掉这条残缺行。
            if (!startsAtLineBoundary) {
                val firstBreak = text.indexOf('\n')
                if (firstBreak < 0) return emptyList()
                text = text.substring(firstBreak + 1)
            }

            return text.lineSequence()
                .map { it.trimEnd('\r') }
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(limit)
        }
    }

    /**
     * 从文件末尾逐行向前遍历。consumer 返回 false 时立即停止。
     *
     * 以块为单位读取，但只在遇到换行后才组装一条完整 UTF-8 文本，
     * 因此不会因为中文多字节字符跨块而产生乱码。
     */
    private fun forEachNonBlankLineReversed(
        file: File,
        consumer: (String) -> Boolean
    ) {
        if (!file.exists() || file.length() == 0L) return

        RandomAccessFile(file, "r").use { raf ->
            var position = raf.length()
            val blockSize = 8 * 1024
            val reversedLine = ByteArrayOutputStream()

            fun emitLine(): Boolean {
                if (reversedLine.size() == 0) return true
                val bytes = reversedLine.toByteArray()
                bytes.reverse()
                reversedLine.reset()
                val line = bytes.toString(Charsets.UTF_8).trimEnd('\r')
                return line.isBlank() || consumer(line)
            }

            while (position > 0L) {
                val size = minOf(blockSize.toLong(), position).toInt()
                position -= size
                raf.seek(position)
                val block = ByteArray(size)
                raf.readFully(block)

                for (index in block.lastIndex downTo 0) {
                    if (block[index].toInt() == '\n'.code) {
                        if (!emitLine()) return
                    } else {
                        reversedLine.write(block[index].toInt())
                    }
                }
            }
            emitLine()
        }
    }

    private fun metaKey(prefix: String, friendId: String): String =
        "${prefix}_${sanitizeId(friendId)}"

    private fun getCachedMessageCount(friendId: String, file: File): Int? {
        val countKey = metaKey("count", friendId)
        if (!metaPrefs.contains(countKey)) return null
        val cachedLength = metaPrefs.getLong(metaKey("length", friendId), -1L)
        val cachedModified = metaPrefs.getLong(metaKey("modified", friendId), -1L)
        return if (cachedLength == file.length() && cachedModified == file.lastModified()) {
            metaPrefs.getInt(countKey, 0)
        } else {
            null
        }
    }

    private fun cacheMessageCount(friendId: String, file: File, count: Int) {
        metaPrefs.edit()
            .putInt(metaKey("count", friendId), count.coerceAtLeast(0))
            .putLong(metaKey("length", friendId), file.length())
            .putLong(metaKey("modified", friendId), file.lastModified())
            .apply()
    }

    private fun invalidateMessageCount(friendId: String) {
        metaPrefs.edit()
            .remove(metaKey("count", friendId))
            .remove(metaKey("length", friendId))
            .remove(metaKey("modified", friendId))
            .apply()
    }

    /**
     * 原子写入：先写到 .tmp 临时文件，写完再一步替换正式文件。
     * 替换是文件系统级的原子操作——要么完全成功，要么原文件保持原样，
     * 永远不会出现"半个文件"。
     */
    private fun atomicWrite(target: File, content: String): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            tmp.writeText(content)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
                true
            } catch (e: Exception) {
                // 极少数文件系统不支持原子移动，退回普通替换
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            if (tmp.exists()) tmp.delete()
            false
        }
    }

    /**
     * 旧格式 → 新格式 一次性迁移
     * 旧的大 JSON 文件转成一行一条的 JSONL，旧文件改名 .json.old 留作备份
     */
    private fun migrateIfNeeded(friendId: String) {
        val newFile = jsonlFile(friendId)
        val oldFile = legacyFile(friendId)
        if (newFile.exists() || !oldFile.exists()) return

        try {
            val json = JSONObject(oldFile.readText())
            val array = json.getJSONArray("messages")
            val sb = StringBuilder()
            var migratedCount = 0
            for (i in 0 until array.length()) {
                try {
                    val msg = jsonToMsg(array.getJSONObject(i))
                    sb.append(msgToJson(msg).toString()).append('\n')
                    migratedCount++
                } catch (e: Exception) {
                    // 单条损坏就跳过
                }
            }
            if (atomicWrite(newFile, sb.toString())) {
                cacheMessageCount(friendId, newFile, migratedCount)
                oldFile.renameTo(File(chatDir, "${sanitizeId(friendId)}.json.old"))
            }
        } catch (e: Exception) {
            // 旧文件整体损坏：改名保留现场（万一以后能救），从空记录开始
            // 注意：绝不覆盖或删除它
            oldFile.renameTo(File(chatDir, "${sanitizeId(friendId)}.json.corrupt"))
        }
    }

    /** StoredMessage → 一行 JSON */
    private fun msgToJson(msg: StoredMessage): JSONObject {
        return JSONObject().apply {
            put("role", msg.role)
            put("content", msg.content)
            put("timestamp", msg.timestamp)
            if (msg.thinking.isNotEmpty()) put("thinking", msg.thinking)
            if (msg.imagePath.isNotEmpty()) put("image_path", msg.imagePath)
            if (msg.type != "text") put("type", msg.type)
            if (msg.extras.isNotEmpty()) put("extras", msg.extras)
        }
    }

    /** 一行 JSON → StoredMessage（兼容旧字段规则） */
    private fun jsonToMsg(obj: JSONObject): StoredMessage {
        val imagePath = obj.optString("image_path", "")
        // 旧消息没有 type 字段：有图片路径的自动识别为 image，否则为 text
        val type = obj.optString(
            "type",
            if (imagePath.isNotEmpty()) "image" else "text"
        )
        return StoredMessage(
            role = obj.getString("role"),
            content = obj.getString("content"),
            timestamp = obj.optLong("timestamp", 0L),
            thinking = obj.optString("thinking", ""),
            imagePath = imagePath,
            type = type,
            extras = obj.optString("extras", "")
        )
    }

    /**
     * 把好友ID处理成安全的文件名（去掉特殊字符）
     */
    private fun sanitizeId(id: String): String {
        return id.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fff_-]"), "_")
    }
}

/** 最近消息读取结果。 */
data class RecentMessagesPage(
    val messages: List<StoredMessage>,
    val hasOlder: Boolean
)

/** 聊天文件的轻量版本标记。 */
data class ChatFileRevision(
    val length: Long,
    val modifiedAt: Long
) {
    companion object {
        val EMPTY = ChatFileRevision(0L, 0L)
    }
}

/**
 * 存储用的消息格式
 * 跟 ChatMessage 的区别是多了时间戳和消息类型
 *
 * type 可选值（以后加新类型只在这里加，不动别的代码）：
 *   text       纯文字（默认）
 *   image      图片（用户拍的/选的照片）
 *   sticker    表情包（从收藏夹发的）
 *   voice      语音消息
 *   music      音乐分享
 *   video      视频
 *   file       文件
 *   link       链接预览卡片
 *   code       代码块 / 运行结果
 *   share      分享卡片（记忆/日记/梦境/足迹/总结）
 *   screenshot 查岗截屏
 *   report     报备
 *   quote      引用回复
 *   system     系统提示（换天、间隔等）
 *
 * extras 是 JSON 字符串，放每种类型的附加信息，比如：
 *   share  → {"share_type":"memory","item_id":"MEM-001"}
 *   music  → {"title":"...","artist":"...","duration":180}
 *   link   → {"url":"...","title":"...","preview":"..."}
 *   quote  → {"quote_author":"...","quote_content":"..."}
 */
data class StoredMessage(
    val role: String,           // "user" / "assistant" / "system"
    val content: String,        // 消息内容
    val timestamp: Long,        // 发送时间（毫秒）
    val thinking: String = "",  // AI 的思考过程（可能为空）
    val imagePath: String = "", // 图片路径（可能为空）
    val type: String = "text",  // 消息类型（默认纯文字）
    val extras: String = ""     // 附加数据（JSON 字符串，按类型不同装不同东西）
)