package com.haven.guiqi

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

/** 单条动物 / 植物记录的独立编辑页。 */
class CompanionEditActivity : AppCompatActivity() {

    private val c get() = ThemeHelper.getColors(this)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private lateinit var storage: UserLifeStorage
    private lateinit var kind: UserLifeStorage.Kind
    private var entryId: String? = null
    private var originalEntry: UserLifeStorage.Entry? = null
    private var pendingPhotoPath = ""
    private var didSave = false

    private lateinit var photoPreview: SampledAvatarImageView
    private lateinit var inputName: EditText
    private lateinit var inputSpecies: EditText
    private lateinit var inputBreed: EditText
    private lateinit var inputStartedDate: EditText
    private lateinit var inputStartedThought: EditText
    private lateinit var inputNotes: EditText
    private lateinit var statusSpinner: Spinner
    private lateinit var endFieldsContainer: LinearLayout
    private lateinit var inputEndDate: EditText
    private lateinit var inputEndReason: EditText
    private lateinit var btnRemovePhoto: TextView

    private var statusOptions: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        setContentView(R.layout.activity_companion_edit)
        applyInsets()

        storage = UserLifeStorage(this)
        kind = runCatching {
            UserLifeStorage.Kind.valueOf(
                intent.getStringExtra(EXTRA_KIND) ?: UserLifeStorage.Kind.ANIMAL.name
            )
        }.getOrDefault(UserLifeStorage.Kind.ANIMAL)
        entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        originalEntry = entryId?.let(storage::get)
        if (originalEntry != null) kind = originalEntry!!.kind
        pendingPhotoPath = originalEntry?.photoPath.orEmpty()

        bindViews()
        configureLabels()
        configureSpinner()
        fillExisting()
        refreshPhoto()

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnSave).setOnClickListener { saveAndFinish() }
        findViewById<TextView>(R.id.btnChoosePhoto).setOnClickListener { choosePhoto() }
        btnRemovePhoto.setOnClickListener {
            val originalPath = originalEntry?.photoPath.orEmpty()
            if (pendingPhotoPath.isNotBlank() && pendingPhotoPath != originalPath) {
                try { File(pendingPhotoPath).delete() } catch (_: Exception) {}
            }
            pendingPhotoPath = ""
            refreshPhoto()
        }
        findViewById<TextView>(R.id.btnDelete).apply {
            visibility = if (originalEntry == null) View.GONE else View.VISIBLE
            setOnClickListener { confirmDelete() }
        }
    }

    override fun onDestroy() {
        if (!didSave) {
            val originalPath = originalEntry?.photoPath.orEmpty()
            if (pendingPhotoPath.isNotBlank() && pendingPhotoPath != originalPath) {
                try { File(pendingPhotoPath).delete() } catch (_: Exception) {}
            }
        }
        super.onDestroy()
    }

    private fun bindViews() {
        photoPreview = findViewById(R.id.photoPreview)
        inputName = findViewById(R.id.inputName)
        inputSpecies = findViewById(R.id.inputSpecies)
        inputBreed = findViewById(R.id.inputBreed)
        inputStartedDate = findViewById(R.id.inputStartedDate)
        inputStartedThought = findViewById(R.id.inputStartedThought)
        inputNotes = findViewById(R.id.inputNotes)
        statusSpinner = findViewById(R.id.statusSpinner)
        endFieldsContainer = findViewById(R.id.endFieldsContainer)
        inputEndDate = findViewById(R.id.inputEndDate)
        inputEndReason = findViewById(R.id.inputEndReason)
        btnRemovePhoto = findViewById(R.id.btnRemovePhoto)
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
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
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

    private fun configureLabels() {
        val animal = kind == UserLifeStorage.Kind.ANIMAL
        findViewById<TextView>(R.id.pageTitle).text = when {
            originalEntry != null && animal -> "编辑动物伙伴"
            originalEntry != null -> "编辑植物伙伴"
            animal -> "添加动物伙伴"
            else -> "添加植物伙伴"
        }
        inputSpecies.hint = if (animal) "动物种类，例如猫、狗、兔子" else "植物种类，例如多肉、绿萝、兰花"
        inputStartedDate.hint = if (animal) "开始陪伴日期" else "开始养护日期"
        inputStartedThought.hint = if (animal) {
            "它怎样来到你身边？当时为什么想养它？"
        } else {
            "它怎样来到你身边？当时为什么想养它？"
        }
        inputNotes.hint = if (animal) {
            "性格、习惯、喜欢与讨厌的东西，以及你想记下的故事"
        } else {
            "平时放在哪里、养护习惯，以及你想记下的故事"
        }
    }

    private fun configureSpinner() {
        statusOptions = storage.statusOptions(kind)
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            statusOptions.map { it.second }
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(c.textPrimary)
                    textSize = 14f
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(c.textPrimary)
                    setBackgroundColor(c.card)
                    textSize = 14f
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        statusSpinner.adapter = adapter
        statusSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateEndFields(statusOptions.getOrNull(position)?.first ?: UserLifeStorage.STATUS_CURRENT)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun fillExisting() {
        val entry = originalEntry ?: return
        inputName.setText(entry.name)
        inputSpecies.setText(entry.species)
        inputBreed.setText(entry.breed)
        inputStartedDate.setText(entry.startedDate)
        inputStartedThought.setText(entry.startedThought)
        inputNotes.setText(entry.notes)
        inputEndDate.setText(entry.endDate)
        inputEndReason.setText(entry.endReason)
        val statusIndex = statusOptions.indexOfFirst { it.first == entry.status }.coerceAtLeast(0)
        statusSpinner.setSelection(statusIndex)
    }

    private fun updateEndFields(status: String) {
        val show = status != UserLifeStorage.STATUS_CURRENT
        endFieldsContainer.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            inputEndDate.hint = storage.endDateLabel(kind, status)
            inputEndReason.hint = storage.endReasonLabel(kind, status)
        }
    }

    private fun choosePhoto() {
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, REQUEST_PHOTO)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val chooseButton = findViewById<TextView>(R.id.btnChoosePhoto)
        chooseButton.isEnabled = false
        chooseButton.text = "正在保存照片…"
        val outputDir = File(filesDir, "user_life_media").also { it.mkdirs() }
        Thread {
            val saved = ImageHelper.compressAndSave(this, uri, outputDir)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    saved?.let { try { File(it).delete() } catch (_: Exception) {} }
                    return@runOnUiThread
                }
                chooseButton.isEnabled = true
                chooseButton.text = "选择照片"
                if (saved == null) {
                    Toast.makeText(this, "照片保存失败", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val originalPath = originalEntry?.photoPath.orEmpty()
                if (pendingPhotoPath.isNotBlank() && pendingPhotoPath != originalPath) {
                    try { File(pendingPhotoPath).delete() } catch (_: Exception) {}
                }
                pendingPhotoPath = saved
                refreshPhoto()
            }
        }.start()
    }

    private fun refreshPhoto() {
        val file = pendingPhotoPath.takeIf { it.isNotBlank() }?.let(::File)
        val available = file?.isFile == true
        photoPreview.visibility = if (available) View.VISIBLE else View.INVISIBLE
        btnRemovePhoto.visibility = if (available) View.VISIBLE else View.GONE
        if (available) {
            photoPreview.setAvatarFile(
                file!!,
                dp(88),
                ChatAppearanceStorage.AvatarShape.SQUARE
            )
            photoPreview.setOnClickListener { ImageHelper.showFullImage(this, file.absolutePath) }
        }
    }

    private fun saveAndFinish() {
        val currentStatus = statusOptions.getOrNull(statusSpinner.selectedItemPosition)?.first
            ?: UserLifeStorage.STATUS_CURRENT
        val old = originalEntry
        val entry = UserLifeStorage.Entry(
            id = old?.id ?: java.util.UUID.randomUUID().toString(),
            kind = kind,
            name = inputName.text.toString().trim(),
            species = inputSpecies.text.toString().trim(),
            breed = inputBreed.text.toString().trim(),
            photoPath = pendingPhotoPath,
            startedDate = inputStartedDate.text.toString().trim(),
            startedThought = inputStartedThought.text.toString().trim(),
            notes = inputNotes.text.toString().trim(),
            status = currentStatus,
            endDate = if (currentStatus == UserLifeStorage.STATUS_CURRENT) "" else inputEndDate.text.toString().trim(),
            endReason = if (currentStatus == UserLifeStorage.STATUS_CURRENT) "" else inputEndReason.text.toString().trim(),
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        if (
            entry.name.isBlank() && entry.species.isBlank() && entry.breed.isBlank() &&
            entry.photoPath.isBlank() && entry.startedDate.isBlank() &&
            entry.startedThought.isBlank() && entry.notes.isBlank()
        ) {
            Toast.makeText(this, "至少写一点内容再保存", Toast.LENGTH_SHORT).show()
            return
        }

        storage.save(entry)
        val oldPath = old?.photoPath.orEmpty()
        if (oldPath.isNotBlank() && oldPath != pendingPhotoPath) {
            try { File(oldPath).delete() } catch (_: Exception) {}
        }
        didSave = true
        Toast.makeText(this, "已保存 ♡", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        val entry = originalEntry ?: return
        AlertDialog.Builder(this)
            .setTitle("删除这条记录？")
            .setMessage("删除后不会影响其他记录。")
            .setPositiveButton("删除") { _, _ ->
                val originalPath = entry.photoPath
                if (pendingPhotoPath.isNotBlank() && pendingPhotoPath != originalPath) {
                    try { File(pendingPhotoPath).delete() } catch (_: Exception) {}
                }
                storage.delete(entry.id)
                didSave = true
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ENTRY_ID = "entry_id"
        private const val REQUEST_PHOTO = 7301
    }
}
