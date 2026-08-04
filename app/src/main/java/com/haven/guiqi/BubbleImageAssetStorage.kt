package com.haven.guiqi

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 图片气泡素材的私有文件存储。
 *
 * 选中的图片会复制到 App 私有目录，因此不依赖相册 URI 的长期权限。
 * 每次导入都会先写临时文件、校验确实能被解码，再原子式改名为正式素材。
 */
internal class BubbleImageAssetStorage(context: Context) {

    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    fun importImage(
        uri: Uri,
        friendId: String,
        target: BubbleStyleStorage.Target
    ): File {
        val mime = appContext.contentResolver.getType(uri).orEmpty()
        require(mime.isEmpty() || mime.startsWith("image/")) { "只能选择图片文件" }

        val extension = extensionFor(mime)
        val safeOwner = Integer.toHexString(friendId.hashCode())
        val temp = File(rootDir, ".${UUID.randomUUID()}.$extension.part")
        val result = File(
            rootDir,
            "bubble_${safeOwner}_${target.storageValue}_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension"
        )

        try {
            appContext.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取这张图片" }
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_ASSET_BYTES) { "图片太大了，请选择 16MB 以内的图片" }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别这张图片" }
            require(bounds.outWidth <= MAX_IMAGE_EDGE && bounds.outHeight <= MAX_IMAGE_EDGE) {
                "图片尺寸太大，请选择边长不超过 8192 像素的图片"
            }

            require(temp.renameTo(result)) { "保存图片气泡素材失败" }
            return result
        } catch (error: Exception) {
            temp.delete()
            result.delete()
            throw error
        }
    }

    fun deleteManagedPath(path: String) {
        if (!isManagedPath(path)) return
        BubbleImageBitmapCache.remove(path)
        runCatching { File(path).delete() }
    }

    fun isManagedPath(path: String): Boolean {
        if (path.isBlank()) return false
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val root = runCatching { rootDir.canonicalFile }.getOrNull() ?: return false
        return file.parentFile == root
    }

    private fun extensionFor(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/jpeg", "image/jpg" -> "jpg"
        else -> "img"
    }

    companion object {
        private const val DIRECTORY_NAME = "bubble_assets"
        private const val MAX_ASSET_BYTES = 16L * 1024L * 1024L
        private const val MAX_IMAGE_EDGE = 8192
    }
}
