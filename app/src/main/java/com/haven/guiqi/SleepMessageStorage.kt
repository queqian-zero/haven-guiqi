package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 睡眠期间的“床边留言”索引。
 *
 * 正文仍以 ChatStorage 为准；这里额外保存一份轻量索引，用来：
 * 1. 统计睡着期间留下了多少条消息；
 * 2. 自然醒/紧急唤醒时明确告诉模型哪些消息是在睡眠期间收到的；
 * 3. API 失败时保留待处理状态，避免消息被误标成“已经看过”。
 */
class SleepMessageStorage(private val context: Context) {

    data class PendingMessage(
        val timestamp: Long,
        val content: String
    )

    data class Inbox(
        val sleepAt: Long,
        val messages: List<PendingMessage>
    )

    private val directory: File
        get() = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    fun beginSession(friendId: String, sleepAt: Long) = synchronized(FILE_LOCK) {
        save(friendId, Inbox(sleepAt = sleepAt, messages = emptyList()))
    }

    fun add(friendId: String, sleepAt: Long, content: String, timestamp: Long): Int =
        synchronized(FILE_LOCK) {
            val current = load(friendId)
            val base = if (current.sleepAt == sleepAt) current else Inbox(sleepAt, emptyList())
            val cleaned = content.trim().ifEmpty { "[空白消息]" }
            val updated = base.copy(
                messages = base.messages + PendingMessage(timestamp, cleaned)
            )
            save(friendId, updated)
            updated.messages.size
        }

    fun getInbox(friendId: String): Inbox = synchronized(FILE_LOCK) { load(friendId) }

    fun getPending(friendId: String): List<PendingMessage> =
        synchronized(FILE_LOCK) { load(friendId).messages }

    fun hasPending(friendId: String): Boolean =
        synchronized(FILE_LOCK) { load(friendId).messages.isNotEmpty() }

    fun clear(friendId: String) = synchronized(FILE_LOCK) {
        val file = inboxFile(friendId)
        if (file.exists()) file.delete()
        val temp = File(file.parentFile, "${file.name}.tmp")
        if (temp.exists()) temp.delete()
        val backup = File(file.parentFile, "${file.name}.bak")
        if (backup.exists()) backup.delete()
    }

    /**
     * 给模型看的简短回顾。最多保留最近 20 条、总长约 6000 字，
     * 防止一次睡眠留下太多消息时挤爆上下文。
     */
    fun buildWakeRecap(friendId: String): String {
        val pending = getPending(friendId)
        if (pending.isEmpty()) return ""

        val selected = pending.takeLast(MAX_RECAP_MESSAGES)
        val lines = mutableListOf<String>()
        var used = 0
        for ((index, item) in selected.withIndex()) {
            val clipped = item.content.replace(Regex("\\s+"), " ").take(MAX_SINGLE_MESSAGE_CHARS)
            val line = "${index + 1}. $clipped"
            if (used + line.length > MAX_RECAP_CHARS) break
            lines.add(line)
            used += line.length
        }

        val omitted = pending.size - lines.size
        val omittedText = if (omitted > 0) "\n（更早还有 $omitted 条，原文仍在最近聊天记录里）" else ""
        return """你睡着期间，用户留下了 ${pending.size} 条消息。它们没有唤醒你，也没有提前调用模型。请先完整看完这些内容，再决定怎样回应：
${lines.joinToString("\n")}$omittedText"""
    }

    private fun load(friendId: String): Inbox {
        val file = inboxFile(friendId)
        if (!file.exists()) return Inbox(0L, emptyList())
        return try {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("messages") ?: JSONArray()
            val messages = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        PendingMessage(
                            timestamp = item.optLong("timestamp", 0L),
                            content = item.optString("content", "")
                        )
                    )
                }
            }
            Inbox(root.optLong("sleep_at", 0L), messages)
        } catch (_: Exception) {
            Inbox(0L, emptyList())
        }
    }

    private fun save(friendId: String, inbox: Inbox) {
        val target = inboxFile(friendId)
        val temp = File(target.parentFile, "${target.name}.tmp")
        val backup = File(target.parentFile, "${target.name}.bak")
        val root = JSONObject().apply {
            put("sleep_at", inbox.sleepAt)
            put("messages", JSONArray().apply {
                inbox.messages.forEach { item ->
                    put(JSONObject().apply {
                        put("timestamp", item.timestamp)
                        put("content", item.content)
                    })
                }
            })
        }
        temp.writeText(root.toString())

        if (backup.exists()) backup.delete()
        if (target.exists() && !target.renameTo(backup)) {
            temp.delete()
            throw IllegalStateException("无法准备床边留言备份")
        }
        if (!temp.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            temp.delete()
            throw IllegalStateException("无法保存床边留言")
        }
        if (backup.exists()) backup.delete()
    }

    private fun inboxFile(friendId: String): File {
        val safeId = friendId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(directory, "$safeId.json")
    }

    companion object {
        private val FILE_LOCK = Any()
        private const val DIRECTORY_NAME = "sleep_message_inbox"
        private const val MAX_RECAP_MESSAGES = 20
        private const val MAX_RECAP_CHARS = 6000
        private const val MAX_SINGLE_MESSAGE_CHARS = 600
    }
}
