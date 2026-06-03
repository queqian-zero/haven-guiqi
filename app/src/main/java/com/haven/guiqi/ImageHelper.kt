package com.haven.guiqi

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.view.View
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream

/**
 * ImageHelper — 图片处理工具
 *
 * 从 ChatConversationActivity 拆出来的。
 * 负责：图片压缩保存、Base64转换、全屏查看
 */
object ImageHelper {

    /**
     * 从 Uri 压缩并保存图片
     * @return 保存后的文件路径，失败返回 null
     */
    fun compressAndSave(context: Context, uri: Uri, outputDir: File, index: Int = 0): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (original == null) return null

            val maxSize = 800
            val scale = if (original.width > maxSize) maxSize.toFloat() / original.width else 1f
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(original,
                    (original.width * scale).toInt(),
                    (original.height * scale).toInt(), true)
            } else { original }

            val fileName = "img_${System.currentTimeMillis()}_$index.jpg"
            val file = File(outputDir, fileName)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }

            if (scaled != original) scaled.recycle()
            original.recycle()

            file.absolutePath
        } catch (e: Exception) { null }
    }

    /**
     * 图片文件转 Base64 字符串
     */
    fun toBase64(file: File): String {
        val bytes = file.readBytes()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * 全屏查看图片（点击关闭）
     */
    fun showFullImage(activity: Activity, imagePath: String) {
        val file = File(imagePath)
        if (!file.exists()) return
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: return

        val overlay = android.widget.FrameLayout(activity).apply {
            setBackgroundColor(0xDD000000.toInt())
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val imageView = ImageView(activity).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
        }

        overlay.addView(imageView)
        overlay.setOnClickListener {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
        }

        val rootView = activity.window.decorView as android.widget.FrameLayout
        rootView.addView(overlay)
    }
}