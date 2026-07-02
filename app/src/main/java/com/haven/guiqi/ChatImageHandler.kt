package com.haven.guiqi

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * ChatImageHandler — 聊天图片选择和预览
 *
 * 从 ChatConversationActivity 拆出来。
 * 负责：选图压缩、待发列表、输入栏上方缩略图预览、清除。
 */
class ChatImageHandler(
    private val activity: AppCompatActivity,
    private val previewContainer: LinearLayout
) {
    /** 待发送的图片路径列表 */
    val pendingPaths = mutableListOf<String>()

    /** 点击 ＋ 追加图片时的回调（由 Activity 设置，触发图片选择器） */
    var onPickMore: (() -> Unit)? = null

    private val c get() = ThemeHelper.getColors(activity)
    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private val imageDir: File
        get() {
            val dir = File(activity.filesDir, "chat_images")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun hasPending(): Boolean = pendingPaths.isNotEmpty()

    /** 选图后：压缩保存，加入待发列表 */
    fun handlePickedImage(uri: Uri) {
        val path = ImageHelper.compressAndSave(activity, uri, imageDir, pendingPaths.size)
        if (path != null) {
            pendingPaths.add(path)
            showPreview()
        } else {
            Toast.makeText(activity, "图片处理失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 处理 onActivityResult（多选和单选） */
    fun handleActivityResult(data: Intent?) {
        if (data == null) return
        val clipData = data.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                handlePickedImage(clipData.getItemAt(i).uri)
            }
        } else if (data.data != null) {
            handlePickedImage(data.data!!)
        }
    }

    /** 清空待发图片 */
    fun clear() {
        pendingPaths.clear()
        removePreview()
    }

    /** 输入栏上方显示图片预览（支持多图） */
    fun showPreview() {
        removePreview()
        if (pendingPaths.isEmpty()) return

        val previewLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.inputBg)
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }

        // 缩略图行：横向滚动
        val scrollView = android.widget.HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
        }
        val thumbRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        for ((index, path) in pendingPaths.withIndex()) {
            val thumbContainer = android.widget.FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                    marginEnd = dp(6)
                }
            }

            val imageView = ImageView(activity).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(56), dp(56))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(path))
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
            }
            thumbContainer.addView(imageView)

            // 每个缩略图右上角的 × 按钮
            val idx = index
            val removeBtn = TextView(activity).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
                text = "✕"; textSize = 10f; gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x99000000.toInt())
                    cornerRadius = dp(9).toFloat()
                }
                setOnClickListener {
                    pendingPaths.removeAt(idx)
                    showPreview()
                }
            }
            thumbContainer.addView(removeBtn)
            thumbRow.addView(thumbContainer)
        }

        // ＋ 追加更多图片
        thumbRow.addView(TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            text = "＋"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(c.textSecondary)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(c.accentBg)
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onPickMore?.invoke() }
        })

        scrollView.addView(thumbRow)
        previewLayout.addView(scrollView)

        // 底部提示 + 全部清除
        val bottomRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        bottomRow.addView(TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "${pendingPaths.size} 张图片已选择"
            textSize = 11f; setTextColor(c.textSecondary)
        })
        bottomRow.addView(TextView(activity).apply {
            text = "全部清除"; textSize = 11f; setTextColor(c.errorText)
            setOnClickListener { clear() }
        })
        previewLayout.addView(bottomRow)

        previewContainer.removeAllViews()
        previewContainer.addView(previewLayout)
        previewContainer.visibility = View.VISIBLE
    }

    /** 清除预览区域 */
    fun removePreview() {
        previewContainer.removeAllViews()
        previewContainer.visibility = View.GONE
    }
}