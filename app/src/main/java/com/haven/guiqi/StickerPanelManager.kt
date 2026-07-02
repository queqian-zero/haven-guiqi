package com.haven.guiqi

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable

/**
 * StickerPanelManager — 表情包面板的 UI 和管理逻辑
 *
 * 从 ChatConversationActivity 拆出来。
 * 负责表情包网格渲染、分组标签、管理模式（全选/标签/移动/删除）、
 * 分组选项（重命名/删除分组）、单张选项（设标签/移动/删除）。
 *
 * Activity 只管调度：toggle() 和回调 onSendSticker。
 */
class StickerPanelManager(
    private val activity: Activity,
    private val stickerPanel: LinearLayout,
    private val stickerGrid: LinearLayout,
    private val stickerGroupTabs: LinearLayout,
    private val stickerActionBar: LinearLayout,
    private val stickerStorage: StickerStorage
) {
    /** 用户点击表情包发送 */
    var onSendSticker: ((java.io.File) -> Unit)? = null

    private val c get() = ThemeHelper.getColors(activity)
    private var currentGroup: String? = null
    private var isManageMode = false
    private val selectedIds = mutableSetOf<String>()

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    fun toggle() {
        if (stickerPanel.visibility == View.VISIBLE) {
            stickerPanel.visibility = View.GONE
        } else {
            stickerPanel.visibility = View.VISIBLE
            refreshGrid()
        }
    }

    fun hide() { stickerPanel.visibility = View.GONE }

    fun toggleManageMode() {
        isManageMode = !isManageMode
        if (!isManageMode) selectedIds.clear()
        refreshGrid()
    }

    fun refreshGrid() {
        val columns = 4
        // 1. 分组标签栏
        stickerGroupTabs.removeAllViews()
        val groups = stickerStorage.loadGroups()
        if (groups.isNotEmpty()) {
            val allCount = stickerStorage.count()
            val allTab = makeGroupTab("全部($allCount)", currentGroup == null)
            allTab.setOnClickListener { currentGroup = null; refreshGrid() }
            stickerGroupTabs.addView(allTab)
            for ((name, count) in groups) {
                val tab = makeGroupTab("$name($count)", currentGroup == name)
                tab.setOnClickListener { currentGroup = name; refreshGrid() }
                tab.setOnLongClickListener { showGroupOptionsDialog(name); true }
                stickerGroupTabs.addView(tab)
            }
        }
        // 2. 管理按钮状态
        activity.findViewById<TextView>(R.id.btnManageSticker).apply {
            text = if (isManageMode) "完成" else "管理"
            setTextColor(if (isManageMode) c.accent else c.textSecondary)
        }
        // 3. 表情包网格
        stickerGrid.removeAllViews()
        val stickers = if (currentGroup != null) stickerStorage.loadByGroup(currentGroup!!)
                       else stickerStorage.loadStickers()
        if (stickers.isEmpty()) {
            stickerGrid.addView(TextView(activity).apply {
                text = if (currentGroup != null) "「${currentGroup}」里还没有表情包"
                       else "还没有表情包哦，点右上角「＋ 导入」添加"
                textSize = 12f; setTextColor(c.tipText)
                setPadding(dp(8), dp(30), dp(8), dp(30))
                gravity = Gravity.CENTER
            })
            stickerActionBar.visibility = View.GONE
            return
        }
        val thumbSize = dp(72)
        var currentRow: LinearLayout? = null
        for ((index, sticker) in stickers.withIndex()) {
            if (index % columns == 0) {
                currentRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                }
                stickerGrid.addView(currentRow)
            }
            val file = stickerStorage.getFile(sticker) ?: continue
            val isSelected = sticker.id in selectedIds
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val imgContainer = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            val imageView = ImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(thumbSize, thumbSize)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    setColor(c.accentBg); cornerRadius = dp(8).toFloat()
                    if (isSelected) setStroke(dp(3), c.accent) else setStroke(1, c.timeText)
                }
                setPadding(dp(3), dp(3), dp(3), dp(3))
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) setImageBitmap(bitmap)
            }
            imgContainer.addView(imageView)
            if (isManageMode && isSelected) {
                imgContainer.addView(TextView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = dp(2); marginEnd = dp(2)
                    }
                    text = "✓"; textSize = 11f; gravity = Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                    background = GradientDrawable().apply { setColor(c.accent); cornerRadius = dp(10).toFloat() }
                })
            }
            cell.addView(imgContainer)
            if (sticker.label.isNotEmpty()) {
                cell.addView(TextView(activity).apply {
                    text = sticker.label; textSize = 9f; setTextColor(c.textSecondary)
                    gravity = Gravity.CENTER; maxLines = 1
                    setPadding(0, dp(2), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(thumbSize, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
            if (isManageMode) {
                cell.setOnClickListener {
                    if (sticker.id in selectedIds) selectedIds.remove(sticker.id) else selectedIds.add(sticker.id)
                    refreshGrid()
                }
            } else {
                cell.setOnClickListener { onSendSticker?.invoke(file) }
                cell.setOnLongClickListener { showOptionsDialog(sticker); true }
            }
            currentRow?.addView(cell)
        }
        // 补齐最后一行
        val remainder = stickers.size % columns
        if (remainder != 0 && currentRow != null) {
            for (i in 0 until (columns - remainder)) {
                currentRow.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
        }
        // 4. 管理模式底部操作栏
        buildActionBar(stickers)
    }

    private fun buildActionBar(stickers: List<Sticker>) {
        stickerActionBar.removeAllViews()
        if (!isManageMode) { stickerActionBar.visibility = View.GONE; return }
        stickerActionBar.visibility = View.VISIBLE
        val allSelected = selectedIds.size == stickers.size && stickers.isNotEmpty()
        stickerActionBar.addView(TextView(activity).apply {
            text = if (allSelected) "取消全选" else "全选"
            textSize = 12f; setTextColor(c.accent)
            setPadding(dp(8), dp(4), dp(12), dp(4))
            setOnClickListener {
                if (allSelected) selectedIds.clear() else selectedIds.addAll(stickers.map { it.id })
                refreshGrid()
            }
        })
        stickerActionBar.addView(TextView(activity).apply {
            text = if (selectedIds.isEmpty()) "点击选择" else "已选 ${selectedIds.size} 张"
            textSize = 12f; setTextColor(c.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (selectedIds.isNotEmpty()) {
            stickerActionBar.addView(makeActionBtn("标签", c.accent) {
                val input = EditText(activity).apply { hint = "给选中的表情包统一设标签"; setPadding(48, 32, 48, 32) }
                AlertDialog.Builder(activity).setTitle("批量设置标签").setView(input)
                    .setPositiveButton("保存") { _, _ ->
                        for (id in selectedIds) stickerStorage.setLabel(id, input.text.toString().trim())
                        refreshGrid()
                    }.setNegativeButton("取消", null).show()
            })
            stickerActionBar.addView(makeActionBtn("移动", c.accent) {
                showMoveToGroupDialog(selectedIds.toList())
            })
            stickerActionBar.addView(makeActionBtn("删除", c.errorText) {
                AlertDialog.Builder(activity).setTitle("删除 ${selectedIds.size} 张表情包？")
                    .setPositiveButton("删除") { _, _ ->
                        stickerStorage.batchDelete(selectedIds.toList())
                        selectedIds.clear(); refreshGrid()
                    }.setNegativeButton("取消", null).show()
            })
        }
    }

    private fun makeActionBtn(label: String, color: Int, action: () -> Unit): TextView {
        return TextView(activity).apply {
            text = label; textSize = 12f; setTextColor(color)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { action() }
        }
    }

    private fun makeGroupTab(text: String, selected: Boolean): TextView {
        return TextView(activity).apply {
            this.text = text; textSize = 11f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                if (selected) setColor(c.accent) else setColor(c.accentBg)
            }
            setTextColor(if (selected) c.textOnAccent else c.textSecondary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
    }

    private fun showOptionsDialog(sticker: Sticker) {
        val fresh = stickerStorage.findById(sticker.id) ?: sticker
        AlertDialog.Builder(activity)
            .setItems(arrayOf(
                "设置标签（当前：${fresh.label.ifEmpty { "无" }}）",
                "移动到分组（当前：${fresh.group}）",
                "删除"
            )) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(activity).apply {
                            setText(fresh.label); hint = "简短描述，比如「思考」「嗯？」"; setPadding(48, 32, 48, 32)
                        }
                        AlertDialog.Builder(activity).setTitle("设置标签").setView(input)
                            .setPositiveButton("保存") { _, _ ->
                                stickerStorage.setLabel(fresh.id, input.text.toString().trim()); refreshGrid()
                            }.setNegativeButton("取消", null).show()
                    }
                    1 -> showMoveToGroupDialog(listOf(fresh.id))
                    2 -> { stickerStorage.deleteSticker(fresh.id); refreshGrid()
                           Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show() }
                }
            }.show()
    }

    private fun showMoveToGroupDialog(stickerIds: List<String>) {
        val groups = stickerStorage.loadGroups().map { it.first }.toMutableList()
        groups.add("＋ 新建分组")
        AlertDialog.Builder(activity).setTitle("移动到")
            .setItems(groups.toTypedArray()) { _, which ->
                if (which == groups.size - 1) {
                    val input = EditText(activity).apply { hint = "分组名"; setPadding(48, 32, 48, 32) }
                    AlertDialog.Builder(activity).setTitle("新建分组").setView(input)
                        .setPositiveButton("确定") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isNotEmpty()) {
                                stickerStorage.batchSetGroup(stickerIds, name)
                                selectedIds.clear(); refreshGrid()
                                Toast.makeText(activity, "已移动到「$name」", Toast.LENGTH_SHORT).show()
                            }
                        }.setNegativeButton("取消", null).show()
                } else {
                    stickerStorage.batchSetGroup(stickerIds, groups[which])
                    selectedIds.clear(); refreshGrid()
                    Toast.makeText(activity, "已移动到「${groups[which]}」", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun showGroupOptionsDialog(groupName: String) {
        AlertDialog.Builder(activity).setTitle("分组：$groupName")
            .setItems(arrayOf("重命名", "删除分组（表情包移到未分类）")) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(activity).apply { setText(groupName); setPadding(48, 32, 48, 32) }
                        AlertDialog.Builder(activity).setTitle("重命名分组").setView(input)
                            .setPositiveButton("确定") { _, _ ->
                                val n = input.text.toString().trim()
                                if (n.isNotEmpty() && n != groupName) {
                                    stickerStorage.renameGroup(groupName, n)
                                    if (currentGroup == groupName) currentGroup = n
                                    refreshGrid()
                                }
                            }.setNegativeButton("取消", null).show()
                    }
                    1 -> {
                        stickerStorage.deleteGroup(groupName)
                        if (currentGroup == groupName) currentGroup = null
                        refreshGrid()
                        Toast.makeText(activity, "已删除分组", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }
}