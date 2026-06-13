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
        private const val KEY_SHARPEN = "sharpen"
        private const val KEY_GAMMA = "gamma"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_DENOISE = "denoise"
        private const val KEY_COLOR_TEMP = "color_temp"
        private const val KEY_PRINT_CLEAN = "print_clean_strength"  // 新增
    }
    // 原有参数


    // 新增参数
    // 新增
    private var printCleanStrength = 0f
    private var gamma = 1.0f           // 伽马校正
    private var threshold = 0          // 二值化阈值（0=不启用）
    private var denoise = 0f           // 降噪强度
    private var colorTemp = 0          // 色温偏移
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



        prefs = getSharedPreferences("image_settings", MODE_PRIVATE)
        brightness = prefs.getFloat(KEY_BRIGHTNESS, 0f)
        contrast = prefs.getFloat(KEY_CONTRAST, 1f)
        saturation = prefs.getFloat(KEY_SATURATION, 1f)
        clipLimit = prefs.getFloat(KEY_CLIP_LIMIT, 3.0f)
        sharpenStrength = prefs.getFloat(KEY_SHARPEN, 0.5f)
        gamma = prefs.getFloat(KEY_GAMMA, 1.0f)
        threshold = prefs.getInt(KEY_THRESHOLD, 0)
        denoise = prefs.getFloat(KEY_DENOISE, 0f)
        colorTemp = prefs.getInt(KEY_COLOR_TEMP, 0)
        printCleanStrength = prefs.getFloat(KEY_PRINT_CLEAN, 0f)  // 新增



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

    private var loadImageJob: Thread? = null  // 用于取消上一个加载任务

    private fun loadCurrentImage(showToast: Boolean = false) {
        if (imageFiles.isEmpty()) return
        val file = imageFiles[currentIndex]
        if (!file.exists()) {
            Toast.makeText(this, "图片不存在", Toast.LENGTH_SHORT).show()
            return
        }

        // 取消上一个仍在运行的加载线程
        loadImageJob?.interrupt()
        loadImageJob = Thread {
            // 检查是否被中断（快速拖动时）
            if (Thread.interrupted()) return@Thread

            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap == null || Thread.interrupted()) {
                runOnUiThread { Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show() }
                return@Thread
            }

            // OpenCV 增强（确保 OpenCV 已加载）
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
                printCleanStrength = printCleanStrength   // 传入新参数
            )

            if (Thread.interrupted()) return@Thread

            runOnUiThread {
                // 保存旧 Bitmap 引用
                val oldDrawable = ivImage.drawable
                // 设置新 Bitmap
                ivImage.setImageBitmap(enhanced)

                // 如果 showToast 为 true，则在图片更新后显示提示
                if (showToast) {
                    Toast.makeText(this, "已应用", Toast.LENGTH_SHORT).show()
                }

                // 回收旧 Bitmap（确保不是同一个对象且未被回收）
                if (oldDrawable is BitmapDrawable) {
                    val oldBitmap = oldDrawable.bitmap
                    if (oldBitmap != null && !oldBitmap.isRecycled && oldBitmap !== enhanced) {
                        oldBitmap.recycle()
                    }
                }
                // 回收原始解码的 Bitmap
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }

                // 缩放等后续处理
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

                // 更新分数缓存
                val filePath = file.absolutePath
                if (!scoreCache.containsKey(filePath)) {
                    val dbScore = dbHelper.getImageScore(filePath)
                    scoreCache[filePath] = dbScore ?: Pair(0, 0)
                }
            }
        }.also { it.start() }
    }

    // ==================== 图像滤镜设置 ====================

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.setContentView(R.layout.dialog_image_settings)

        // 设置对话框宽度为屏幕宽度的 95%
        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.95).toInt()
        dialog.window?.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)

        // ★ 让对话框靠上显示，顶住屏幕顶部
        dialog.window?.setGravity(android.view.Gravity.TOP)          // 对齐到顶部
        dialog.window?.attributes?.y = 0                              // 垂直偏移量为 0（顶住顶部）
        dialog.window?.attributes?.x = 0                              // 水平偏移量（可选，0 表示水平居中？实际上 setGravity(TOP) 会让它水平居中，如果想靠左可加 Gravity.START）
        // 如果想水平也靠左，可以改为：

        // 获取所有控件
        val sbBrightness = dialog.findViewById<SeekBar>(R.id.sb_brightness)
        val sbContrast = dialog.findViewById<SeekBar>(R.id.sb_contrast)
        val sbSaturation = dialog.findViewById<SeekBar>(R.id.sb_saturation)
        val sbClipLimit = dialog.findViewById<SeekBar>(R.id.sb_clip_limit)
        val sbSharpen = dialog.findViewById<SeekBar>(R.id.sb_sharpen)
        val sbGamma = dialog.findViewById<SeekBar>(R.id.sb_gamma)
        val sbThreshold = dialog.findViewById<SeekBar>(R.id.sb_threshold)
        val sbDenoise = dialog.findViewById<SeekBar>(R.id.sb_denoise)
        val sbTemp = dialog.findViewById<SeekBar>(R.id.sb_temp)

        val tvBrightnessValue = dialog.findViewById<TextView>(R.id.tv_brightness_value)
        val tvContrastValue = dialog.findViewById<TextView>(R.id.tv_contrast_value)
        val tvSaturationValue = dialog.findViewById<TextView>(R.id.tv_saturation_value)
        val tvClipLimitValue = dialog.findViewById<TextView>(R.id.tv_clip_limit_value)
        val tvSharpenValue = dialog.findViewById<TextView>(R.id.tv_sharpen_value)
        val tvGammaValue = dialog.findViewById<TextView>(R.id.tv_gamma_value)
        val tvThresholdValue = dialog.findViewById<TextView>(R.id.tv_threshold_value)
        val tvDenoiseValue = dialog.findViewById<TextView>(R.id.tv_denoise_value)
        val tvTempValue = dialog.findViewById<TextView>(R.id.tv_temp_value)

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

        // 伽马：progress 0~490 对应 0.1~5.0
        sbGamma.progress = ((gamma - 0.1f) * 100f).toInt().coerceIn(0, 490)
        tvGammaValue.text = String.format("%.1f", gamma)

        // 二值化：0=关，1~255=阈值
        sbThreshold.progress = threshold.coerceIn(0, 255)
        tvThresholdValue.text = if (threshold == 0) "关" else threshold.toString()

        // 降噪：progress 0~500 对应 0.0~50.0
        sbDenoise.progress = (denoise * 10f).toInt().coerceIn(0, 500)
        tvDenoiseValue.text = String.format("%.0f", denoise)

        // 色温：progress 0~400 对应 -200~200
        sbTemp.progress = (colorTemp + 200).coerceIn(0, 400)
        tvTempValue.text = colorTemp.toString()


        val sbPrintClean = dialog.findViewById<SeekBar>(R.id.sb_print_clean)
        val tvPrintCleanValue = dialog.findViewById<TextView>(R.id.tv_print_clean_value)

// 设置当前值（0~100 → 0.0~1.0）
        sbPrintClean.progress = (printCleanStrength * 100f).toInt().coerceIn(0, 100)
        tvPrintCleanValue.text = String.format("%.0f", printCleanStrength * 100)  // 显示百分比

// 监听器
        sbPrintClean.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvPrintCleanValue.text = p.toString()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })


        // 设置监听器（仅更新数值显示）
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

        // 按钮样式
        for (btn in listOf(btnReset, btnApply, btnOk, btnCancel)) {
            btn.setTextColor(Color.BLACK)
            btn.elevation = 0f
            btn.stateListAnimator = null
            btn.setBackgroundColor(Color.WHITE)
        }

        // 还原按钮
        btnReset.setOnClickListener {
            // 重置所有滑块到默认值
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

            // ★ 强制手动更新所有数值 TextView（不依赖监听器）
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

            // 更新成员变量为默认值
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

            // 保存默认值到 SharedPreferences
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

            // 刷新图片
            loadCurrentImage()
            Toast.makeText(this, "已还原为默认值", Toast.LENGTH_SHORT).show()
        }

        // 应用按钮
        btnApply.setOnClickListener {
            readValuesAndSave(dialog)
            loadCurrentImage(showToast = true)  // 传入 true，图片刷新后显示提示
        }

        // 确定按钮
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
        prefs.edit().putFloat(KEY_PRINT_CLEAN, printCleanStrength).apply()

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
            apply()
        }
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