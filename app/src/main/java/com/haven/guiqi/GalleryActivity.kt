package com.haven.guiqi

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.concurrent.Executors

/** 全屋共用的画匣。分类只负责整理，不负责限制住户使用。 */
class GalleryActivity : AppCompatActivity() {

    private val storage by lazy { GalleryStorage(this) }
    private val c get() = ThemeHelper.getColors(this)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private lateinit var root: LinearLayout
    private lateinit var filterBar: LinearLayout
    private lateinit var albumBar: LinearLayout
    private lateinit var albumScroll: HorizontalScrollView
    private lateinit var scrollView: ScrollView
    private lateinit var grid: GridLayout
    private lateinit var emptyView: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var countText: TextView

    private var currentCategory: GalleryStorage.Category? = null
    private var currentAlbumId: String? = null
    private var showUnclassifiedOnly = false

    private var pendingImportCategory = GalleryStorage.Category.GENERAL
    private var pendingImportAlbumId: String? = null
    private val imageExecutor = Executors.newFixedThreadPool(2)

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        var success = 0
        val failures = mutableListOf<String>()
        uris.forEach { uri ->
            try {
                storage.importImage(uri, pendingImportCategory, pendingImportAlbumId)
                success++
            } catch (e: Exception) {
                failures += (e.message ?: "未知错误")
            }
        }
        val message = when {
            failures.isEmpty() -> "已放进画匣：$success 张"
            success > 0 -> "放入 $success 张，${failures.size} 张失败"
            else -> "导入失败：${failures.firstOrNull() ?: "无法读取图片"}"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        currentCategory = pendingImportCategory
        currentAlbumId = pendingImportAlbumId
        showUnclassifiedOnly = pendingImportAlbumId == null
        renderFilters()
        renderAlbumFilters()
        renderGallery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        buildPage()
        renderFilters()
        renderAlbumFilters()
        renderGallery()
    }

    override fun onResume() {
        super.onResume()
        if (::grid.isInitialized) {
            renderAlbumFilters()
            renderGallery()
        }
    }

    override fun onDestroy() {
        imageExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = !ThemeHelper.isDark(this)
    }

    private fun buildPage() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.background)
        }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(10))

            addView(TextView(this@GalleryActivity).apply {
                text = "‹"
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(c.textPrimary)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))

            addView(LinearLayout(this@GalleryActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@GalleryActivity).apply {
                    text = "画匣"
                    textSize = 21f
                    setTextColor(c.textPrimary)
                })
                countText = TextView(this@GalleryActivity).apply {
                    textSize = 11f
                    setTextColor(c.textSecondary)
                }
                addView(countText, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            })

            addView(TextView(this@GalleryActivity).apply {
                text = "＋"
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(c.textPrimary)
                background = rounded(c.card, 14, c.border)
                setOnClickListener { chooseImportCategory() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        root.addView(header)

        filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(8))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filterBar)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        albumBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), dp(12))
        }
        albumScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(albumBar)
        }
        root.addView(albumScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val contentFrame = FrameLayout(this)
        root.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        grid = GridLayout(this).apply {
            columnCount = 3
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            setPadding(dp(14), dp(4), dp(14), dp(28))
        }
        scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(grid, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        contentFrame.addView(scrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(72))

            emptyTitle = TextView(this@GalleryActivity).apply {
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(c.textPrimary)
            }
            addView(emptyTitle)

            emptyHint = TextView(this@GalleryActivity).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(c.textSecondary)
            }
            addView(emptyHint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        contentFrame.addView(emptyView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun renderFilters() {
        filterBar.removeAllViews()
        val filters = listOf<Pair<String, GalleryStorage.Category?>>(
            "全部" to null,
            "头像" to GalleryStorage.Category.AVATAR,
            "背景" to GalleryStorage.Category.BACKGROUND,
            "表情包" to GalleryStorage.Category.STICKER,
            "普通图片" to GalleryStorage.Category.GENERAL
        )
        filters.forEachIndexed { index, (label, category) ->
            addChip(
                parent = filterBar,
                label = label,
                selected = currentCategory == category,
                addStartMargin = index > 0
            ) {
                currentCategory = category
                currentAlbumId = null
                showUnclassifiedOnly = false
                renderFilters()
                renderAlbumFilters()
                renderGallery()
            }
        }
    }

    private fun renderAlbumFilters() {
        albumBar.removeAllViews()
        val category = currentCategory
        if (category == null) {
            albumScroll.visibility = View.GONE
            return
        }
        albumScroll.visibility = View.VISIBLE

        addChip(
            parent = albumBar,
            label = "全部",
            selected = currentAlbumId == null && !showUnclassifiedOnly,
            addStartMargin = false,
            compact = true
        ) {
            currentAlbumId = null
            showUnclassifiedOnly = false
            renderAlbumFilters()
            renderGallery()
        }

        addChip(
            parent = albumBar,
            label = "未分类",
            selected = showUnclassifiedOnly,
            addStartMargin = true,
            compact = true
        ) {
            currentAlbumId = null
            showUnclassifiedOnly = true
            renderAlbumFilters()
            renderGallery()
        }

        storage.listAlbums(category).forEach { album ->
            val chip = addChip(
                parent = albumBar,
                label = album.name,
                selected = currentAlbumId == album.id && !showUnclassifiedOnly,
                addStartMargin = true,
                compact = true
            ) {
                currentAlbumId = album.id
                showUnclassifiedOnly = false
                renderAlbumFilters()
                renderGallery()
            }
            chip.setOnLongClickListener {
                showAlbumMenu(album)
                true
            }
        }

        addChip(
            parent = albumBar,
            label = "＋",
            selected = false,
            addStartMargin = true,
            compact = true
        ) {
            showCreateAlbumDialog(category) { album ->
                currentAlbumId = album.id
                showUnclassifiedOnly = false
                renderAlbumFilters()
                renderGallery()
            }
        }
    }

    private fun addChip(
        parent: LinearLayout,
        label: String,
        selected: Boolean,
        addStartMargin: Boolean,
        compact: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val chip = TextView(this).apply {
            text = label
            textSize = if (compact) 11.5f else 12f
            gravity = Gravity.CENTER
            setTextColor(if (selected) c.textOnAccent else c.textSecondary)
            background = rounded(if (selected) c.accentStrong else c.card, 16, c.border)
            setPadding(
                dp(if (compact) 13 else 15),
                dp(if (compact) 7 else 8),
                dp(if (compact) 13 else 15),
                dp(if (compact) 7 else 8)
            )
            setOnClickListener { onClick() }
        }
        parent.addView(chip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { if (addStartMargin) marginStart = dp(8) })
        return chip
    }

    private fun renderGallery() {
        val items = storage.listByCategory(
            category = currentCategory,
            albumId = currentAlbumId,
            unclassifiedOnly = showUnclassifiedOnly
        )
        val total = storage.count()
        countText.text = if (total == 0) "全屋共用 · 还没有图片" else "全屋共用 · $total 张图片"
        grid.removeAllViews()

        val isEmpty = items.isEmpty()
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        scrollView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        if (isEmpty) {
            updateEmptyCopy()
            return
        }

        val side = ((resources.displayMetrics.widthPixels - dp(28) - dp(16)) / 3f).toInt()
        items.forEachIndexed { index, item ->
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(c.card, 12, c.border)
                clipToOutline = true
                contentDescription = item.displayName
                setOnClickListener { showPreview(item) }
                setOnLongClickListener {
                    showItemMenu(item)
                    true
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = side
                height = side
                val column = index % 3
                setMargins(
                    if (column == 0) 0 else dp(4),
                    dp(4),
                    if (column == 2) 0 else dp(4),
                    dp(4)
                )
            }
            grid.addView(image, params)
            loadSampled(storage.fileFor(item), side * 2, image)
        }
    }

    private fun updateEmptyCopy() {
        when {
            storage.count() == 0 -> {
                emptyTitle.text = "画匣还是空的"
                emptyHint.text = "点右上角，把图片放进全屋共用的画匣。"
            }
            currentCategory == null -> {
                emptyTitle.text = "这里还没有图片"
                emptyHint.text = "换一个分类看看。"
            }
            showUnclassifiedOnly -> {
                emptyTitle.text = "没有未分类图片"
                emptyHint.text = "新上传的图片可以先放在这里。"
            }
            currentAlbumId != null -> {
                emptyTitle.text = "这个分类还是空的"
                emptyHint.text = "上传图片，或长按其他图片把它移动进来。"
            }
            else -> {
                emptyTitle.text = "这一类还没有图片"
                emptyHint.text = "点右上角，把图片放进来。"
            }
        }
    }

    private fun chooseImportCategory() {
        val categories = GalleryStorage.Category.values().asList().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("放到哪一类")
            .setItems(categories.map { it.label }.toTypedArray()) { _, which ->
                pendingImportCategory = categories[which]
                chooseImportAlbum(pendingImportCategory)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseImportAlbum(category: GalleryStorage.Category) {
        chooseAlbumDestination(
            category = category,
            title = "放进 ${category.label} 的哪一组",
            allowCreate = true
        ) { albumId ->
            pendingImportAlbumId = albumId
            imagePicker.launch(arrayOf("image/*"))
        }
    }

    private fun chooseAlbumDestination(
        category: GalleryStorage.Category,
        title: String,
        allowCreate: Boolean,
        onChosen: (String?) -> Unit
    ) {
        val albums = storage.listAlbums(category)
        val labels = mutableListOf("未分类")
        labels += albums.map { it.name }
        if (allowCreate) labels += "＋ 新建分类"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> onChosen(null)
                    which <= albums.size -> onChosen(albums[which - 1].id)
                    else -> showCreateAlbumDialog(category) { album -> onChosen(album.id) }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCreateAlbumDialog(
        category: GalleryStorage.Category,
        onCreated: (GalleryStorage.Album) -> Unit
    ) {
        val input = EditText(this).apply {
            hint = "例如：这个 IP、猫猫、阴阳怪气"
            setSingleLine(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("新建 ${category.label} 分类")
            .setView(input)
            .setPositiveButton("新建", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val album = storage.createAlbum(category, input.text.toString())
                    dialog.dismiss()
                    onCreated(album)
                } catch (e: IllegalArgumentException) {
                    input.error = e.message ?: "分类名不可用"
                }
            }
        }
        dialog.show()
    }

    private fun showAlbumMenu(album: GalleryStorage.Album) {
        AlertDialog.Builder(this)
            .setTitle(album.name)
            .setItems(arrayOf("重命名", "删除分类")) { _, which ->
                when (which) {
                    0 -> showRenameAlbumDialog(album)
                    1 -> confirmDeleteAlbum(album)
                }
            }
            .show()
    }

    private fun showRenameAlbumDialog(album: GalleryStorage.Album) {
        val input = EditText(this).apply {
            setText(album.name)
            setSelection(text.length)
            setSingleLine(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("重命名分类")
            .setView(input)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    storage.renameAlbum(album.id, input.text.toString())
                    dialog.dismiss()
                    renderAlbumFilters()
                    renderGallery()
                } catch (e: IllegalArgumentException) {
                    input.error = e.message ?: "分类名不可用"
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteAlbum(album: GalleryStorage.Album) {
        AlertDialog.Builder(this)
            .setTitle("删除分类“${album.name}”？")
            .setMessage("图片不会被删除，会回到“未分类”。")
            .setPositiveButton("删除分类") { _, _ ->
                storage.deleteAlbum(album.id)
                if (currentAlbumId == album.id) {
                    currentAlbumId = null
                    showUnclassifiedOnly = true
                }
                renderAlbumFilters()
                renderGallery()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showItemMenu(item: GalleryStorage.Item) {
        val albumName = storage.findAlbum(item.albumId)?.name ?: "未分类"
        val options = arrayOf("查看原图", "移动分类", "删除")
        AlertDialog.Builder(this)
            .setTitle("${item.displayName}\n${item.category.label} · $albumName")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPreview(item)
                    1 -> chooseMoveCategory(item)
                    2 -> confirmDelete(item)
                }
            }
            .show()
    }

    private fun chooseMoveCategory(item: GalleryStorage.Item) {
        val categories = GalleryStorage.Category.values().asList().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("移动到哪一类")
            .setSingleChoiceItems(
                categories.map { it.label }.toTypedArray(),
                categories.indexOf(item.category)
            ) { dialog, which ->
                val targetCategory = categories[which]
                dialog.dismiss()
                chooseAlbumDestination(
                    category = targetCategory,
                    title = "放进 ${targetCategory.label} 的哪一组",
                    allowCreate = true
                ) { targetAlbumId ->
                    storage.move(item.id, targetCategory, targetAlbumId)
                    renderAlbumFilters()
                    renderGallery()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(item: GalleryStorage.Item) {
        AlertDialog.Builder(this)
            .setTitle("从画匣删除？")
            .setMessage("这会删除画匣里的副本，不会动你手机相册中的原图。")
            .setPositiveButton("删除") { _, _ ->
                storage.delete(item.id)
                renderGallery()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPreview(item: GalleryStorage.Item) {
        val dialog = Dialog(this)
        val container = FrameLayout(this).apply {
            setBackgroundColor(0xE6000000.toInt())
            setPadding(dp(12), dp(24), dp(12), dp(24))
            setOnClickListener { dialog.dismiss() }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = item.displayName
        }
        container.addView(image, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))
        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            loadSampled(storage.fileFor(item), 1800, image)
        }
        dialog.show()
    }

    private fun loadSampled(file: File, targetPx: Int, imageView: ImageView) {
        imageView.tag = file.absolutePath
        imageExecutor.execute {
            val bitmap = decodeSampled(file, targetPx)
            runOnUiThread {
                if (!isFinishing && imageView.tag == file.absolutePath) {
                    if (bitmap != null) imageView.setImageBitmap(bitmap)
                } else {
                    bitmap?.recycle()
                }
            }
        }
    }

    private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }
}
