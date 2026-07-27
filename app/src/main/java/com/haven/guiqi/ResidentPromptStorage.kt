package com.haven.guiqi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 每位住户独立的“公约与运行设置保险箱”。
 *
 * 文件位置：filesDir/resident_prompt_profiles/{friendId}.json
 * 不存在档案时返回 LEGACY 默认值，不会改变当前提示词。
 */
class ResidentPromptStorage(private val context: Context) {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    fun getProfile(friendId: String): ResidentPromptProfile {
        val file = profileFile(friendId)
        if (!file.exists()) return defaultProfile(friendId)

        return try {
            fromJson(JSONObject(file.readText()), friendId)
        } catch (_: Exception) {
            defaultProfile(friendId)
        }
    }

    fun saveProfile(profile: ResidentPromptProfile) {
        require(profile.friendId.isNotBlank()) { "friendId 不能为空" }
        val target = profileFile(profile.friendId)
        val temp = File(target.parentFile, "${target.name}.tmp")
        val backup = File(target.parentFile, "${target.name}.bak")
        temp.writeText(toJson(profile.copy(updatedAt = System.currentTimeMillis())).toString(2))

        if (backup.exists()) backup.delete()
        if (target.exists() && !target.renameTo(backup)) {
            temp.delete()
            throw IllegalStateException("无法准备住户提示词档案备份")
        }
        if (!temp.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            temp.delete()
            throw IllegalStateException("无法保存住户提示词档案")
        }
        if (backup.exists()) backup.delete()
    }

    fun setMode(friendId: String, mode: ResidentPromptMode): ResidentPromptProfile {
        val updated = getProfile(friendId).copy(mode = mode)
        saveProfile(updated)
        return updated
    }

    fun saveCovenantDraft(friendId: String, content: String): ResidentPromptProfile {
        val updated = getProfile(friendId).copy(covenantDraft = content)
        saveProfile(updated)
        return updated
    }

    /**
     * 采用当前草稿。旧版本不会被覆盖，会留在 versions 中供以后恢复。
     */
    fun adoptCovenantDraft(
        friendId: String,
        author: String = "resident",
        note: String = ""
    ): ResidentPromptProfile {
        val current = getProfile(friendId)
        val content = current.covenantDraft.trim()
        require(content.isNotEmpty()) { "住户公约草稿不能为空" }

        // 同一份公约如果只是从旧版模式重新启用，不重复制造版本。
        if (current.activeVersion > 0 && current.activeCovenant.trim() == content) {
            val updated = current.copy(
                mode = ResidentPromptMode.LAYERED,
                covenantDraft = content
            )
            saveProfile(updated)
            return updated
        }

        val nextVersion = (current.versions.maxOfOrNull { it.version } ?: 0) + 1
        val version = ResidentPromptVersion(
            version = nextVersion,
            content = content,
            author = author,
            note = note
        )
        val updated = current.copy(
            mode = ResidentPromptMode.LAYERED,
            activeCovenant = content,
            activeVersion = nextVersion,
            versions = current.versions + version
        )
        saveProfile(updated)
        return updated
    }

    fun restoreVersion(friendId: String, versionNumber: Int): ResidentPromptProfile {
        val current = getProfile(friendId)
        val target = current.versions.firstOrNull { it.version == versionNumber }
            ?: throw IllegalArgumentException("找不到公约版本 $versionNumber")
        val updated = current.copy(
            mode = ResidentPromptMode.LAYERED,
            covenantDraft = target.content,
            activeCovenant = target.content,
            activeVersion = target.version
        )
        saveProfile(updated)
        return updated
    }

    /** 暂停个人公约，回到旧版提示词；草稿和历史均保留。 */
    fun returnToLegacy(friendId: String): ResidentPromptProfile {
        val updated = getProfile(friendId).copy(mode = ResidentPromptMode.LEGACY)
        saveProfile(updated)
        return updated
    }

    fun setEditPermission(
        friendId: String,
        permission: ResidentPromptEditPermission
    ): ResidentPromptProfile {
        val updated = getProfile(friendId).copy(editPermission = permission)
        saveProfile(updated)
        return updated
    }

    fun updateRuntimeSettings(
        friendId: String,
        settings: ResidentRuntimeSettings
    ): ResidentPromptProfile {
        val updated = getProfile(friendId).copy(runtimeSettings = settings)
        saveProfile(updated)
        return updated
    }

    fun delete(friendId: String) {
        val file = profileFile(friendId)
        if (file.exists()) file.delete()
    }

    /** 供归栖备份功能直接嵌入每位住户的数据。 */
    fun exportProfileJson(friendId: String): JSONObject? {
        val file = profileFile(friendId)
        if (!file.exists()) return null
        return try {
            toJson(getProfile(friendId))
        } catch (_: Exception) {
            null
        }
    }

    /** 兼容将来的备份恢复；以外层好友 id 为准，避免档案串户。 */
    fun importProfileJson(friendId: String, json: JSONObject) {
        saveProfile(fromJson(json, friendId).copy(friendId = friendId))
    }

    private fun defaultProfile(friendId: String) = ResidentPromptProfile(friendId = friendId)

    private fun profileFile(friendId: String): File {
        val safeId = friendId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(directory, "$safeId.json")
    }

    private fun toJson(profile: ResidentPromptProfile): JSONObject = JSONObject().apply {
        put("schema_version", profile.schemaVersion)
        put("friend_id", profile.friendId)
        put("mode", profile.mode.name)
        put("covenant_draft", profile.covenantDraft)
        put("active_covenant", profile.activeCovenant)
        put("active_version", profile.activeVersion)
        put("edit_permission", profile.editPermission.name)
        put("updated_at", profile.updatedAt)

        put("runtime_settings", JSONObject().apply {
            put("dnd_mode", profile.runtimeSettings.dndMode.name)
            put("emergency_can_wake", profile.runtimeSettings.emergencyCanWake)
            put("sleep_message_policy", profile.runtimeSettings.sleepMessagePolicy.name)
            profile.runtimeSettings.summaryIntervalOverride?.let {
                put("summary_interval_override", it)
            }
        })

        put("versions", JSONArray().apply {
            profile.versions.forEach { item ->
                put(JSONObject().apply {
                    put("version", item.version)
                    put("content", item.content)
                    put("author", item.author)
                    put("note", item.note)
                    put("created_at", item.createdAt)
                })
            }
        })
    }

    private fun fromJson(json: JSONObject, fallbackFriendId: String): ResidentPromptProfile {
        val runtimeJson = json.optJSONObject("runtime_settings") ?: JSONObject()
        val versionsJson = json.optJSONArray("versions") ?: JSONArray()
        val versions = buildList {
            for (i in 0 until versionsJson.length()) {
                val item = versionsJson.optJSONObject(i) ?: continue
                add(
                    ResidentPromptVersion(
                        version = item.optInt("version", i + 1),
                        content = item.optString("content", ""),
                        author = item.optString("author", "resident"),
                        note = item.optString("note", ""),
                        createdAt = item.optLong("created_at", System.currentTimeMillis())
                    )
                )
            }
        }

        val summaryOverride = if (runtimeJson.has("summary_interval_override") &&
            !runtimeJson.isNull("summary_interval_override")) {
            runtimeJson.optInt("summary_interval_override").takeIf { it > 0 }
        } else null

        return ResidentPromptProfile(
            schemaVersion = json.optInt("schema_version", 1),
            friendId = json.optString("friend_id", fallbackFriendId).ifBlank { fallbackFriendId },
            mode = enumOrDefault(json.optString("mode"), ResidentPromptMode.LEGACY),
            covenantDraft = json.optString("covenant_draft", ""),
            activeCovenant = json.optString("active_covenant", ""),
            activeVersion = json.optInt("active_version", 0),
            editPermission = enumOrDefault(
                json.optString("edit_permission"),
                ResidentPromptEditPermission.ASK_EACH_TIME
            ),
            runtimeSettings = ResidentRuntimeSettings(
                dndMode = enumOrDefault(
                    runtimeJson.optString("dnd_mode"),
                    ResidentDndMode.OFF
                ),
                emergencyCanWake = runtimeJson.optBoolean("emergency_can_wake", true),
                sleepMessagePolicy = enumOrDefault(
                    runtimeJson.optString("sleep_message_policy"),
                    ResidentSleepMessagePolicy.DELIVER
                ),
                summaryIntervalOverride = summaryOverride
            ),
            versions = versions,
            updatedAt = json.optLong("updated_at", System.currentTimeMillis())
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    companion object {
        private const val DIRECTORY_NAME = "resident_prompt_profiles"
    }
}
