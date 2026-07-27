package com.haven.guiqi

/**
 * 每位住户各自拥有的提示词档案。
 *
 * 默认使用 LEGACY，因此仅加入这套数据结构不会改变任何现有住户收到的提示词。
 */
enum class ResidentPromptMode {
    LEGACY,
    LAYERED
}

/** 住户以后修改自己公约时，归栖如何处理保存权限。 */
enum class ResidentPromptEditPermission {
    ASK_EACH_TIME,
    ALLOW_RESIDENT
}

/** 绝对免打扰的作用范围。真正拦截消息会在后续接入睡眠流程。 */
enum class ResidentDndMode {
    OFF,
    THIS_SLEEP,
    ALWAYS
}

/** 睡眠期间普通消息如何处理。 */
enum class ResidentSleepMessagePolicy {
    DELIVER,
    HOLD
}

data class ResidentRuntimeSettings(
    val dndMode: ResidentDndMode = ResidentDndMode.OFF,
    val emergencyCanWake: Boolean = true,
    val sleepMessagePolicy: ResidentSleepMessagePolicy = ResidentSleepMessagePolicy.DELIVER,
    val summaryIntervalOverride: Int? = null
)

data class ResidentPromptVersion(
    val version: Int,
    val content: String,
    val author: String = "resident",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class ResidentPromptProfile(
    val schemaVersion: Int = 1,
    val friendId: String,
    val mode: ResidentPromptMode = ResidentPromptMode.LEGACY,
    val covenantDraft: String = "",
    val activeCovenant: String = "",
    val activeVersion: Int = 0,
    val editPermission: ResidentPromptEditPermission = ResidentPromptEditPermission.ASK_EACH_TIME,
    val runtimeSettings: ResidentRuntimeSettings = ResidentRuntimeSettings(),
    val versions: List<ResidentPromptVersion> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)
