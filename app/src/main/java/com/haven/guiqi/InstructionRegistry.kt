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
        Ability("[AVATAR:emoji]", "换 emoji 头像", setOf(Scene.CHAT)),
        Ability("[SET_AVATAR]", "把最近收到的图片设为自己的头像", setOf(Scene.CHAT)),
        Ability("[BROWSE_AVATARS] / [BROWSE_AVATARS:分类名:页码]", "按需看图浏览画匣头像", setOf(Scene.CHAT)),
        Ability("[AVATAR_PICK:编号]", "从已经看过的画匣头像里选择自己的头像", setOf(Scene.CHAT)),
        Ability("[MY_AVATAR]", "查看自己当前的头像", setOf(Scene.CHAT)),
        Ability("[BROWSE_AVATAR_FRAMES] / [BROWSE_AVATAR_FRAMES:分类名:页码]", "按需看图浏览画匣头像框", setOf(Scene.CHAT)),
        Ability("[AVATAR_FRAME_PICK:编号]", "从已经看过的画匣头像框里选择自己的头像框", setOf(Scene.CHAT)),
        Ability("[CLEAR_AVATAR_FRAME]", "摘掉自己的头像框", setOf(Scene.CHAT)),
        Ability("[SET_BACKGROUND]", "把最近收到的图片设为当前聊天背景", setOf(Scene.CHAT)),
        Ability("[BROWSE_BACKGROUNDS] / [BROWSE_BACKGROUNDS:分类名:页码]", "按需看图浏览画匣背景", setOf(Scene.CHAT)),
        Ability("[BACKGROUND_PICK:编号]", "从已经看过的画匣背景里选择当前聊天背景", setOf(Scene.CHAT)),
        Ability("[CLEAR_BACKGROUND]", "恢复当前聊天的默认背景", setOf(Scene.CHAT)),
        Ability("[MYCODE:新编码]", "换编码", setOf(Scene.CHAT)),
        Ability("[MY_BUBBLE_STYLE]", "查看自己的代码气泡档案与完整语法", setOf(Scene.CHAT)),
        Ability(
            "[BUBBLE_STYLE_DRAFT]代码[/BUBBLE_STYLE_DRAFT]",
            "提交一份自己的代码气泡候选草稿（只校验保存，不会直接应用）",
            setOf(Scene.CHAT)
        ),
        Ability("[BIO:内容]", "写自我认识", setOf(Scene.CHAT)),

        // ── 住户公约 ──
        Ability("[MY_COVENANT]", "查看自己的居住公约档案", setOf(Scene.CHAT)),
        Ability("[COVENANT_DRAFT]内容[/COVENANT_DRAFT]", "写一份自己的居住公约草稿", setOf(Scene.CHAT)),
        Ability("[COVENANT_ADOPT]", "采用当前候选公约", setOf(Scene.CHAT)),
        Ability("[COVENANT_HISTORY]", "查看自己的公约历史版本", setOf(Scene.CHAT)),
        Ability("[COVENANT_RESTORE:版本号]", "恢复某个历史版本", setOf(Scene.CHAT)),
        Ability("[COVENANT_LEGACY]", "暂停个人公约并回到旧版提示词", setOf(Scene.CHAT)),

        // ── 阅读 ──
        Ability("[READ_BOOK:书名]", "去看书", setOf(Scene.CHAT, Scene.WAKE_UP, Scene.IDLE)),
        Ability("[BOOK_ANNOTATE:书名|内容]", "在书上留批注", setOf(Scene.CHAT, Scene.IDLE)),
        Ability("[SHARE_BOOK:书名|内容]", "分享一本书", setOf(Scene.CHAT, Scene.IDLE)),
        Ability("[RECALL:关键词或日期]", "翻留声（搜聊天记录）", setOf(Scene.CHAT, Scene.WAKE_UP, Scene.IDLE)),

        // ── 作息 ──
        Ability("[SLEEP] / [SLEEP:时长]", "睡觉（自动定起床闹钟；普通消息仍可能叫醒）", setOf(Scene.CHAT)),
        Ability("[DO_NOT_DISTURB]", "为这次睡眠开启免打扰，普通消息留在床边", setOf(Scene.CHAT)),
        Ability("[ALLOW_WAKE]", "关闭这次睡眠的免打扰，允许普通消息尝试唤醒", setOf(Scene.CHAT)),
        Ability("[REMIND_ME:时间:理由]", "给自己设提醒", setOf(Scene.ALL)),
        Ability("[CANCEL_REMIND]", "取消最近的提醒", setOf(Scene.CHAT)),
        Ability("[SET_ALARM:HH:MM:备注:模式]", "帮用户设闹钟", setOf(Scene.CHAT)),
        Ability("[CANCEL_ALARM:HH:MM]", "取消帮用户设的闹钟", setOf(Scene.CHAT)),

        // ── 聊天控制 ──
        Ability("[SEEN]", "已读不回", setOf(Scene.CHAT)),
        Ability("[SPLIT]", "分条发送", setOf(Scene.CHAT)),
        Ability("[STICKER:关键词]", "发表情包", setOf(Scene.CHAT)),
        Ability("[BROWSE_STICKERS:分组名] / [BROWSE_STICKERS:分组名:页码]", "按需看图浏览表情包", setOf(Scene.CHAT)),

        // ── 记忆管理 ──
        Ability("[FORGET:记忆ID]", "忘掉一条记忆", setOf(Scene.CHAT)),
        Ability("[EDIT_MEMORY:记忆ID:新内容]", "修改一条记忆", setOf(Scene.CHAT)),
        Ability("[SET_SUMMARY_INTERVAL:N]", "修改聊天总结间隔", setOf(Scene.CHAT)),
        Ability("[READ_MY_BIO]", "调用工具查看用户在「我眼中的自己」里写下的内容；结果会在同一轮返回", setOf(Scene.CHAT)),

        // ── 潜意识 ──
        Ability("[LIKE:内容]", "记住喜欢的东西", setOf(Scene.CHAT)),
        Ability("[WANT_TO:内容]", "记住想做的事", setOf(Scene.CHAT)),
        Ability("[CARE:内容]", "记住在意的事", setOf(Scene.CHAT)),
        Ability("[INTEREST:内容]", "记住感兴趣的", setOf(Scene.CHAT)),
        Ability("[PROMISE:内容]", "记住答应过的", setOf(Scene.CHAT)),
        Ability("[PREF_DONE:关键词]", "标记偏好已完成", setOf(Scene.CHAT)),
        Ability("[PREF_DELETE:关键词]", "删除一个念头（进废纸篓）", setOf(Scene.CHAT)),

        // ── 天气 ──
        Ability("[WEATHER]", "查看用户所在城市天气", setOf(Scene.CHAT, Scene.WAKE_UP)),
        Ability("[SHARE_WEATHER]", "把天气以卡片分享给用户", setOf(Scene.CHAT)),
        Ability("[REFRESH_WEATHER]", "静默刷新天气数据", setOf(Scene.CHAT, Scene.IDLE)),

        // ── 留言板 ──
        Ability("[BULLETIN:内容]", "在桌面留言板写一条留言", setOf(Scene.ALL)),
        Ability("[CAPSULE:日期:内容]", "埋一个时间胶囊（到期才能拆封）", setOf(Scene.CHAT)),
        Ability("[BADGE:名字:解锁条件]", "创建一枚带解锁条件的亲密徽章", setOf(Scene.CHAT)),
        Ability("[BADGE_UNLOCK:名字]", "申请解锁一枚徽章（等人类确认）", setOf(Scene.CHAT)),
        Ability("[BADGE_RENAME:旧名:新名]", "给徽章改名", setOf(Scene.CHAT)),
        Ability("[MY_BADGES]", "查看徽章墙上有什么", setOf(Scene.CHAT)),

        // ── 日志墙 ──
        Ability("[READ_WALL]", "翻看施工日志墙（只读，看看谁盖了这栋房子）", setOf(Scene.CHAT, Scene.WAKE_UP)),

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