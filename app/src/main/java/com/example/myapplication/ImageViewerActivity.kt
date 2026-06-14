package com.example.myapplication

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
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
        private const val KEY_SHARPEN = "sharpen"
        private const val KEY_GAMMA = "gamma"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_DENOISE = "denoise"
        private const val KEY_COLOR_TEMP = "color_temp"
        private const val KEY_PRINT_CLEAN = "print_clean_strength"
    }

    // 图像参数
    private var brightness = 0f
    private var contrast = 1f
    private var saturation = 1f
    private var clipLimit = 3.0f
    private var sharpenStrength = 0.5f
    private var gamma = 1.0f
    private var threshold = 0
    private var denoise = 0f
    private var colorTemp = 0
    private var printCleanStrength = 0f

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
    private lateinit var btnFavorite: Button
    private lateinit var layoutNumberButtons: LinearLayout

    private lateinit var dbHelper: DictDbHelper

    private var currentDirectoryPath = ""
    private var recursiveScan = false

    private var imageFiles = ArrayList<File>()
    private var currentIndex = 0

    // 收藏相关
    private var favoriteFiles = mutableListOf<File>()
    private var isFavoriteMode = false
    private val pendingFavorites = mutableSetOf<String>()  // 待入库的收藏路径

    private val scoreCache = mutableMapOf<String, Pair<Int, Int>>()
    private var hasUnsavedChanges = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        // 初始化 OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "初始化失败")
        } else {
            Log.d("OpenCV", "初始化成功")
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

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
        btnFavorite = findViewById(R.id.btn_favorite)
        layoutNumberButtons = findViewById(R.id.layout_number_buttons)

        dbHelper = (application as MyApplication).dbHelper

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSettings()

        applyEinkStyle()

        // 加载收藏列表
        loadFavoritesFromDb()

        // 如果收藏列表不为空，直接进入收藏模式
        if (favoriteFiles.isNotEmpty()) {
            isFavoriteMode = true
            currentIndex = 0
            loadCurrentImage()
            generateNumberButtons()
        } else {
            // 否则等待数据库就绪后扫描目录
            Thread {
                waitForDbAndScan()
            }.start()
        }

        btnFamiliar.setOnClickListener { onFamiliar() }
        btnStrange.setOnClickListener { onStrange() }
        btnSave.setOnClickListener { saveScores() }
        btnPrev.setOnClickListener { prevImage() }
        btnNext.setOnClickListener { nextImage() }
        btnSwitchDir.setOnClickListener { switchDirectory() }
        btnReload.setOnClickListener { reloadConfig() }
        btnBack.setOnClickListener { finish() }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnFavorite.setOnClickListener { toggleFavorite() }

        findViewById<Button>(R.id.btn_zoom_in).setOnClickListener { zoomIn() }
        findViewById<Button>(R.id.btn_zoom_out).setOnClickListener { zoomOut() }

        ivImage.isClickable = true
        ivImage.setOnTouchListener { _, event -> handleTouch(event) }


        // 在已有的 findViewById 之后添加
        val btnMore = findViewById<Button>(R.id.btn_more)
        val extraButtonBar = findViewById<LinearLayout>(R.id.extra_button_bar)

        btnMore.setOnClickListener {
            val isVisible = extraButtonBar.visibility == View.VISIBLE
            extraButtonBar.visibility = if (isVisible) View.GONE else View.VISIBLE
            btnMore.text = if (isVisible) "更多" else "收起"
        }

    }

    private fun loadSettings() {
        brightness = prefs.getFloat(KEY_BRIGHTNESS, 0f)
        contrast = prefs.getFloat(KEY_CONTRAST, 1f)
        saturation = prefs.getFloat(KEY_SATURATION, 1f)
        clipLimit = prefs.getFloat(KEY_CLIP_LIMIT, 3.0f)
        sharpenStrength = prefs.getFloat(KEY_SHARPEN, 0.5f)
        gamma = prefs.getFloat(KEY_GAMMA, 1.0f)
        threshold = prefs.getInt(KEY_THRESHOLD, 0)
        denoise = prefs.getFloat(KEY_DENOISE, 0f)
        colorTemp = prefs.getInt(KEY_COLOR_TEMP, 0)
        printCleanStrength = prefs.getFloat(KEY_PRINT_CLEAN, 0f)
    }

    private fun applyEinkStyle() {
        // 主按钮行
        val mainButtons = listOf(
            btnFamiliar, btnStrange, btnSave, btnPrev, btnNext,
            btnSwitchDir, btnReload, btnBack, btnSettings, btnFavorite,
            findViewById<Button>(R.id.btn_zoom_in),
            findViewById<Button>(R.id.btn_zoom_out),
            findViewById<Button>(R.id.btn_more)  // 新增
        )
        for (btn in mainButtons) {
            btn.setTextColor(Color.BLACK)
            btn.setBackgroundColor(Color.WHITE)
            btn.elevation = 0f
            btn.stateListAnimator = null
        }

        // 额外按钮行中的所有按钮
        val extraBar = findViewById<LinearLayout>(R.id.extra_button_bar)
        for (i in 0 until extraBar.childCount) {
            val child = extraBar.getChildAt(i)
            if (child is Button) {
                child.setTextColor(Color.BLACK)
                child.setBackgroundColor(Color.WHITE)
                child.elevation = 0f
                child.stateListAnimator = null
            }
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

    // ==================== 收藏相关 ====================

    private fun loadFavoritesFromDb() {
        val paths = dbHelper.getAllFavoritePaths()
        favoriteFiles.clear()
        for (path in paths) {
            val file = File(path)
            if (file.exists()) {
                favoriteFiles.add(file)
            } else {
                dbHelper.deleteFavoriteByPath(path)
            }
        }
    }

    private fun toggleFavorite() {
        val currentFile = getCurrentFile() ?: return
        val path = currentFile.absolutePath

        if (pendingFavorites.contains(path)) {
            pendingFavorites.remove(path)
            btnFavorite.text = "收藏"
            ToastUtil.show(this, "已取消收藏")
        } else {
            pendingFavorites.add(path)
            btnFavorite.text = "取消收藏"
            ToastUtil.show(this, "已标记收藏（保存后生效）")
        }
    }

    private fun updateFavoriteButton() {
        val currentFile = getCurrentFile() ?: return
        val path = currentFile.absolutePath
        val isPending = pendingFavorites.contains(path)
        val isDbFav = dbHelper.isFavorite(path)
        btnFavorite.text = if (isPending || isDbFav) "取消收藏" else "收藏"
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
                ToastUtil.show(this, "未选择目录")
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
            ToastUtil.show(this, "目录不存在: $currentDirectoryPath")
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
                isFavoriteMode = false  // 切换到普通模式
                loadCurrentImage()
                generateNumberButtons()
            }
        } else {
            runOnUiThread {
                ToastUtil.show(this, "该目录下没有图片")
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
        val sourceList = if (isFavoriteMode) favoriteFiles else imageFiles
        if (sourceList.isEmpty()) return

        val total = sourceList.size
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
                if (index < sourceList.size) {
                    currentIndex = index
                    loadCurrentImage()
                    generateNumberButtons()
                }
            }
            layoutNumberButtons.addView(btn)
        }
    }

    // ==================== 图片加载（使用 OpenCV 增强） ====================

    private var loadImageJob: Thread? = null

    private fun loadCurrentImage(showToast: Boolean = false) {
        val sourceList = if (isFavoriteMode) favoriteFiles else imageFiles
        if (sourceList.isEmpty()) {
            if (isFavoriteMode) {
                // 收藏列表为空，回退到普通模式
                isFavoriteMode = false
                loadCurrentImage(showToast)
                return
            }
            return
        }
        if (currentIndex < 0 || currentIndex >= sourceList.size) {
            currentIndex = 0
        }
        val file = sourceList[currentIndex]
        if (!file.exists()) {
            ToastUtil.show(this, "图片不存在")
            return
        }

        loadImageJob?.interrupt()
        loadImageJob = Thread {
            if (Thread.interrupted()) return@Thread

            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap == null || Thread.interrupted()) {
                runOnUiThread { 
                    ToastUtil.show(this, "无法加载图片")
                }
                return@Thread
            }

            val curClip = prefs.getFloat(KEY_CLIP_LIMIT, 3.0f)
            val curSharpen = prefs.getFloat(KEY_SHARPEN, 0.5f)
            val enhanced = DocImageProcessor.enhance(
                bitmap,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                clipLimit = clipLimit,
                sharpenStrength = sharpenStrength,
                gamma = gamma,
                threshold = threshold,
                denoise = denoise,
                colorTemp = colorTemp,
                printCleanStrength = printCleanStrength
            )

            if (Thread.interrupted()) return@Thread

            runOnUiThread {
                val oldDrawable = ivImage.drawable
                ivImage.setImageBitmap(enhanced)

                if (showToast) {
                    ToastUtil.show(this, "已应用")
                }

                if (oldDrawable is BitmapDrawable) {
                    val oldBitmap = oldDrawable.bitmap
                    if (oldBitmap != null && !oldBitmap.isRecycled && oldBitmap !== enhanced) {
                        oldBitmap.recycle()
                    }
                }
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }

                currentScale = 1.0f
                ivImage.scaleType = ImageView.ScaleType.MATRIX
                if (ivImage.width > 0 && ivImage.height > 0) {
                    applyFitCenterMatrix()
                } else {
                    ivImage.viewTreeObserver.addOnGlobalLayoutListener(object :
                        ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            ivImage.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            applyFitCenterMatrix()
                        }
                    })
                }

                val filePath = file.absolutePath
                if (!scoreCache.containsKey(filePath)) {
                    val dbScore = dbHelper.getImageScore(filePath)
                    scoreCache[filePath] = dbScore ?: Pair(0, 0)
                }

                // 更新收藏按钮状态
                updateFavoriteButton()
            }
        }.also { it.start() }
    }

    // ==================== 图像滤镜设置 ====================

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.setContentView(R.layout.dialog_image_settings)

        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.95).toInt()
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.TOP)
        dialog.window?.attributes?.y = 0
        dialog.window?.attributes?.x = 0

        val sbBrightness = dialog.findViewById<SeekBar>(R.id.sb_brightness)
        val sbContrast = dialog.findViewById<SeekBar>(R.id.sb_contrast)
        val sbSaturation = dialog.findViewById<SeekBar>(R.id.sb_saturation)
        val sbClipLimit = dialog.findViewById<SeekBar>(R.id.sb_clip_limit)
        val sbSharpen = dialog.findViewById<SeekBar>(R.id.sb_sharpen)
        val sbGamma = dialog.findViewById<SeekBar>(R.id.sb_gamma)
        val sbThreshold = dialog.findViewById<SeekBar>(R.id.sb_threshold)
        val sbDenoise = dialog.findViewById<SeekBar>(R.id.sb_denoise)
        val sbTemp = dialog.findViewById<SeekBar>(R.id.sb_temp)
        val sbPrintClean = dialog.findViewById<SeekBar>(R.id.sb_print_clean)

        val tvBrightnessValue = dialog.findViewById<TextView>(R.id.tv_brightness_value)
        val tvContrastValue = dialog.findViewById<TextView>(R.id.tv_contrast_value)
        val tvSaturationValue = dialog.findViewById<TextView>(R.id.tv_saturation_value)
        val tvClipLimitValue = dialog.findViewById<TextView>(R.id.tv_clip_limit_value)
        val tvSharpenValue = dialog.findViewById<TextView>(R.id.tv_sharpen_value)
        val tvGammaValue = dialog.findViewById<TextView>(R.id.tv_gamma_value)
        val tvThresholdValue = dialog.findViewById<TextView>(R.id.tv_threshold_value)
        val tvDenoiseValue = dialog.findViewById<TextView>(R.id.tv_denoise_value)
        val tvTempValue = dialog.findViewById<TextView>(R.id.tv_temp_value)
        val tvPrintCleanValue = dialog.findViewById<TextView>(R.id.tv_print_clean_value)

        val btnReset = dialog.findViewById<Button>(R.id.btn_reset)
        val btnApply = dialog.findViewById<Button>(R.id.btn_apply)
        val btnOk = dialog.findViewById<Button>(R.id.btn_ok)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel)

        // 设置当前值
        sbBrightness.progress = (brightness + 255f).toInt().coerceIn(0, 510)
        tvBrightnessValue.text = brightness.toInt().toString()

        sbContrast.progress = (contrast * 100f).toInt().coerceIn(0, 1000)
        tvContrastValue.text = String.format("%.1f", contrast)

        sbSaturation.progress = (saturation * 100f).toInt().coerceIn(0, 500)
        tvSaturationValue.text = String.format("%.1f", saturation)

        sbClipLimit.progress = (clipLimit * 100f).toInt().coerceIn(0, 2000)
        tvClipLimitValue.text = String.format("%.1f", clipLimit)

        sbSharpen.progress = (sharpenStrength * 100f).toInt().coerceIn(0, 500)
        tvSharpenValue.text = String.format("%.1f", sharpenStrength)

        sbGamma.progress = ((gamma - 0.1f) * 100f).toInt().coerceIn(0, 490)
        tvGammaValue.text = String.format("%.1f", gamma)

        sbThreshold.progress = threshold.coerceIn(0, 255)
        tvThresholdValue.text = if (threshold == 0) "关" else threshold.toString()

        sbDenoise.progress = (denoise * 10f).toInt().coerceIn(0, 500)
        tvDenoiseValue.text = String.format("%.0f", denoise)

        sbTemp.progress = (colorTemp + 200).coerceIn(0, 400)
        tvTempValue.text = colorTemp.toString()

        sbPrintClean.progress = (printCleanStrength * 100f).toInt().coerceIn(0, 100)
        tvPrintCleanValue.text = String.format("%.0f", printCleanStrength * 100)

        // 监听器
        sbBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvBrightnessValue.text = (p - 255).toString()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvContrastValue.text = String.format("%.1f", p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbSaturation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvSaturationValue.text = String.format("%.1f", p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbClipLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvClipLimitValue.text = String.format("%.1f", p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbSharpen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvSharpenValue.text = String.format("%.1f", p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbGamma.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                val v = 0.1f + p / 100f
                tvGammaValue.text = String.format("%.1f", v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvThresholdValue.text = if (p == 0) "关" else p.toString()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbDenoise.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvDenoiseValue.text = String.format("%.0f", p / 10f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbTemp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvTempValue.text = (p - 200).toString()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbPrintClean.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvPrintCleanValue.text = p.toString()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // 按钮样式
        for (btn in listOf(btnReset, btnApply, btnOk, btnCancel)) {
            btn.setTextColor(Color.BLACK)
            btn.elevation = 0f
            btn.stateListAnimator = null
            btn.setBackgroundColor(Color.WHITE)
        }

        btnReset.setOnClickListener {
            sbBrightness.progress = 255
            sbContrast.progress = 100
            sbSaturation.progress = 100
            sbClipLimit.progress = 300
            sbSharpen.progress = 50
            sbGamma.progress = 90
            sbThreshold.progress = 0
            sbDenoise.progress = 0
            sbTemp.progress = 200
            sbPrintClean.progress = 0

            tvBrightnessValue.text = "0"
            tvContrastValue.text = "1.0"
            tvSaturationValue.text = "1.0"
            tvClipLimitValue.text = "3.0"
            tvSharpenValue.text = "0.5"
            tvGammaValue.text = "1.0"
            tvThresholdValue.text = "关"
            tvDenoiseValue.text = "0"
            tvTempValue.text = "0"
            tvPrintCleanValue.text = "0"

            brightness = 0f
            contrast = 1f
            saturation = 1f
            clipLimit = 3.0f
            sharpenStrength = 0.5f
            gamma = 1.0f
            threshold = 0
            denoise = 0f
            colorTemp = 0
            printCleanStrength = 0f

            prefs.edit().apply {
                putFloat(KEY_BRIGHTNESS, brightness)
                putFloat(KEY_CONTRAST, contrast)
                putFloat(KEY_SATURATION, saturation)
                putFloat(KEY_CLIP_LIMIT, clipLimit)
                putFloat(KEY_SHARPEN, sharpenStrength)
                putFloat(KEY_GAMMA, gamma)
                putInt(KEY_THRESHOLD, threshold)
                putFloat(KEY_DENOISE, denoise)
                putInt(KEY_COLOR_TEMP, colorTemp)
                putFloat(KEY_PRINT_CLEAN, printCleanStrength)
                apply()
            }

            loadCurrentImage()
            ToastUtil.show(this, "已还原为默认值")
        }

        btnApply.setOnClickListener {
            readValuesAndSave(dialog)
            loadCurrentImage(showToast = true)
        }

        btnOk.setOnClickListener {
            readValuesAndSave(dialog)
            loadCurrentImage(showToast = true)
            dialog.dismiss()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun readValuesAndSave(dialog: Dialog) {
        brightness = (dialog.findViewById<SeekBar>(R.id.sb_brightness).progress - 255).toFloat()
        contrast = dialog.findViewById<SeekBar>(R.id.sb_contrast).progress / 100f
        saturation = dialog.findViewById<SeekBar>(R.id.sb_saturation).progress / 100f
        clipLimit = dialog.findViewById<SeekBar>(R.id.sb_clip_limit).progress / 100f
        sharpenStrength = dialog.findViewById<SeekBar>(R.id.sb_sharpen).progress / 100f
        gamma = 0.1f + dialog.findViewById<SeekBar>(R.id.sb_gamma).progress / 100f
        threshold = dialog.findViewById<SeekBar>(R.id.sb_threshold).progress
        denoise = dialog.findViewById<SeekBar>(R.id.sb_denoise).progress / 10f
        colorTemp = dialog.findViewById<SeekBar>(R.id.sb_temp).progress - 200
        printCleanStrength = dialog.findViewById<SeekBar>(R.id.sb_print_clean).progress / 100f

        prefs.edit().apply {
            putFloat(KEY_BRIGHTNESS, brightness)
            putFloat(KEY_CONTRAST, contrast)
            putFloat(KEY_SATURATION, saturation)
            putFloat(KEY_CLIP_LIMIT, clipLimit)
            putFloat(KEY_SHARPEN, sharpenStrength)
            putFloat(KEY_GAMMA, gamma)
            putInt(KEY_THRESHOLD, threshold)
            putFloat(KEY_DENOISE, denoise)
            putInt(KEY_COLOR_TEMP, colorTemp)
            putFloat(KEY_PRINT_CLEAN, printCleanStrength)
            apply()
        }
    }

    // ==================== 操作 ====================

    private fun onFamiliar() {
        val file = getCurrentFile() ?: return
        val filePath = file.absolutePath
        val old = scoreCache[filePath] ?: Pair(0, 0)
        scoreCache[filePath] = Pair(old.first + 1, old.second)
        hasUnsavedChanges = true
        // 不再自动跳转
    }

    private fun onStrange() {
        val file = getCurrentFile() ?: return
        val filePath = file.absolutePath
        val old = scoreCache[filePath] ?: Pair(0, 0)
        scoreCache[filePath] = Pair(old.first, old.second + 1)
        hasUnsavedChanges = true
        // 不再自动跳转
    }

    private fun getCurrentFile(): File? {
        val sourceList = if (isFavoriteMode) favoriteFiles else imageFiles
        return sourceList.getOrNull(currentIndex)
    }

    private fun prevImage() {
        val sourceList = if (isFavoriteMode) favoriteFiles else imageFiles
        if (sourceList.isEmpty()) return
        currentIndex = if (currentIndex > 0) currentIndex - 1 else sourceList.size - 1
        loadCurrentImage()
        generateNumberButtons()
    }

    private fun nextImage() {
        val sourceList = if (isFavoriteMode) favoriteFiles else imageFiles
        if (sourceList.isEmpty()) return
        currentIndex = if (currentIndex < sourceList.size - 1) currentIndex + 1 else 0
        loadCurrentImage()
        generateNumberButtons()
    }

    private fun reloadConfig() {
        if (hasUnsavedChanges) saveScores()
        dirCache.clear()
        switchDirectory()
    }

    private fun saveScores() {
        var savedSomething = false

        // 保存分数
        if (hasUnsavedChanges) {
            val updates = mutableMapOf<String, Pair<Int, Int>>()
            for ((hash, score) in scoreCache) {
                if (score.first > 0 || score.second > 0) {
                    updates[hash] = score
                }
            }
            if (updates.isNotEmpty()) {
                dbHelper.batchUpdateImageScores(updates)
                savedSomething = true
            }
            hasUnsavedChanges = false
        }

        // 保存收藏
        if (pendingFavorites.isNotEmpty()) {
            dbHelper.batchInsertFavorites(pendingFavorites.toList())
            pendingFavorites.clear()
            loadFavoritesFromDb()
            savedSomething = true
        }

        if (savedSomething) {
            ToastUtil.show(this, "已保存")
        } else {
            ToastUtil.show(this, "没有需要保存的数据")
        }
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
