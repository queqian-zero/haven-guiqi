package com.haven.guiqi

import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

/**
 * “我眼中的自己”独立页面。
 *
 * 顶部自由文本仍保存到原来的 haven_user / my_bio，保持既有语义与指令兼容；
 * 页面下方只新增用户亲自填写的动物、植物记录。
 */
class MySelfActivity : AppCompatActivity() {

    private val c get() = ThemeHelper.getColors(this)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private lateinit var inputMyBio: EditText
    private lateinit var animalContainer: LinearLayout
    private lateinit var plantContainer: LinearLayout
    private lateinit var storage: UserLifeStorage
    private var initialBio = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        setContentView(R.layout.activity_my_self)
        applyInsets()

        storage = UserLifeStorage(this)
        inputMyBio = findViewById(R.id.inputMyBio)
        animalContainer = findViewById(R.id.animalContainer)
        plantContainer = findViewById(R.id.plantContainer)

        initialBio = getSharedPreferences("haven_user", MODE_PRIVATE)
            .getString("my_bio", "")
            .orEmpty()
        inputMyBio.setText(initialBio)

        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            saveBio(showToast = false)
            finish()
        }
        findViewById<TextView>(R.id.btnSaveBio).setOnClickListener {
            saveBio(showToast = true)
        }
        findViewById<TextView>(R.id.btnAddAnimal).setOnClickListener {
            openEditor(UserLifeStorage.Kind.ANIMAL)
        }
        findViewById<TextView>(R.id.btnAddPlant).setOnClickListener {
            openEditor(UserLifeStorage.Kind.PLANT)
        }

        renderRecords()
    }

    override fun onResume() {
        super.onResume()
        renderRecords()
    }

    override fun onPause() {
        saveBio(showToast = false)
        super.onPause()
    }

    override fun onBackPressed() {
        saveBio(showToast = false)
        super.onBackPressed()
    }

    private fun applyInsets() {
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = !ThemeHelper.isDark(this)

    }

    private fun saveBio(showToast: Boolean) {
        val value = inputMyBio.text.toString().trim()
        if (value == initialBio) {
            if (showToast) Toast.makeText(this, "没有需要保存的变化", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("haven_user", MODE_PRIVATE)
            .edit()
            .putString("my_bio", value)
            .apply()
        initialBio = value
        if (showToast) Toast.makeText(this, "已保存 ♡", Toast.LENGTH_SHORT).show()
    }

    private fun openEditor(kind: UserLifeStorage.Kind, entryId: String? = null) {
        startActivity(Intent(this, CompanionEditActivity::class.java).apply {
            putExtra(CompanionEditActivity.EXTRA_KIND, kind.name)
            if (!entryId.isNullOrBlank()) putExtra(CompanionEditActivity.EXTRA_ENTRY_ID, entryId)
        })
    }

    private fun renderRecords() {
        if (!::storage.isInitialized) return
        renderKind(animalContainer, UserLifeStorage.Kind.ANIMAL)
        renderKind(plantContainer, UserLifeStorage.Kind.PLANT)
    }

    private fun renderKind(container: LinearLayout, kind: UserLifeStorage.Kind) {
        container.removeAllViews()
        val entries = storage.load(kind)
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (kind == UserLifeStorage.Kind.ANIMAL) {
                    "还没有动物伙伴记录。"
                } else {
                    "还没有植物伙伴记录。"
                }
                textSize = 12f
                setTextColor(c.tipText)
                setPadding(dp(4), dp(5), dp(4), dp(10))
            })
            return
        }

        entries.forEach { entry ->
            container.addView(createEntryCard(entry))
        }
    }

    private fun createEntryCard(entry: UserLifeStorage.Entry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.settings_item_bg)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
            setOnClickListener { openEditor(entry.kind, entry.id) }
        }

        val photoFile = entry.photoPath.takeIf { it.isNotBlank() }?.let(::File)
        if (photoFile?.isFile == true) {
            card.addView(SampledAvatarImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(62), dp(62)).apply {
                    marginEnd = dp(12)
                }
                setAvatarFile(
                    photoFile,
                    dp(62),
                    ChatAppearanceStorage.AvatarShape.SQUARE
                )
                setOnClickListener {
                    ImageHelper.showFullImage(this@MySelfActivity, photoFile.absolutePath)
                }
            })
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = entry.name.ifBlank { "未命名" }
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(c.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = storage.statusLabel(entry)
            textSize = 11f
            setTextColor(if (entry.status == UserLifeStorage.STATUS_CURRENT) c.accentStrong else c.textSecondary)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = getDrawable(R.drawable.icon_bg)
        })
        textColumn.addView(titleRow)

        val speciesText = listOf(entry.species, entry.breed)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (speciesText.isNotBlank()) {
            textColumn.addView(TextView(this).apply {
                text = speciesText
                textSize = 12f
                setTextColor(c.textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            })
        }

        val dateText = when {
            entry.startedDate.isNotBlank() && entry.endDate.isNotBlank() ->
                "${entry.startedDate} → ${entry.endDate}"
            entry.startedDate.isNotBlank() ->
                if (entry.kind == UserLifeStorage.Kind.ANIMAL) "从 ${entry.startedDate} 开始陪伴" else "从 ${entry.startedDate} 开始养护"
            entry.endDate.isNotBlank() -> entry.endDate
            else -> ""
        }
        if (dateText.isNotBlank()) {
            textColumn.addView(TextView(this).apply {
                text = dateText
                textSize = 11f
                setTextColor(c.tipText)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            })
        }

        val preview = entry.notes.ifBlank { entry.startedThought }
        if (preview.isNotBlank()) {
            textColumn.addView(TextView(this).apply {
                text = preview.take(72) + if (preview.length > 72) "…" else ""
                textSize = 11f
                maxLines = 2
                setTextColor(c.tipText)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            })
        }

        card.addView(textColumn)
        return card
    }
}
