package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 保存“我眼中的自己”页面下方的动物与植物记录。
 *
 * 用户的自由自述仍沿用 haven_user / my_bio；这里不改写、不归纳那段文字，
 * 只保存用户亲自填写的生活记录。
 */
class UserLifeStorage(private val context: Context) {

    enum class Kind { ANIMAL, PLANT }

    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val kind: Kind,
        val name: String = "",
        val species: String = "",
        val breed: String = "",
        val photoPath: String = "",
        val startedDate: String = "",
        val startedThought: String = "",
        val notes: String = "",
        val status: String = STATUS_CURRENT,
        val endDate: String = "",
        val endReason: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis()
    )

    data class PhotoRef(val label: String, val path: String)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAll(): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val entries = mutableListOf<Entry>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val parsed = parseEntry(obj)
                if (parsed != null) entries.add(parsed)
            }
            entries
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun load(kind: Kind): List<Entry> = loadAll()
        .filter { it.kind == kind }
        .sortedWith(
            compareBy<Entry> { if (it.status == STATUS_CURRENT) 0 else 1 }
                .thenByDescending { it.updatedAt }
        )

    fun get(id: String): Entry? = loadAll().firstOrNull { it.id == id }

    fun save(entry: Entry) {
        val entries = loadAll().toMutableList()
        val index = entries.indexOfFirst { it.id == entry.id }
        val normalized = entry.copy(updatedAt = System.currentTimeMillis())
        if (index >= 0) entries[index] = normalized else entries.add(normalized)
        persist(entries)
    }

    fun delete(id: String) {
        val entries = loadAll().toMutableList()
        val removed = entries.firstOrNull { it.id == id }
        if (!entries.removeAll { it.id == id }) return
        persist(entries)
        removed?.photoPath?.takeIf { it.isNotBlank() }?.let { path ->
            if (entries.none { it.photoPath == path }) {
                try { File(path).delete() } catch (_: Exception) {}
            }
        }
    }

    fun hasAnyReadableContent(): Boolean {
        val bio = context.getSharedPreferences("haven_user", Context.MODE_PRIVATE)
            .getString("my_bio", "")
            .orEmpty()
            .trim()
        return bio.isNotEmpty() || loadAll().isNotEmpty()
    }

    /**
     * 供 [READ_MY_BIO] 同轮工具使用。所有措辞都保留“用户写下/记录”的来源，
     * 不把主观自述包装成系统认定的客观档案。
     */
    fun buildReadContext(): String? {
        val bio = context.getSharedPreferences("haven_user", Context.MODE_PRIVATE)
            .getString("my_bio", "")
            .orEmpty()
            .trim()
        val animals = load(Kind.ANIMAL)
        val plants = load(Kind.PLANT)
        if (bio.isEmpty() && animals.isEmpty() && plants.isEmpty()) return null

        return buildString {
            append("[用户在「我眼中的自己」里写下的内容]\n")
            append("以下内容来自用户自己的填写与记录，不是系统对用户作出的客观判断。\n")

            if (bio.isNotEmpty()) {
                append("\n[用户的自我描述]\n")
                append(bio)
                append('\n')
            }

            if (animals.isNotEmpty()) {
                append("\n[用户记录的动物伙伴]\n")
                animals.forEachIndexed { index, entry ->
                    appendEntry(index + 1, entry)
                }
            }

            if (plants.isNotEmpty()) {
                append("\n[用户记录的植物伙伴]\n")
                plants.forEachIndexed { index, entry ->
                    appendEntry(index + 1, entry)
                }
            }
        }.trim()
    }

    fun readablePhotos(): List<PhotoRef> = loadAll().mapNotNull { entry ->
        val file = entry.photoPath.takeIf { it.isNotBlank() }?.let(::File)
        if (file?.isFile != true) return@mapNotNull null
        val type = if (entry.kind == Kind.ANIMAL) "动物伙伴" else "植物伙伴"
        PhotoRef("$type「${entry.name.ifBlank { "未命名" }}」的照片", file.absolutePath)
    }

    fun statusLabel(entry: Entry): String = when (entry.kind) {
        Kind.ANIMAL -> when (entry.status) {
            STATUS_DECEASED -> "已离世"
            STATUS_REHOMED -> "已归档"
            STATUS_ARCHIVED -> "已归档"
            else -> "陪伴中"
        }
        Kind.PLANT -> when (entry.status) {
            STATUS_WITHERED -> "已枯萎"
            STATUS_GIFTED -> "已归档"
            STATUS_ARCHIVED -> "已归档"
            else -> "养护中"
        }
    }

    fun statusOptions(kind: Kind): List<Pair<String, String>> = when (kind) {
        Kind.ANIMAL -> listOf(
            STATUS_CURRENT to "陪伴中",
            STATUS_DECEASED to "已离世",
            STATUS_ARCHIVED to "已归档"
        )
        Kind.PLANT -> listOf(
            STATUS_CURRENT to "养护中",
            STATUS_WITHERED to "已枯萎",
            STATUS_ARCHIVED to "已归档"
        )
    }

    fun endDateLabel(kind: Kind, status: String): String = when {
        status == STATUS_DECEASED -> "离世日期"
        status == STATUS_REHOMED -> "归档日期"
        status == STATUS_WITHERED -> "枯萎日期"
        status == STATUS_GIFTED -> "归档日期"
        status == STATUS_ARCHIVED -> "归档日期"
        else -> "状态日期"
    }

    fun endReasonLabel(kind: Kind, status: String): String = when {
        status == STATUS_DECEASED -> "离世原因与想留下的话"
        status == STATUS_REHOMED -> "归档备注"
        status == STATUS_WITHERED -> "枯萎原因与想留下的话"
        status == STATUS_GIFTED -> "归档备注"
        status == STATUS_ARCHIVED -> "归档备注（例如送给了谁、为什么归档）"
        kind == Kind.ANIMAL -> "状态备注"
        else -> "状态备注"
    }

    private fun StringBuilder.appendEntry(index: Int, entry: Entry) {
        append(index).append(". ")
        append(entry.name.ifBlank { "未命名" })
        append("｜").append(statusLabel(entry)).append('\n')
        if (entry.species.isNotBlank()) append("种类：").append(entry.species).append('\n')
        if (entry.breed.isNotBlank()) append("品种：").append(entry.breed).append('\n')
        if (entry.startedDate.isNotBlank()) {
            append(if (entry.kind == Kind.ANIMAL) "开始陪伴：" else "开始养护：")
            append(entry.startedDate).append('\n')
        }
        if (entry.startedThought.isNotBlank()) {
            append("当时的想法：").append(entry.startedThought).append('\n')
        }
        if (entry.notes.isNotBlank()) append("记录：").append(entry.notes).append('\n')
        if (entry.status != STATUS_CURRENT) {
            if (entry.endDate.isNotBlank()) {
                append(endDateLabel(entry.kind, entry.status)).append('：')
                    .append(entry.endDate).append('\n')
            }
            if (entry.endReason.isNotBlank()) {
                append(endReasonLabel(entry.kind, entry.status)).append('：')
                    .append(entry.endReason).append('\n')
            }
        }
        if (entry.photoPath.isNotBlank() && File(entry.photoPath).isFile) {
            append("照片：已保存；住户此次查看会同时收到这张照片。\n")
        }
    }

    private fun persist(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry -> array.put(entry.toJson()) }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun Entry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.name)
        put("name", name)
        put("species", species)
        put("breed", breed)
        put("photoPath", photoPath)
        put("startedDate", startedDate)
        put("startedThought", startedThought)
        put("notes", notes)
        put("status", status)
        put("endDate", endDate)
        put("endReason", endReason)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    private fun parseEntry(obj: JSONObject): Entry? {
        val kind = try {
            Kind.valueOf(obj.optString("kind", Kind.ANIMAL.name))
        } catch (_: Exception) {
            return null
        }
        return Entry(
            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = kind,
            name = obj.optString("name"),
            species = obj.optString("species"),
            breed = obj.optString("breed"),
            photoPath = obj.optString("photoPath"),
            startedDate = obj.optString("startedDate"),
            startedThought = obj.optString("startedThought"),
            notes = obj.optString("notes"),
            status = obj.optString("status", STATUS_CURRENT),
            endDate = obj.optString("endDate"),
            endReason = obj.optString("endReason"),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    companion object {
        private const val PREFS_NAME = "haven_user_life"
        private const val KEY_ENTRIES = "companion_entries_v1"

        const val STATUS_CURRENT = "current"
        const val STATUS_DECEASED = "deceased"
        const val STATUS_REHOMED = "rehomed"
        const val STATUS_WITHERED = "withered"
        const val STATUS_GIFTED = "gifted"
        const val STATUS_ARCHIVED = "archived"
    }
}
