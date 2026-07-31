package com.haven.guiqi

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** 在聊天设置里从画匣挑背景或头像框。 */
object GalleryAssetPickerDialog {

    fun show(
        activity: Activity,
        category: GalleryStorage.Category,
        title: String,
        selectedItemId: String? = null,
        onChosen: (GalleryStorage.Item) -> Unit
    ) {
        val storage = GalleryStorage(activity.applicationContext)
        val items = storage.listByCategory(category)
        if (items.isEmpty()) {
            Toast.makeText(activity, "画匣的${category.label}分类还是空的", Toast.LENGTH_SHORT).show()
            return
        }

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val colors = ThemeHelper.getColors(activity)
        val executor = Executors.newFixedThreadPool(2)

        val grid = GridLayout(activity).apply {
            columnCount = 3
            setPadding(dp(10), dp(10), dp(10), dp(16))
        }
        val scroll = ScrollView(activity).apply { addView(grid) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("取消", null)
            .create()

        items.forEachIndexed { index, item ->
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(5), dp(5), dp(7))
                background = GradientDrawable().apply {
                    setColor(colors.card)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (item.id == selectedItemId) 2 else 1),
                        if (item.id == selectedItemId) colors.accentStrong else colors.border)
                }
                setOnClickListener {
                    onChosen(item)
                    dialog.dismiss()
                }
            }

            val image = ImageView(activity).apply {
                scaleType = if (category == GalleryStorage.Category.AVATAR_FRAME) {
                    ImageView.ScaleType.FIT_CENTER
                } else {
                    ImageView.ScaleType.CENTER_CROP
                }
                contentDescription = item.displayName
                setBackgroundColor(colors.backgroundSecondary)
            }
            tile.addView(image, LinearLayout.LayoutParams(dp(92), dp(92)))

            tile.addView(TextView(activity).apply {
                text = if (item.id == selectedItemId) "✓ ${item.displayName}" else item.displayName
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(colors.textSecondary)
            }, LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
            })

            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(index % 3, 1, 1f)
                rowSpec = GridLayout.spec(index / 3)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })

            val file = storage.fileFor(item)
            image.tag = file.absolutePath
            executor.execute {
                val bitmap = decodeSampled(file, dp(184))
                activity.runOnUiThread {
                    if (!activity.isFinishing && image.tag == file.absolutePath) {
                        if (bitmap != null) image.setImageBitmap(bitmap)
                    } else {
                        bitmap?.recycle()
                    }
                }
            }
        }

        dialog.setOnDismissListener { executor.shutdownNow() }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.94f).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
            )
        }
        dialog.show()
    }

    private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > targetPx * 2) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }
}
