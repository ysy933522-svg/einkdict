package com.example.myapplication

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.*
import org.opencv.android.OpenCVLoader
import java.io.File
import java.util.*
import kotlin.collections.ArrayList

class ImageViewerActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_DIRECTORY = 1001
        private val dirCache = mutableMapOf<String, Pair<List<File>, Long>>()
        private const val PREFS_NAME = "image_viewer_prefs"
        private const val KEY_LAST_DIR = "last_selected_dir"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_CONTRAST = "contrast"
        private const val KEY_SATURATION = "saturation"
        private const val KEY_CLIP_LIMIT = "clip_limit"
        private const val KEY_SHARPEN = "sharpen_strength"
    }

    private lateinit var prefs: SharedPreferences
    private var currentScale = 1.0f
    private val maxScale = 4.0f
    private val minScale = 0.5f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private val touchSlop = 5

    private lateinit var ivImage: ImageView
    private lateinit var btnFamiliar: Button
    private lateinit var btnStrange: Button
    private lateinit var btnSave: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnSwitchDir: Button
    private lateinit var btnReload: Button
    private lateinit var btnBack: Button
    private lateinit var btnSettings: Button
    private lateinit var layoutNumberButtons: LinearLayout

    private lateinit var dbHelper: DictDbHelper

    private var currentDirectoryPath: String = ""
    private var recursiveScan = false

    private var imageFiles = ArrayList<File>()
    private var currentIndex = 0

    private val scoreCache = mutableMapOf<String, Pair<Int, Int>>()
    private var hasUnsavedChanges = false

    // 图像滤镜参数（从 SharedPreferences 读取）
    private var brightness: Float = 0f
    private var contrast: Float = 1f
    private var saturation: Float = 1f
    private var clipLimit: Float = 3.0f
    private var sharpenStrength: Float = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        // 初始化 OpenCV（必须在任何 OpenCV 调用之前）
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "初始化失败")
        } else {
            Log.d("OpenCV", "初始化成功")
        }

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        ivImage = findViewById(R.id.iv_image)
        btnFamiliar = findViewById(R.id.btn_familiar)
        btnStrange = findViewById(R.id.btn_strange)
        btnSave = findViewById(R.id.btn_save)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnSwitchDir = findViewById(R.id.btn_switch_dir)
        btnReload = findViewById(R.id.btn_reload)
        btnBack = findViewById(R.id.btn_back)
        btnSettings = findViewById(R.id.btn_settings)
        layoutNumberButtons = findViewById(R.id.layout_number_buttons)

        dbHelper = (application as MyApplication).dbHelper

        // 统一使用同一个 SharedPreferences
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSettings()

        applyEinkStyle()

        Thread {
            waitForDbAndScan()
        }.start()

        btnFamiliar.setOnClickListener { onFamiliar() }
        btnStrange.setOnClickListener { onStrange() }
        btnSave.setOnClickListener { saveScores() }
        btnPrev.setOnClickListener { prevImage() }
        btnNext.setOnClickListener { nextImage() }
        btnSwitchDir.setOnClickListener { switchDirectory() }
        btnReload.setOnClickListener { reloadConfig() }
        btnBack.setOnClickListener { finish() }
        btnSettings.setOnClickListener { showSettingsDialog() }

        findViewById<Button>(R.id.btn_zoom_in).setOnClickListener { zoomIn() }
        findViewById<Button>(R.id.btn_zoom_out).setOnClickListener { zoomOut() }

        ivImage.isClickable = true
        ivImage.setOnTouchListener { _, event -> handleTouch(event) }
    }

    private fun loadSettings() {
        brightness = prefs.getFloat(KEY_BRIGHTNESS, 0f)
        contrast = prefs.getFloat(KEY_CONTRAST, 1f)
        saturation = prefs.getFloat(KEY_SATURATION, 1f)
        clipLimit = prefs.getFloat(KEY_CLIP_LIMIT, 3.0f)
        sharpenStrength = prefs.getFloat(KEY_SHARPEN, 0.5f)
    }

    private fun applyEinkStyle() {
        val buttons = listOf(
            btnFamiliar, btnStrange, btnSave, btnPrev, btnNext,
            btnSwitchDir, btnReload, btnBack, btnSettings,
            findViewById<Button>(R.id.btn_zoom_in),
            findViewById<Button>(R.id.btn_zoom_out)
        )
        for (btn in buttons) {
            btn.setTextColor(Color.BLACK)
            btn.setBackgroundColor(Color.WHITE)
            btn.elevation = 0f
            btn.stateListAnimator = null
        }
    }

    // ==================== 缩放与拖动 ====================

    private fun resetZoom() {
        currentScale = 1.0f
        ivImage.scaleType = ImageView.ScaleType.MATRIX
        applyFitCenterMatrix()
    }

    private fun applyFitCenterMatrix() {
        val drawable = ivImage.drawable ?: return
        val bmpW = drawable.intrinsicWidth.toFloat()
        val bmpH = drawable.intrinsicHeight.toFloat()
        val viewW = ivImage.width.toFloat()
        val viewH = ivImage.height.toFloat()
        if (bmpW <= 0 || bmpH <= 0 || viewW <= 0 || viewH <= 0) return
        val scale = minOf(viewW / bmpW, viewH / bmpH)
        val offsetX = (viewW - bmpW * scale) / 2f
        val offsetY = (viewH - bmpH * scale) / 2f
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(offsetX, offsetY)
        ivImage.imageMatrix = m
        ivImage.invalidate()
    }

    private fun applyZoom() {
        val drawable = ivImage.drawable ?: return
        val bmpW = drawable.intrinsicWidth.toFloat()
        val bmpH = drawable.intrinsicHeight.toFloat()
        val viewW = ivImage.width.toFloat()
        val viewH = ivImage.height.toFloat()
        if (bmpW <= 0 || bmpH <= 0 || viewW <= 0 || viewH <= 0) return

        val initScale = minOf(viewW / bmpW, viewH / bmpH)
        val initOffsetX = (viewW - bmpW * initScale) / 2f
        val initOffsetY = (viewH - bmpH * initScale) / 2f
        val m = Matrix()
        m.setScale(initScale, initScale)
        m.postTranslate(initOffsetX, initOffsetY)
        val cx = viewW / 2f
        val cy = viewH / 2f
        m.postScale(currentScale, currentScale, cx, cy)
        ivImage.imageMatrix = m
        ivImage.invalidate()
    }

    private fun zoomIn() {
        currentScale *= 1.25f
        if (currentScale > maxScale) currentScale = maxScale
        applyZoom()
    }

    private fun zoomOut() {
        currentScale /= 1.25f
        if (currentScale < minScale) currentScale = minScale
        applyZoom()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentScale <= 1.0f) return false
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                    isDragging = true
                    val m = Matrix(ivImage.imageMatrix)
                    m.postTranslate(dx, dy)
                    ivImage.imageMatrix = m
                    ivImage.invalidate()
                }
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                return true
            }
        }
        return false
    }

    // ==================== 初始化（记忆上次目录） ====================

    private fun waitForDbAndScan() {
        while (!dbHelper.isMemoryReady) {
            Thread.sleep(200)
        }
        runOnUiThread {
            val lastDir = prefs.getString(KEY_LAST_DIR, "")
            if (!lastDir.isNullOrEmpty()) {
                val dir = File(lastDir)
                if (dir.exists() && dir.isDirectory) {
                    currentDirectoryPath = lastDir
                    scanCurrentDirectory()
                    return@runOnUiThread
                }
            }
            switchDirectory()
        }
    }

    // ==================== 目录选择 ====================

    private fun switchDirectory() {
        if (hasUnsavedChanges) saveScores()
        val intent = Intent(this, DirectoryPickerActivity::class.java)
        startActivityForResult(intent, REQUEST_CODE_DIRECTORY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_DIRECTORY && resultCode == Activity.RESULT_OK) {
            val selectedPath = data?.getStringExtra(DirectoryPickerActivity.EXTRA_SELECTED_PATH)
            if (selectedPath != null) {
                currentDirectoryPath = selectedPath
                prefs.edit().putString(KEY_LAST_DIR, selectedPath).apply()
                dirCache.clear()
                scanCurrentDirectory()
            } else {
                Toast.makeText(this, "未选择目录", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 扫描目录 ====================

    private fun scanCurrentDirectory() {
        if (currentDirectoryPath.isEmpty()) {
            Toast.makeText(this, "尚未选择目录", Toast.LENGTH_SHORT).show()
            return
        }

        val dir = File(currentDirectoryPath)
        if (!dir.exists() || !dir.isDirectory) {
            Toast.makeText(this, "目录不存在: $currentDirectoryPath", Toast.LENGTH_SHORT).show()
            return
        }

        val cached = dirCache[currentDirectoryPath]
        val dirLastModified = dir.lastModified()
        if (cached != null && cached.second == dirLastModified) {
            imageFiles.clear()
            imageFiles.addAll(cached.first)
        } else {
            imageFiles.clear()
            scanDir(dir, recursiveScan)
            dirCache[currentDirectoryPath] = Pair(imageFiles.toList(), dirLastModified)
        }

        if (imageFiles.isNotEmpty()) {
            currentIndex = Random().nextInt(imageFiles.size)
            runOnUiThread {
                loadCurrentImage()
                generateNumberButtons()
            }
        } else {
            runOnUiThread {
                Toast.makeText(this, "该目录下没有图片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scanDir(dir: File, recursive: Boolean) {
        val files = dir.listFiles() ?: return
        val extensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        for (file in files) {
            if (file.isDirectory) {
                if (recursive) scanDir(file, true)
            } else {
                val ext = file.extension.lowercase(Locale.ROOT)
                if (ext in extensions) {
                    imageFiles.add(file)
                }
            }
        }
    }

    // ==================== 数字按钮 ====================

    private fun generateNumberButtons() {
        layoutNumberButtons.removeAllViews()
        if (imageFiles.isEmpty()) return

        val total = imageFiles.size
        val range = 4
        var start = currentIndex - range
        var end = currentIndex + range

        if (start < 0) {
            end -= start
            start = 0
        }
        if (end >= total) {
            start -= (end - total + 1)
            end = total - 1
            if (start < 0) start = 0
        }
        if (end >= total) end = total - 1

        for (i in start..end) {
            val btn = Button(this)
            btn.text = (i + 1).toString()
            btn.textSize = 13f
            btn.setTextColor(Color.BLACK)
            btn.setBackgroundColor(Color.WHITE)
            btn.elevation = 0f
            btn.stateListAnimator = null
            btn.setPadding(4, 0, 4, 0)

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            if (i > start) {
                params.marginStart = 4
            }
            btn.layoutParams = params

            val index = i
            btn.setOnClickListener {
                if (index < imageFiles.size) {
                    currentIndex = index
                    loadCurrentImage()
                    generateNumberButtons()
                }
            }
            layoutNumberButtons.addView(btn)
        }
    }

    // ==================== 图片加载（使用 OpenCV 增强） ====================

    private fun loadCurrentImage() {
        if (imageFiles.isEmpty()) return
        val file = imageFiles[currentIndex]
        if (!file.exists()) {
            Toast.makeText(this, "图片不存在", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap == null) {
                runOnUiThread { Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show() }
                return@Thread
            }

            // 从 SharedPreferences 读取最新的 CLAHE 和锐化参数
            val curClip = prefs.getFloat(KEY_CLIP_LIMIT, 3.0f).toDouble()
            val curSharpen = prefs.getFloat(KEY_SHARPEN, 0.5f).toDouble()
            val enhanced = DocImageProcessor.enhance(bitmap, curClip, curSharpen)
            bitmap.recycle()  // 释放原始位图

            runOnUiThread {
                ivImage.setImageBitmap(enhanced)
                currentScale = 1.0f
                ivImage.scaleType = ImageView.ScaleType.MATRIX

                if (ivImage.width > 0 && ivImage.height > 0) {
                    applyFitCenterMatrix()
                } else {
                    ivImage.viewTreeObserver.addOnGlobalLayoutListener(object :
                        android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            ivImage.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            applyFitCenterMatrix()
                        }
                    })
                }

                // 注意：不再调用 applyImageFilter()，避免双重处理
                // 所有图像增强已由 OpenCV 完成

                val filePath = file.absolutePath
                if (!scoreCache.containsKey(filePath)) {
                    val dbScore = dbHelper.getImageScore(filePath)
                    scoreCache[filePath] = dbScore ?: Pair(0, 0)
                }
            }
        }.start()
    }

    // ==================== 图像滤镜设置 ====================

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.setContentView(R.layout.dialog_image_settings)

        val etBrightness = dialog.findViewById<EditText>(R.id.et_brightness)
        val etContrast = dialog.findViewById<EditText>(R.id.et_contrast)
        val etSaturation = dialog.findViewById<EditText>(R.id.et_saturation)
        val etClip = dialog.findViewById<EditText>(R.id.et_clip_limit)
        val etSharpen = dialog.findViewById<EditText>(R.id.et_sharpen)
        val btnReset = dialog.findViewById<Button>(R.id.btn_reset)
        val btnOk = dialog.findViewById<Button>(R.id.btn_ok)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel)

        // 填充当前值
        etBrightness.setText(brightness.toString())
        etContrast.setText(contrast.toString())
        etSaturation.setText(saturation.toString())
        etClip.setText(clipLimit.toString())
        etSharpen.setText(sharpenStrength.toString())

        etBrightness.setHintTextColor(Color.GRAY)
        etContrast.setHintTextColor(Color.GRAY)
        etSaturation.setHintTextColor(Color.GRAY)
        etClip.setHintTextColor(Color.GRAY)
        etSharpen.setHintTextColor(Color.GRAY)

        for (btn in listOf(btnReset, btnOk, btnCancel)) {
            btn.setTextColor(Color.BLACK)
            btn.elevation = 0f
            btn.stateListAnimator = null
            btn.setBackgroundColor(Color.WHITE)
        }

        btnReset.setOnClickListener {
            etBrightness.setText("0")
            etContrast.setText("1.0")
            etSaturation.setText("1.0")
            etClip.setText("3.0")
            etSharpen.setText("0.5")
        }

        btnOk.setOnClickListener {
            try {
                val nb = etBrightness.text.toString().toFloatOrNull() ?: brightness
                val nc = etContrast.text.toString().toFloatOrNull() ?: contrast
                val ns = etSaturation.text.toString().toFloatOrNull() ?: saturation
                val nClip = etClip.text.toString().toFloatOrNull() ?: clipLimit
                val nSharpen = etSharpen.text.toString().toFloatOrNull() ?: sharpenStrength

                if (nb < -255f || nb > 255f) {
                    Toast.makeText(this, "亮度范围：-255~255", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (nc < 0.1f || nc > 3.0f) {
                    Toast.makeText(this, "对比度范围：0.1~3.0", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (ns < 0f || ns > 2.0f) {
                    Toast.makeText(this, "饱和度范围：0.0~2.0", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (nClip < 2.0f || nClip > 5.0f) {
                    Toast.makeText(this, "CLAHE范围：2.0~5.0", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (nSharpen < 0f || nSharpen > 1.0f) {
                    Toast.makeText(this, "锐化范围：0.0~1.0", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                brightness = nb
                contrast = nc
                saturation = ns
                clipLimit = nClip
                sharpenStrength = nSharpen

                prefs.edit().apply {
                    putFloat(KEY_BRIGHTNESS, brightness)
                    putFloat(KEY_CONTRAST, contrast)
                    putFloat(KEY_SATURATION, saturation)
                    putFloat(KEY_CLIP_LIMIT, clipLimit)
                    putFloat(KEY_SHARPEN, sharpenStrength)
                    apply()
                }

                // 重新加载当前页以应用新设置
                loadCurrentImage()
                dialog.dismiss()
            } catch (e: Exception) {
                Toast.makeText(this, "输入无效，请检查", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ==================== 操作 ====================

    private fun onFamiliar() {
        if (imageFiles.isEmpty()) return
        val file = imageFiles[currentIndex]
        val filePath = file.absolutePath
        val old = scoreCache[filePath] ?: Pair(0, 0)
        scoreCache[filePath] = Pair(old.first + 1, old.second)
        hasUnsavedChanges = true
        nextImage()
    }

    private fun onStrange() {
        if (imageFiles.isEmpty()) return
        val file = imageFiles[currentIndex]
        val filePath = file.absolutePath
        val old = scoreCache[filePath] ?: Pair(0, 0)
        scoreCache[filePath] = Pair(old.first, old.second + 1)
        hasUnsavedChanges = true
        nextImage()
    }

    private fun prevImage() {
        if (imageFiles.isEmpty()) return
        currentIndex = if (currentIndex > 0) currentIndex - 1 else imageFiles.size - 1
        loadCurrentImage()
        generateNumberButtons()
    }

    private fun nextImage() {
        if (imageFiles.isEmpty()) return
        currentIndex = if (currentIndex < imageFiles.size - 1) currentIndex + 1 else 0
        loadCurrentImage()
        generateNumberButtons()
    }

    private fun reloadConfig() {
        if (hasUnsavedChanges) saveScores()
        dirCache.clear()
        switchDirectory()
    }

    private fun saveScores() {
        if (!hasUnsavedChanges) {
            Toast.makeText(this, "没有需要保存的数据", Toast.LENGTH_SHORT).show()
            return
        }
        val updates = mutableMapOf<String, Pair<Int, Int>>()
        for ((hash, score) in scoreCache) {
            if (score.first > 0 || score.second > 0) {
                updates[hash] = score
            }
        }
        if (updates.isNotEmpty()) {
            dbHelper.batchUpdateImageScores(updates)
        }
        hasUnsavedChanges = false
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        if (hasUnsavedChanges) saveScores()
    }

    override fun onBackPressed() {
        if (hasUnsavedChanges) saveScores()
        super.onBackPressed()
    }
}