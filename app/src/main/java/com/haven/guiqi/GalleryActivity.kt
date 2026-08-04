package com.haven.guiqi

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.util.LruCache
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var grid: GridView
    private lateinit var galleryAdapter: GalleryAdapter
    private lateinit var emptyView: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var countText: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView

    private val selectedItemIds = linkedSetOf<String>()
    private var currentCategory: GalleryStorage.Category? = null
    private var currentAlbumId: String? = null
    private var showUnclassifiedOnly = false

    private var pendingImportCategory = GalleryStorage.Category.GENERAL
    private var pendingImportAlbumId: String? = null
    private var skipInitialResumeRefresh = true
    private var galleryTotalCount = 0
    private var galleryRenderVersion = 0
    private val metadataExecutor = Executors.newSingleThreadExecutor()
    private val imageExecutor = Executors.newFixedThreadPool(2)
    private val thumbnailCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 16L)
            .toInt()
            .coerceIn(8 * 1024, 32 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult

        // 捕获本次选择的目标，避免导入期间再次操作把图片放进另一组。
        val importCategory = pendingImportCategory
        val importAlbumId = pendingImportAlbumId
        Toast.makeText(this, "正在放进画匣：${uris.size} 张…", Toast.LENGTH_SHORT).show()

        imageExecutor.execute {
            var success = 0
            val failures = mutableListOf<String>()
            uris.forEach { uri ->
                try {
                    storage.importImage(uri, importCategory, importAlbumId)
                    success++
                } catch (e: Exception) {
                    failures += (e.message ?: "未知错误")
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = when {
                    failures.isEmpty() -> "已放进画匣：$success 张"
                    success > 0 -> "放入 $success 张，${failures.size} 张失败"
                    else -> "导入失败：${failures.firstOrNull() ?: "无法读取图片"}"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

                clearSelection(refreshGallery = false)
                currentCategory = importCategory
                currentAlbumId = importAlbumId
                showUnclassifiedOnly = importAlbumId == null
                renderFilters()
                renderAlbumFilters()
                renderGallery()
            }
        }
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
        if (skipInitialResumeRefresh) {
            skipInitialResumeRefresh = false
            return
        }
        if (::grid.isInitialized) {
            renderAlbumFilters()
            renderGallery()
        }
    }

    override fun onDestroy() {
        galleryRenderVersion++
        if (::grid.isInitialized) grid.adapter = null
        thumbnailCache.evictAll()
        metadataExecutor.shutdownNow()
        imageExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectedItemIds.isNotEmpty()) clearSelection() else super.onBackPressed()
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
                setOnClickListener {
                    if (selectedItemIds.isNotEmpty()) clearSelection() else finish()
                }
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

        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = rounded(c.card, 14, c.border)
            setPadding(dp(14), dp(8), dp(8), dp(8))

            selectionCount = TextView(this@GalleryActivity).apply {
                textSize = 12f
                setTextColor(c.textPrimary)
            }
            addView(selectionCount, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ))
            addView(makeSelectionAction("移动", c.accentStrong) { moveSelectedItems() })
            addView(makeSelectionAction("删除", c.errorText) { deleteSelectedItems() })
            addView(makeSelectionAction("取消", c.textSecondary) { clearSelection() })
        }
        root.addView(selectionBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(14), 0, dp(14), dp(10))
        })

        val contentFrame = FrameLayout(this)
        root.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        galleryAdapter = GalleryAdapter()
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(14), dp(4), dp(14), dp(28))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            selector = ColorDrawable(Color.TRANSPARENT)
            adapter = galleryAdapter
        }
        contentFrame.addView(grid, FrameLayout.LayoutParams(
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
            "头像框" to GalleryStorage.Category.AVATAR_FRAME,
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
                clearSelection(refreshGallery = false)
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
            clearSelection(refreshGallery = false)
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
            clearSelection(refreshGallery = false)
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
                clearSelection(refreshGallery = false)
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
            clearSelection(refreshGallery = false)
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

    private fun makeSelectionAction(
        label: String,
        color: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(color)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun updateSelectionBar() {
        if (!::selectionBar.isInitialized) return
        val count = selectedItemIds.size
        selectionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) selectionCount.text = "已选 $count 张"
    }

    private fun toggleSelection(itemId: String) {
        if (!selectedItemIds.add(itemId)) selectedItemIds.remove(itemId)
        updateSelectionBar()
        if (::galleryAdapter.isInitialized) galleryAdapter.notifyDataSetChanged()
    }

    private fun clearSelection(refreshGallery: Boolean = true) {
        if (selectedItemIds.isEmpty()) {
            updateSelectionBar()
            return
        }
        selectedItemIds.clear()
        updateSelectionBar()
        if (refreshGallery && ::galleryAdapter.isInitialized) galleryAdapter.notifyDataSetChanged()
    }

    private fun moveSelectedItems() {
        val ids = selectedItemIds.toList()
        if (ids.isEmpty()) return
        val categories = GalleryStorage.Category.values().asList().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("把 ${ids.size} 张图片移动到哪一类")
            .setItems(categories.map { it.label }.toTypedArray()) { _, which ->
                val targetCategory = categories[which]
                chooseAlbumDestination(
                    category = targetCategory,
                    title = "放进 ${targetCategory.label} 的哪一组",
                    allowCreate = true
                ) { targetAlbumId ->
                    val moved = storage.moveMany(ids, targetCategory, targetAlbumId)
                    clearSelection(refreshGallery = false)
                    renderAlbumFilters()
                    renderGallery()
                    Toast.makeText(this, "已移动 $moved 张", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedItems() {
        val ids = selectedItemIds.toList()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("从画匣删除 ${ids.size} 张图片？")
            .setMessage("只会删除画匣里的副本，不会动你手机相册中的原图。")
            .setPositiveButton("删除") { _, _ ->
                val deleted = storage.deleteMany(ids)
                clearSelection(refreshGallery = false)
                renderGallery()
                Toast.makeText(this, "已删除 $deleted 张", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderGallery() {
        val version = ++galleryRenderVersion
        val category = currentCategory
        val albumId = currentAlbumId
        val unclassifiedOnly = showUnclassifiedOnly
        if (::countText.isInitialized && galleryTotalCount == 0) {
            countText.text = "全屋共用 · 整理中…"
        }

        metadataExecutor.execute {
            val allItems = storage.listAll()
            val categoryItems = if (category == null) {
                allItems
            } else {
                allItems.filter { it.category == category }
            }
            val items = when {
                category == null -> categoryItems
                unclassifiedOnly -> categoryItems.filter { it.albumId == null }
                albumId != null -> categoryItems.filter { it.albumId == albumId }
                else -> categoryItems
            }

            runOnUiThread {
                if (isFinishing || isDestroyed || version != galleryRenderVersion) {
                    return@runOnUiThread
                }

                galleryTotalCount = allItems.size
                selectedItemIds.retainAll(allItems.map { it.id }.toHashSet())
                updateSelectionBar()
                countText.text = if (galleryTotalCount == 0) {
                    "全屋共用 · 还没有图片"
                } else {
                    "全屋共用 · $galleryTotalCount 张图片"
                }

                val isEmpty = items.isEmpty()
                emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
                grid.visibility = if (isEmpty) View.GONE else View.VISIBLE
                galleryAdapter.submit(items)
                if (isEmpty) updateEmptyCopy()
            }
        }
    }

    private fun updateEmptyCopy() {
        when {
            galleryTotalCount == 0 -> {
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
                emptyHint.text = "上传图片，或长按图片进入多选后移动进来。"
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
            loadPreview(storage.fileFor(item), 1800, image)
        }
        dialog.show()
    }

    private fun loadThumbnail(file: File, targetPx: Int, imageView: ImageView, key: String) {
        imageView.tag = key
        val cached = thumbnailCache.get(key)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageDrawable(null)
        imageExecutor.execute {
            if (Thread.currentThread().isInterrupted) return@execute
            val bitmap = decodeSquareThumbnail(file, targetPx)
            if (bitmap != null) thumbnailCache.put(key, bitmap)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && imageView.tag == key) {
                    if (bitmap != null && !bitmap.isRecycled) imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun loadPreview(file: File, targetPx: Int, imageView: ImageView) {
        val key = "preview:${file.absolutePath}:${file.lastModified()}"
        imageView.tag = key
        imageExecutor.execute {
            val bitmap = decodeSampledForPreview(file, targetPx)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && imageView.tag == key) {
                    if (bitmap != null) imageView.setImageBitmap(bitmap)
                } else {
                    bitmap?.recycle()
                }
            }
        }
    }

    private fun decodeSquareThumbnail(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        while (shortSide / (sample * 2) >= targetPx) sample *= 2

        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null

        return try {
            ThumbnailUtils.extractThumbnail(
                decoded,
                targetPx,
                targetPx,
                ThumbnailUtils.OPTIONS_RECYCLE_INPUT
            )
        } catch (_: Exception) {
            if (!decoded.isRecycled) decoded.recycle()
            null
        }
    }

    private fun decodeSampledForPreview(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private inner class GalleryAdapter : BaseAdapter() {
        private var items: List<GalleryStorage.Item> = emptyList()
        private val sidePx: Int
            get() = ((resources.displayMetrics.widthPixels - dp(28) - dp(16)) / 3f).toInt()

        fun submit(newItems: List<GalleryStorage.Item>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): GalleryStorage.Item = items[position]
        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: GalleryCellHolder
            val cell: FrameLayout
            if (convertView is FrameLayout && convertView.tag is GalleryCellHolder) {
                cell = convertView
                holder = convertView.tag as GalleryCellHolder
            } else {
                val image = ImageView(this@GalleryActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                }
                val tint = View(this@GalleryActivity).apply {
                    visibility = View.GONE
                }
                val check = TextView(this@GalleryActivity).apply {
                    text = "✓"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(c.textOnAccent)
                    background = rounded(c.accentStrong, 12)
                    visibility = View.GONE
                }
                cell = FrameLayout(this@GalleryActivity).apply {
                    addView(image, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                    addView(tint, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                    addView(check, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(7)
                        marginEnd = dp(7)
                    })
                }
                holder = GalleryCellHolder(image, tint, check)
                cell.tag = holder
            }

            val side = sidePx
            cell.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, side)
            val item = getItem(position)
            val selected = item.id in selectedItemIds
            cell.contentDescription = item.displayName
            cell.setOnClickListener {
                if (selectedItemIds.isNotEmpty()) toggleSelection(item.id) else showPreview(item)
            }
            cell.setOnLongClickListener {
                toggleSelection(item.id)
                true
            }

            holder.image.alpha = if (selected) 0.68f else 1f
            holder.image.background = rounded(c.card, 12, if (selected) c.accentStrong else c.border)
            holder.tint.visibility = if (selected) View.VISIBLE else View.GONE
            holder.tint.background = rounded(
                Color.argb(
                    42,
                    Color.red(c.accentStrong),
                    Color.green(c.accentStrong),
                    Color.blue(c.accentStrong)
                ),
                12
            )
            holder.check.visibility = if (selected) View.VISIBLE else View.GONE

            val file = storage.fileFor(item)
            val key = "${file.absolutePath}:${file.lastModified()}:$side"
            loadThumbnail(file, side, holder.image, key)
            return cell
        }
    }

    private class GalleryCellHolder(
        val image: ImageView,
        val tint: View,
        val check: TextView
    )

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }
}
