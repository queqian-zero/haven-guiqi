package com.haven.guiqi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import java.io.File

/**
 * 每个聊天独立的外观选择。
 *
 * 真正的图片资产仍然只保存在全屋共用的画匣里；这里仅保存当前聊天选中了哪一张，
 * 以及每张背景的构图位置、缩放、当前聊天的模糊与遮罩、每张头像框的缩放和位置。
 */
class ChatAppearanceStorage(context: Context) {

    enum class AvatarDisplayMode(val storageValue: String) {
        AI_ONLY("ai"),
        USER_ONLY("user"),
        BOTH("both");

        val showsFriendAvatar: Boolean get() = this != USER_ONLY
        val showsUserAvatar: Boolean get() = this != AI_ONLY

        companion object {
            fun fromStorage(value: String?): AvatarDisplayMode =
                values().firstOrNull { it.storageValue == value } ?: AI_ONLY
        }
    }

    enum class AvatarShape(val storageValue: String) {
        CIRCLE("circle"),
        SQUARE("square");

        companion object {
            fun fromStorage(value: String?): AvatarShape =
                values().firstOrNull { it.storageValue == value } ?: CIRCLE
        }
    }

    enum class AvatarTarget {
        FRIEND,
        USER
    }

    data class BackgroundEffects(
        val blurRadius: Int,
        val overlayPercent: Int
    )

    data class BackgroundTransform(
        val scalePercent: Int = DEFAULT_BACKGROUND_SCALE_PERCENT,
        val offsetXPercent: Int = DEFAULT_BACKGROUND_OFFSET_PERCENT,
        val offsetYPercent: Int = DEFAULT_BACKGROUND_OFFSET_PERCENT
    )

    data class AvatarFrameTransform(
        val scalePercent: Int = DEFAULT_AVATAR_FRAME_SCALE_PERCENT,
        val offsetXPercent: Int = DEFAULT_AVATAR_FRAME_OFFSET_PERCENT,
        val offsetYPercent: Int = DEFAULT_AVATAR_FRAME_OFFSET_PERCENT
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gallery by lazy { GalleryStorage(appContext) }

    fun getBackgroundItemId(friendId: String): String =
        prefs.getString(backgroundKey(friendId), "").orEmpty()

    fun getAvatarFrameItemId(
        friendId: String,
        target: AvatarTarget = AvatarTarget.FRIEND
    ): String {
        ensureAvatarFrameSplitMigrated(friendId)
        return prefs.getString(frameKey(friendId, target), "").orEmpty()
    }

    /** 当前聊天所选头像框的构图。每张头像框、每个显示对象分别记忆自己的比例和位置。 */
    fun getAvatarFrameTransform(
        friendId: String,
        target: AvatarTarget = AvatarTarget.FRIEND
    ): AvatarFrameTransform {
        val itemId = getAvatarFrameItemId(friendId, target)
        return getAvatarFrameTransform(friendId, itemId, target)
    }

    fun getAvatarFrameTransform(
        friendId: String,
        itemId: String,
        target: AvatarTarget = AvatarTarget.FRIEND
    ): AvatarFrameTransform {
        if (itemId.isEmpty()) return AvatarFrameTransform()
        return AvatarFrameTransform(
            scalePercent = prefs.getInt(
                frameScaleKey(friendId, itemId, target),
                DEFAULT_AVATAR_FRAME_SCALE_PERCENT
            ).coerceIn(MIN_AVATAR_FRAME_SCALE_PERCENT, MAX_AVATAR_FRAME_SCALE_PERCENT),
            offsetXPercent = prefs.getInt(
                frameOffsetXKey(friendId, itemId, target),
                DEFAULT_AVATAR_FRAME_OFFSET_PERCENT
            ).coerceIn(MIN_AVATAR_FRAME_OFFSET_PERCENT, MAX_AVATAR_FRAME_OFFSET_PERCENT),
            offsetYPercent = prefs.getInt(
                frameOffsetYKey(friendId, itemId, target),
                DEFAULT_AVATAR_FRAME_OFFSET_PERCENT
            ).coerceIn(MIN_AVATAR_FRAME_OFFSET_PERCENT, MAX_AVATAR_FRAME_OFFSET_PERCENT)
        )
    }

    fun getAvatarFrameScalePercent(friendId: String): Int =
        getAvatarFrameTransform(friendId).scalePercent

    fun getAvatarDisplayMode(friendId: String): AvatarDisplayMode =
        AvatarDisplayMode.fromStorage(prefs.getString(avatarDisplayModeKey(friendId), null))

    fun setAvatarDisplayMode(friendId: String, mode: AvatarDisplayMode) {
        prefs.edit()
            .putString(avatarDisplayModeKey(friendId), mode.storageValue)
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    fun getAvatarShape(friendId: String, target: AvatarTarget): AvatarShape =
        AvatarShape.fromStorage(prefs.getString(avatarShapeKey(friendId, target), null))

    fun setAvatarShape(friendId: String, target: AvatarTarget, shape: AvatarShape) {
        prefs.edit()
            .putString(avatarShapeKey(friendId, target), shape.storageValue)
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    fun getBackgroundTransform(friendId: String): BackgroundTransform {
        val itemId = getBackgroundItemId(friendId)
        return getBackgroundTransform(friendId, itemId)
    }

    fun getBackgroundTransform(friendId: String, itemId: String): BackgroundTransform {
        if (itemId.isEmpty()) return BackgroundTransform()
        return BackgroundTransform(
            scalePercent = prefs.getInt(
                backgroundScaleKey(friendId, itemId),
                DEFAULT_BACKGROUND_SCALE_PERCENT
            ).coerceIn(MIN_BACKGROUND_SCALE_PERCENT, MAX_BACKGROUND_SCALE_PERCENT),
            offsetXPercent = prefs.getInt(
                backgroundOffsetXKey(friendId, itemId),
                DEFAULT_BACKGROUND_OFFSET_PERCENT
            ).coerceIn(MIN_BACKGROUND_OFFSET_PERCENT, MAX_BACKGROUND_OFFSET_PERCENT),
            offsetYPercent = prefs.getInt(
                backgroundOffsetYKey(friendId, itemId),
                DEFAULT_BACKGROUND_OFFSET_PERCENT
            ).coerceIn(MIN_BACKGROUND_OFFSET_PERCENT, MAX_BACKGROUND_OFFSET_PERCENT)
        )
    }

    fun getBackgroundItem(friendId: String): GalleryStorage.Item? =
        getBackgroundItemId(friendId).takeIf { it.isNotEmpty() }?.let { gallery.find(it) }

    fun getAvatarFrameItem(
        friendId: String,
        target: AvatarTarget = AvatarTarget.FRIEND
    ): GalleryStorage.Item? =
        getAvatarFrameItemId(friendId, target).takeIf { it.isNotEmpty() }?.let { gallery.find(it) }

    fun getBackgroundFile(friendId: String): File? =
        getBackgroundItem(friendId)?.let { gallery.fileFor(it) }?.takeIf { it.isFile }

    fun getAvatarFrameFile(
        friendId: String,
        target: AvatarTarget = AvatarTarget.FRIEND
    ): File? =
        getAvatarFrameItem(friendId, target)?.let { gallery.fileFor(it) }?.takeIf { it.isFile }

    fun getBackgroundEffects(friendId: String): BackgroundEffects = BackgroundEffects(
        blurRadius = prefs.getInt(blurKey(friendId), DEFAULT_BLUR_RADIUS)
            .coerceIn(MIN_BLUR_RADIUS, MAX_BLUR_RADIUS),
        overlayPercent = prefs.getInt(overlayKey(friendId), DEFAULT_OVERLAY_PERCENT)
            .coerceIn(MIN_OVERLAY_PERCENT, MAX_OVERLAY_PERCENT)
    )

    fun setBackgroundEffects(friendId: String, blurRadius: Int, overlayPercent: Int) {
        prefs.edit()
            .putInt(blurKey(friendId), blurRadius.coerceIn(MIN_BLUR_RADIUS, MAX_BLUR_RADIUS))
            .putInt(
                overlayKey(friendId),
                overlayPercent.coerceIn(MIN_OVERLAY_PERCENT, MAX_OVERLAY_PERCENT)
            )
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    fun resetBackgroundEffects(friendId: String) {
        prefs.edit()
            .remove(blurKey(friendId))
            .remove(overlayKey(friendId))
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    fun setBackground(friendId: String, item: GalleryStorage.Item) {
        require(item.category == GalleryStorage.Category.BACKGROUND) { "只能把背景类图片设为聊天背景" }
        prefs.edit()
            .putString(backgroundKey(friendId), item.id)
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    fun clearBackground(friendId: String) {
        prefs.edit()
            .remove(backgroundKey(friendId))
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    fun setBackgroundTransform(friendId: String, transform: BackgroundTransform) {
        val itemId = getBackgroundItemId(friendId)
        if (itemId.isEmpty()) return
        setBackgroundTransform(friendId, itemId, transform)
    }

    fun setBackgroundTransform(
        friendId: String,
        itemId: String,
        transform: BackgroundTransform
    ) {
        if (itemId.isEmpty()) return
        prefs.edit()
            .putInt(
                backgroundScaleKey(friendId, itemId),
                transform.scalePercent.coerceIn(
                    MIN_BACKGROUND_SCALE_PERCENT,
                    MAX_BACKGROUND_SCALE_PERCENT
                )
            )
            .putInt(
                backgroundOffsetXKey(friendId, itemId),
                transform.offsetXPercent.coerceIn(
                    MIN_BACKGROUND_OFFSET_PERCENT,
                    MAX_BACKGROUND_OFFSET_PERCENT
                )
            )
            .putInt(
                backgroundOffsetYKey(friendId, itemId),
                transform.offsetYPercent.coerceIn(
                    MIN_BACKGROUND_OFFSET_PERCENT,
                    MAX_BACKGROUND_OFFSET_PERCENT
                )
            )
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    fun resetBackgroundTransform(friendId: String) {
        val itemId = getBackgroundItemId(friendId)
        if (itemId.isEmpty()) return
        prefs.edit()
            .remove(backgroundScaleKey(friendId, itemId))
            .remove(backgroundOffsetXKey(friendId, itemId))
            .remove(backgroundOffsetYKey(friendId, itemId))
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    fun setAvatarFrameTransform(
        friendId: String,
        transform: AvatarFrameTransform,
        target: AvatarTarget = AvatarTarget.FRIEND
    ) {
        val itemId = getAvatarFrameItemId(friendId, target)
        if (itemId.isEmpty()) return
        setAvatarFrameTransform(friendId, itemId, transform, target)
    }

    fun setAvatarFrameTransform(
        friendId: String,
        itemId: String,
        transform: AvatarFrameTransform,
        target: AvatarTarget = AvatarTarget.FRIEND
    ) {
        if (itemId.isEmpty()) return
        prefs.edit()
            .putInt(
                frameScaleKey(friendId, itemId, target),
                transform.scalePercent.coerceIn(
                    MIN_AVATAR_FRAME_SCALE_PERCENT,
                    MAX_AVATAR_FRAME_SCALE_PERCENT
                )
            )
            .putInt(
                frameOffsetXKey(friendId, itemId, target),
                transform.offsetXPercent.coerceIn(
                    MIN_AVATAR_FRAME_OFFSET_PERCENT,
                    MAX_AVATAR_FRAME_OFFSET_PERCENT
                )
            )
            .putInt(
                frameOffsetYKey(friendId, itemId, target),
                transform.offsetYPercent.coerceIn(
                    MIN_AVATAR_FRAME_OFFSET_PERCENT,
                    MAX_AVATAR_FRAME_OFFSET_PERCENT
                )
            )
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    fun setAvatarFrameScalePercent(friendId: String, scalePercent: Int) {
        val current = getAvatarFrameTransform(friendId)
        setAvatarFrameTransform(friendId, current.copy(scalePercent = scalePercent))
    }

    fun resetAvatarFrameScale(friendId: String) {
        val itemId = getAvatarFrameItemId(friendId, AvatarTarget.FRIEND)
        if (itemId.isEmpty()) return
        prefs.edit()
            .remove(frameScaleKey(friendId, itemId, AvatarTarget.FRIEND))
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    fun resetAvatarFrameTransform(friendId: String) {
        val itemId = getAvatarFrameItemId(friendId, AvatarTarget.FRIEND)
        if (itemId.isEmpty()) return
        prefs.edit()
            .remove(frameScaleKey(friendId, itemId, AvatarTarget.FRIEND))
            .remove(frameOffsetXKey(friendId, itemId, AvatarTarget.FRIEND))
            .remove(frameOffsetYKey(friendId, itemId, AvatarTarget.FRIEND))
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    fun setAvatarFrame(
        friendId: String,
        item: GalleryStorage.Item,
        target: AvatarTarget = AvatarTarget.FRIEND
    ) {
        require(item.category == GalleryStorage.Category.AVATAR_FRAME) { "只能把头像框类图片设为头像框" }
        ensureAvatarFrameSplitMigrated(friendId)
        prefs.edit()
            .putString(frameKey(friendId, target), item.id)
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    /**
     * 只摘掉指定对象的头像框。变换参数按“对象 + 素材”保留，之后重新戴回同一张时仍能复原。
     */
    fun clearAvatarFrame(
        friendId: String,
        target: AvatarTarget = AvatarTarget.FRIEND
    ) {
        ensureAvatarFrameSplitMigrated(friendId)
        prefs.edit()
            .remove(frameKey(friendId, target))
            .putLong(revisionKey(friendId), nextRevision(friendId))
            .apply()
    }

    fun getRevision(friendId: String): Long = prefs.getLong(revisionKey(friendId), 0L)

    fun clearForFriend(friendId: String) {
        val editor = prefs.edit()
            .remove(backgroundKey(friendId))
            .remove(legacySharedFrameKey(friendId))
            .remove(frameKey(friendId, AvatarTarget.FRIEND))
            .remove(frameKey(friendId, AvatarTarget.USER))
            .remove(frameMigrationKey(friendId))
            .remove(avatarDisplayModeKey(friendId))
            .remove(avatarShapeKey(friendId, AvatarTarget.FRIEND))
            .remove(avatarShapeKey(friendId, AvatarTarget.USER))
            .remove(blurKey(friendId))
            .remove(overlayKey(friendId))
            .remove(revisionKey(friendId))
        val prefixes = listOf(
            frameScalePrefix(friendId, AvatarTarget.FRIEND),
            frameOffsetXPrefix(friendId, AvatarTarget.FRIEND),
            frameOffsetYPrefix(friendId, AvatarTarget.FRIEND),
            frameScalePrefix(friendId, AvatarTarget.USER),
            frameOffsetXPrefix(friendId, AvatarTarget.USER),
            frameOffsetYPrefix(friendId, AvatarTarget.USER),
            backgroundScalePrefix(friendId),
            backgroundOffsetXPrefix(friendId),
            backgroundOffsetYPrefix(friendId)
        )
        prefs.all.keys.filter { key -> prefixes.any(key::startsWith) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * 在后台线程解码当前聊天背景。图片按中心裁切绘制，不拉伸变形。
     * 模糊在后台完成；遮罩在 Drawable 绘制时叠加。
     * 返回 null 表示使用内置默认背景。
     */
    fun loadBackgroundDrawable(friendId: String, targetWidth: Int, targetHeight: Int): Drawable? {
        val file = getBackgroundFile(friendId) ?: return null
        val effects = getBackgroundEffects(friendId)
        val transform = getBackgroundTransform(friendId)
        val bitmap = loadBackgroundBitmap(
            file = file,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            blurRadius = effects.blurRadius
        ) ?: return null
        return CenterCropBitmapDrawable(
            bitmap = bitmap,
            overlayColor = overlayColor(effects.overlayPercent),
            transform = transform
        )
    }

    /** 给设置页预览使用：后台调用，返回值由调用者负责回收。 */
    fun loadBackgroundBitmap(
        file: File,
        targetWidth: Int,
        targetHeight: Int,
        blurRadius: Int
    ): Bitmap? {
        val decoded = decodeSampled(file, targetWidth, targetHeight) ?: return null
        return if (blurRadius > 0) {
            blurForBackground(decoded, blurRadius)
        } else {
            decoded
        }
    }

    fun overlayColor(overlayPercent: Int): Int {
        val overlayBase = if (ThemeHelper.isDark(appContext)) Color.BLACK else Color.WHITE
        val overlayAlpha = overlayPercent
            .coerceIn(MIN_OVERLAY_PERCENT, MAX_OVERLAY_PERCENT) * 255 / 100
        return Color.argb(
            overlayAlpha,
            Color.red(overlayBase),
            Color.green(overlayBase),
            Color.blue(overlayBase)
        )
    }

    /** 只在主线程调用，把已经解码好的背景装到页面上。 */
    fun applyBackground(target: View, drawable: Drawable?) {
        releaseBackground(target)
        if (drawable == null) {
            target.setBackgroundResource(R.drawable.chat_bg)
        } else {
            target.background = drawable
        }
    }

    fun releaseBackground(target: View) {
        releaseDrawable(target.background)
    }

    fun releaseDrawable(drawable: Drawable?) {
        (drawable as? CenterCropBitmapDrawable)?.release()
    }

    private fun decodeSampled(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val safeWidth = targetWidth.coerceAtLeast(1)
        val safeHeight = targetHeight.coerceAtLeast(1)
        val targetLongSide = maxOf(safeWidth, safeHeight).coerceAtLeast(1)
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > targetLongSide * 3 / 2) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    /**
     * 背景模糊不需要保留原图像素级清晰度。先限制工作位图尺寸，再做两次盒式模糊，
     * 避免复杂大图在低端手机上占用过多内存和 CPU。
     */
    private fun blurForBackground(source: Bitmap, requestedRadius: Int): Bitmap {
        val longSide = maxOf(source.width, source.height)
        val working = if (longSide > BLUR_WORKING_LONG_SIDE) {
            val scale = BLUR_WORKING_LONG_SIDE.toFloat() / longSide.toFloat()
            val scaled = Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== source && !source.isRecycled) source.recycle()
            scaled
        } else {
            source
        }

        val radius = requestedRadius.coerceIn(1, MAX_BLUR_RADIUS)
        val width = working.width
        val height = working.height
        val first = IntArray(width * height)
        val second = IntArray(width * height)
        working.getPixels(first, 0, width, 0, 0, width, height)

        boxBlurHorizontal(first, second, width, height, radius)
        boxBlurVertical(second, first, width, height, radius)
        // 再做一轮小半径模糊，让结果更接近高斯模糊但仍保持 O(n)。
        val secondRadius = (radius / 2).coerceAtLeast(1)
        boxBlurHorizontal(first, second, width, height, secondRadius)
        boxBlurVertical(second, first, width, height, secondRadius)

        working.setPixels(first, 0, width, 0, 0, width, height)
        return working
    }

    private fun boxBlurHorizontal(
        source: IntArray,
        destination: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ) {
        for (y in 0 until height) {
            val row = y * width
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0

            for (x in -radius..radius) {
                val color = source[row + x.coerceIn(0, width - 1)]
                sumA += color ushr 24
                sumR += color shr 16 and 0xFF
                sumG += color shr 8 and 0xFF
                sumB += color and 0xFF
            }

            val count = radius * 2 + 1
            for (x in 0 until width) {
                destination[row + x] =
                    (sumA / count shl 24) or
                    (sumR / count shl 16) or
                    (sumG / count shl 8) or
                    (sumB / count)

                val remove = source[row + (x - radius).coerceIn(0, width - 1)]
                val add = source[row + (x + radius + 1).coerceIn(0, width - 1)]
                sumA += (add ushr 24) - (remove ushr 24)
                sumR += (add shr 16 and 0xFF) - (remove shr 16 and 0xFF)
                sumG += (add shr 8 and 0xFF) - (remove shr 8 and 0xFF)
                sumB += (add and 0xFF) - (remove and 0xFF)
            }
        }
    }

    private fun boxBlurVertical(
        source: IntArray,
        destination: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ) {
        for (x in 0 until width) {
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0

            for (y in -radius..radius) {
                val color = source[y.coerceIn(0, height - 1) * width + x]
                sumA += color ushr 24
                sumR += color shr 16 and 0xFF
                sumG += color shr 8 and 0xFF
                sumB += color and 0xFF
            }

            val count = radius * 2 + 1
            for (y in 0 until height) {
                destination[y * width + x] =
                    (sumA / count shl 24) or
                    (sumR / count shl 16) or
                    (sumG / count shl 8) or
                    (sumB / count)

                val remove = source[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = source[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                sumA += (add ushr 24) - (remove ushr 24)
                sumR += (add shr 16 and 0xFF) - (remove shr 16 and 0xFF)
                sumG += (add shr 8 and 0xFF) - (remove shr 8 and 0xFF)
                sumB += (add and 0xFF) - (remove and 0xFF)
            }
        }
    }

    private class CenterCropBitmapDrawable(
        private var bitmap: Bitmap?,
        private val overlayColor: Int,
        private val transform: BackgroundTransform
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = overlayColor }

        override fun draw(canvas: Canvas) {
            val image = bitmap ?: return
            if (image.isRecycled || bounds.isEmpty) return

            val destination = ChatAppearanceStorage.calculateBackgroundDestination(
                imageWidth = image.width,
                imageHeight = image.height,
                targetLeft = bounds.left.toFloat(),
                targetTop = bounds.top.toFloat(),
                targetWidth = bounds.width().toFloat(),
                targetHeight = bounds.height().toFloat(),
                transform = transform
            )
            canvas.drawBitmap(image, null, destination, paint)
            if (Color.alpha(overlayColor) > 0) canvas.drawRect(bounds, overlayPaint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            overlayPaint.alpha = Color.alpha(overlayColor) * alpha / 255
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        fun release() {
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = null
        }
    }

    /**
     * v11.6.3.5 将原先“双方共用一张框”的旧键拆成两套。
     * 迁移按当时真实显示模式分配，避免升级后凭空给原本未显示的一方戴框：
     * 仅住户 → 住户；仅我 → 用户；双方 → 双方。
     */
    private fun ensureAvatarFrameSplitMigrated(friendId: String) {
        if (prefs.getBoolean(frameMigrationKey(friendId), false)) return
        synchronized(prefs) {
            if (prefs.getBoolean(frameMigrationKey(friendId), false)) return
            val legacyItemId = prefs.getString(legacySharedFrameKey(friendId), "").orEmpty()
            val editor = prefs.edit()
            if (legacyItemId.isNotEmpty()) {
                when (AvatarDisplayMode.fromStorage(
                    prefs.getString(avatarDisplayModeKey(friendId), null)
                )) {
                    AvatarDisplayMode.AI_ONLY ->
                        editor.putString(frameKey(friendId, AvatarTarget.FRIEND), legacyItemId)
                    AvatarDisplayMode.USER_ONLY ->
                        editor.putString(frameKey(friendId, AvatarTarget.USER), legacyItemId)
                    AvatarDisplayMode.BOTH -> {
                        editor.putString(frameKey(friendId, AvatarTarget.FRIEND), legacyItemId)
                        editor.putString(frameKey(friendId, AvatarTarget.USER), legacyItemId)
                    }
                }
            }
            editor.remove(legacySharedFrameKey(friendId))
                .putBoolean(frameMigrationKey(friendId), true)
                .commit()
        }
    }

    private fun nextRevision(friendId: String): Long =
        maxOf(System.currentTimeMillis(), getRevision(friendId) + 1L)

    private fun backgroundKey(friendId: String) = "background_$friendId"
    private fun frameKey(friendId: String, target: AvatarTarget) =
        "avatar_frame_${target.name.lowercase()}_$friendId"
    private fun legacySharedFrameKey(friendId: String) = "avatar_frame_$friendId"
    private fun frameMigrationKey(friendId: String) = "avatar_frame_split_migrated_$friendId"
    private fun avatarDisplayModeKey(friendId: String) = "avatar_display_mode_$friendId"
    private fun avatarShapeKey(friendId: String, target: AvatarTarget) =
        "avatar_shape_${target.name.lowercase()}_$friendId"

    /**
     * FRIEND 继续沿用 v11.6.3 的旧键名，保证已经调好的住户头像框位置无损迁移；
     * USER 使用独立前缀。
     */
    private fun frameScalePrefix(friendId: String, target: AvatarTarget) =
        if (target == AvatarTarget.FRIEND) {
            "avatar_frame_scale_${friendId}_"
        } else {
            "user_avatar_frame_scale_${friendId}_"
        }

    private fun frameOffsetXPrefix(friendId: String, target: AvatarTarget) =
        if (target == AvatarTarget.FRIEND) {
            "avatar_frame_offset_x_${friendId}_"
        } else {
            "user_avatar_frame_offset_x_${friendId}_"
        }

    private fun frameOffsetYPrefix(friendId: String, target: AvatarTarget) =
        if (target == AvatarTarget.FRIEND) {
            "avatar_frame_offset_y_${friendId}_"
        } else {
            "user_avatar_frame_offset_y_${friendId}_"
        }

    private fun frameScaleKey(friendId: String, itemId: String, target: AvatarTarget) =
        frameScalePrefix(friendId, target) + itemId
    private fun frameOffsetXKey(friendId: String, itemId: String, target: AvatarTarget) =
        frameOffsetXPrefix(friendId, target) + itemId
    private fun frameOffsetYKey(friendId: String, itemId: String, target: AvatarTarget) =
        frameOffsetYPrefix(friendId, target) + itemId
    private fun backgroundScalePrefix(friendId: String) = "background_scale_${friendId}_"
    private fun backgroundOffsetXPrefix(friendId: String) = "background_offset_x_${friendId}_"
    private fun backgroundOffsetYPrefix(friendId: String) = "background_offset_y_${friendId}_"
    private fun backgroundScaleKey(friendId: String, itemId: String) = backgroundScalePrefix(friendId) + itemId
    private fun backgroundOffsetXKey(friendId: String, itemId: String) = backgroundOffsetXPrefix(friendId) + itemId
    private fun backgroundOffsetYKey(friendId: String, itemId: String) = backgroundOffsetYPrefix(friendId) + itemId
    private fun blurKey(friendId: String) = "background_blur_$friendId"
    private fun overlayKey(friendId: String) = "background_overlay_$friendId"
    private fun revisionKey(friendId: String) = "revision_$friendId"

    companion object {
        private const val PREFS_NAME = "haven_chat_appearance"
        const val MIN_BLUR_RADIUS = 0
        const val MAX_BLUR_RADIUS = 30
        const val DEFAULT_BLUR_RADIUS = 0
        const val MIN_OVERLAY_PERCENT = 0
        const val MAX_OVERLAY_PERCENT = 70
        const val DEFAULT_OVERLAY_PERCENT = 18
        const val MIN_AVATAR_FRAME_SCALE_PERCENT = 50
        const val MAX_AVATAR_FRAME_SCALE_PERCENT = 200
        const val DEFAULT_AVATAR_FRAME_SCALE_PERCENT = 100
        const val MIN_AVATAR_FRAME_OFFSET_PERCENT = -100
        const val MAX_AVATAR_FRAME_OFFSET_PERCENT = 100
        const val DEFAULT_AVATAR_FRAME_OFFSET_PERCENT = 0
        const val MIN_BACKGROUND_SCALE_PERCENT = 100
        const val MAX_BACKGROUND_SCALE_PERCENT = 250
        const val DEFAULT_BACKGROUND_SCALE_PERCENT = 100
        const val MIN_BACKGROUND_OFFSET_PERCENT = -100
        const val MAX_BACKGROUND_OFFSET_PERCENT = 100
        const val DEFAULT_BACKGROUND_OFFSET_PERCENT = 0
        private const val BLUR_WORKING_LONG_SIDE = 720

        fun calculateBackgroundDestination(
            imageWidth: Int,
            imageHeight: Int,
            targetLeft: Float,
            targetTop: Float,
            targetWidth: Float,
            targetHeight: Float,
            transform: BackgroundTransform
        ): RectF {
            val safeImageWidth = imageWidth.coerceAtLeast(1).toFloat()
            val safeImageHeight = imageHeight.coerceAtLeast(1).toFloat()
            val baseScale = maxOf(targetWidth / safeImageWidth, targetHeight / safeImageHeight)
            val zoom = transform.scalePercent.coerceIn(
                MIN_BACKGROUND_SCALE_PERCENT,
                MAX_BACKGROUND_SCALE_PERCENT
            ) / 100f
            val drawWidth = safeImageWidth * baseScale * zoom
            val drawHeight = safeImageHeight * baseScale * zoom
            val overflowX = (drawWidth - targetWidth).coerceAtLeast(0f)
            val overflowY = (drawHeight - targetHeight).coerceAtLeast(0f)
            val shiftX = transform.offsetXPercent.coerceIn(
                MIN_BACKGROUND_OFFSET_PERCENT,
                MAX_BACKGROUND_OFFSET_PERCENT
            ) / 100f * overflowX / 2f
            val shiftY = transform.offsetYPercent.coerceIn(
                MIN_BACKGROUND_OFFSET_PERCENT,
                MAX_BACKGROUND_OFFSET_PERCENT
            ) / 100f * overflowY / 2f
            val left = targetLeft + (targetWidth - drawWidth) / 2f + shiftX
            val top = targetTop + (targetHeight - drawHeight) / 2f + shiftY
            return RectF(left, top, left + drawWidth, top + drawHeight)
        }
    }
}
