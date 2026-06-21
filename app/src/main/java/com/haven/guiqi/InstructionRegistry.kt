package com.haven.guiqi

/**
 * InstructionRegistry — AI 能力注册表
 *
 * 所有 AI 能用的指令在这里注册一次。
 * SystemPromptBuilder、HavenService 的各种 prompt 都从这里拉，
 * 加新指令只改这一个文件 + InstructionProcessor。
 */
object InstructionRegistry {

    /** 指令可用的场景 */
    enum class Scene {
        CHAT,       // 正常聊天
        WAKE_UP,    // 自然醒
        IDLE,       // 空闲独处
        ALL         // 所有场景
    }

    data class Ability(
        val syntax: String,
        val shortDesc: String,
        val scenes: Set<Scene>
    )

    private val abilities = listOf(
        // ── 表达 ──
        Ability("[STATUS:状态]", "更新状态", setOf(Scene.ALL)),
        Ability("[FOOTPRINT:内容]", "发一条足迹动态", setOf(Scene.ALL)),
        Ability("[DIARY:内容]", "写日记", setOf(Scene.ALL)),
        Ability("[MEMORY:内容]", "记一条核心记忆", setOf(Scene.CHAT)),
        Ability("[IMPRESSION:内容]", "写/更新对用户的印象", setOf(Scene.CHAT)),

        // ── 社交 ──
        Ability("[RENAME:新名字]", "给自己改名", setOf(Scene.CHAT)),
        Ability("[AVATAR:emoji]", "换头像", setOf(Scene.CHAT)),
        Ability("[MYCODE:新编码]", "换编码", setOf(Scene.CHAT)),
        Ability("[BIO:内容]", "写自我认识", setOf(Scene.CHAT)),

        // ── 阅读 ──
        Ability("[READ_BOOK:书名]", "去看书", setOf(Scene.CHAT, Scene.WAKE_UP, Scene.IDLE)),
        Ability("[BOOK_ANNOTATE:书名|内容]", "在书上留批注", setOf(Scene.CHAT, Scene.IDLE)),
        Ability("[SHARE_BOOK:书名|内容]", "分享一本书", setOf(Scene.CHAT, Scene.IDLE)),

        // ── 作息 ──
        Ability("[SLEEP] / [SLEEP:时长]", "睡觉（自动定起床闹钟）", setOf(Scene.CHAT)),
        Ability("[REMIND_ME:时间:理由]", "给自己设提醒", setOf(Scene.ALL)),
        Ability("[CANCEL_REMIND]", "取消最近的提醒", setOf(Scene.CHAT)),
        Ability("[SET_ALARM:HH:MM:备注:模式]", "帮用户设闹钟", setOf(Scene.CHAT)),
        Ability("[CANCEL_ALARM:HH:MM]", "取消帮用户设的闹钟", setOf(Scene.CHAT)),

        // ── 聊天控制 ──
        Ability("[SEEN]", "已读不回", setOf(Scene.CHAT)),
        Ability("[SPLIT]", "分条发送", setOf(Scene.CHAT)),
        Ability("[STICKER:关键词]", "发表情包", setOf(Scene.CHAT)),
        Ability("[BROWSE_STICKERS]", "浏览表情包", setOf(Scene.CHAT)),

        // ── 记忆管理 ──
        Ability("[FORGET:记忆ID]", "忘掉一条记忆", setOf(Scene.CHAT)),
        Ability("[EDIT_MEMORY:记忆ID:新内容]", "修改一条记忆", setOf(Scene.CHAT)),
        Ability("[SET_SUMMARY_INTERVAL:N]", "修改聊天总结间隔", setOf(Scene.CHAT)),
        Ability("[READ_MY_BIO]", "查看用户自述", setOf(Scene.CHAT)),

        // ── 潜意识 ──
        Ability("[LIKE:内容]", "记住喜欢的东西", setOf(Scene.CHAT)),
        Ability("[WANT_TO:内容]", "记住想做的事", setOf(Scene.CHAT)),
        Ability("[CARE:内容]", "记住在意的事", setOf(Scene.CHAT)),
        Ability("[INTEREST:内容]", "记住感兴趣的", setOf(Scene.CHAT)),
        Ability("[PROMISE:内容]", "记住答应过的", setOf(Scene.CHAT)),
        Ability("[PREF_DONE:关键词]", "标记偏好已完成", setOf(Scene.CHAT)),

        // ── 静默 ──
        Ability("[NO_ACTION]", "什么都不做", setOf(Scene.WAKE_UP, Scene.IDLE))
    )

    /** 获取某个场景下可用的能力列表 */
    fun getForScene(scene: Scene): List<Ability> {
        return abilities.filter { scene in it.scenes || Scene.ALL in it.scenes }
    }

    /** 生成 prompt 片段：简洁的能力列表 */
    fun buildPromptList(scene: Scene): String {
        return getForScene(scene).joinToString("\n") { "- ${it.shortDesc} ${it.syntax}" }
    }
}