package com.example.myapplication

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream

class PdfReaderActivity : AppCompatActivity() {

    private lateinit var settingsButton: Button
    private var docSettingsOverride: GlobalSettings? = null
    private lateinit var preview: ImageView
    private lateinit var overlay: SelectionOverlay
    private lateinit var tvPageInfo: TextView

    private var pdfRenderer: PdfRenderer? = null
    private var currentPageIndex = 0
    private var totalPages = 0

    private var currentDocPath: String = ""
    private var pic_path = "/sdcard/dicts_sqlite_diy_/__pic_note__/__note__1__/"

    private var displayScale = 1.0f
    private var lastTouch = PointF()
    private var isDragging = false
    private var scaleDetector: ScaleGestureDetector? = null
    private var currentBitmap: Bitmap? = null
    private lateinit var borderPaint: Paint

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_reader)

        // 初始化 OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "初始化失败")
        } else {
            Log.d("OpenCV", "初始化成功")
        }

        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )

        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        tvPageInfo = findViewById(R.id.tvPageInfo)
        tvPageInfo.setOnClickListener {
            if (dialog?.isShowing == true) {
                dismissDialog()
            } else {
                showJumpDialog()
            }
        }

        settingsButton = findViewById(R.id.btnSettings)
        settingsButton.setOnClickListener {
            if (currentDocPath.isNotEmpty()) {
                showSettingsDialog()
            } else {
                Toast.makeText(this, "请先打开一个PDF文件", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnPrev).setOnClickListener { goPage(-1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { goPage(1) }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { changeZoom(-1) }
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { changeZoom(1) }
        findViewById<Button>(R.id.btnCrop).setOnClickListener { toggleCropMode() }
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            saveReadingProgress(currentPageIndex)
            finish()
        }

        val btnImageViewer = findViewById<Button>(R.id.btn_image_viewer)
        btnImageViewer.setOnClickListener {
            val intent = Intent(this, ImageViewerActivity::class.java)
            startActivity(intent)
        }

        val pdfUri = intent.getStringExtra("pdf_uri")
        if (pdfUri != null) {
            openPdfFromUri(Uri.parse(pdfUri))
            return
        }

        handleReceivedIntent(intent)

        borderPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = false
            alpha = 255
        }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!overlay.isSelecting) {
                    val scaleFactor = detector.scaleFactor
                    displayScale *= scaleFactor
                    displayScale = displayScale.coerceIn(0.5f, 4.0f)
                    applyDisplayMatrix()
                    return true
                }
                return false
            }
        })

        preview.setOnTouchListener { _, event ->
            if (overlay.isSelecting) {
                return@setOnTouchListener false
            }
            scaleDetector?.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouch.set(event.x, event.y)
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val dx = event.x - lastTouch.x
                        val dy = event.y - lastTouch.y
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                            isDragging = true
                            val matrix = Matrix(preview.imageMatrix)
                            matrix.postTranslate(dx, dy)
                            preview.imageMatrix = matrix
                            preview.invalidate()
                        }
                        lastTouch.set(event.x, event.y)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }

        val pdfPath = intent.getStringExtra("pdf_path")
        if (pdfPath != null) {
            openPdf(pdfPath)
        } else {
            startActivity(Intent(this, FilePickerActivity::class.java))
            finish()
        }
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        handleReceivedIntent(newIntent)
    }

    private var sharedUri: Uri? = null

    private fun handleReceivedIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) {
                    sharedUri = uri
                    openPdfFromUri(uri)
                }
            }
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    sharedUri = uri
                    openPdfFromUri(uri)
                }
            }
            else -> {
                val pdfPath = intent?.getStringExtra("pdf_path")
                if (pdfPath != null) {
                    openPdfFromPath(pdfPath)
                } else {
                    startActivity(Intent(this, FilePickerActivity::class.java))
                    finish()
                }
            }
        }
    }

    private fun openPdfFromUri(uri: Uri) {
        currentDocPath = uri.toString()
        try {
            val fileName = getFileNameFromUri(uri) ?: "unknown.pdf"
            val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            if (parcelFileDescriptor == null) {
                ToastUtil.show(this, "无法打开文件")
                return
            }
            pdfRenderer = PdfRenderer(parcelFileDescriptor)
            totalPages = pdfRenderer!!.pageCount
            currentPageIndex = 0

            val savedPage = getReadingProgress(uri.toString())
            if (savedPage > 0 && savedPage < totalPages) {
                currentPageIndex = savedPage
            }
            showPage(currentPageIndex)

            RecentFilesActivity.saveRecentPath(this, uri.toString(), fileName)
        } catch (e: Exception) {
            ToastUtil.show(this, "打开PDF失败: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun openPdfFromPath(path: String) {
        try {
            currentDocPath = path
            val file = File(path)
            val parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(parcelFileDescriptor)
            totalPages = pdfRenderer!!.pageCount
            currentPageIndex = 0

            val savedPage = getReadingProgress(path)
            if (savedPage > 0 && savedPage < totalPages) {
                currentPageIndex = savedPage
            }
            showPage(currentPageIndex)
            val currentFileName = getFileNameFromPath(path)
            RecentFilesActivity.saveRecentPath(this, path, currentFileName)
        } catch (e: Exception) {
            ToastUtil.show(this, "打开PDF失败: ${e.message}")
            e.printStackTrace()
        }
    }

    public fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }

    private fun saveReadingProgress(pageIndex: Int) {
        val pdfPath = intent.getStringExtra("pdf_path") ?: return
        val prefs = getSharedPreferences("reading_progress", MODE_PRIVATE)
        val map = mutableMapOf<String, Int>()
        val jsonStr = prefs.getString("progress", "{}") ?: "{}"
        try {
            val jsonObj = org.json.JSONObject(jsonStr)
            val keys = jsonObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = jsonObj.getInt(key)
            }
        } catch (e: Exception) { }
        map[pdfPath] = pageIndex
        val limitedMap = map.entries.drop((map.size - 50).coerceAtLeast(0)).associate { it.key to it.value }
        val newJson = org.json.JSONObject(limitedMap).toString()
        prefs.edit().putString("progress", newJson).apply()
    }

    private fun getReadingProgress(pdfPath: String): Int {
        val prefs = getSharedPreferences("reading_progress", MODE_PRIVATE)
        val jsonStr = prefs.getString("progress", "{}") ?: "{}"
        try {
            val jsonObj = org.json.JSONObject(jsonStr)
            if (jsonObj.has(pdfPath)) {
                return jsonObj.getInt(pdfPath)
            }
        } catch (e: Exception) { }
        return 0
    }

    private fun getFileNameFromPath(path: String): String {
        return try {
            if (!path.startsWith("content://")) {
                return File(path).name
            }
            val cursor = contentResolver.query(Uri.parse(path), null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return it.getString(nameIndex)
                    }
                }
            }
            "未知文件.pdf"
        } catch (e: Exception) {
            "未知文件.pdf"
        }
    }

    private fun openPdf(path: String) {
        try {
            val file = File(path)
            val parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(parcelFileDescriptor)
            totalPages = pdfRenderer!!.pageCount
            currentPageIndex = 0

            val savedPage = getReadingProgress(path)
            if (savedPage > 0 && savedPage < totalPages) {
                currentPageIndex = savedPage
            }
            showPage(currentPageIndex)
            val currentFileName = getFileNameFromPath(path)
            RecentFilesActivity.saveRecentPath(this, path, currentFileName)
        } catch (e: Exception) {
            ToastUtil.show(this, "打开PDF失败: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun showPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index < 0 || index >= totalPages) return

        val page = renderer.openPage(index)
        val targetWidth = getOptimalRenderWidth()
        val scale = targetWidth.toFloat() / page.width
        val targetHeight = (page.height * scale).toInt()

        val rawBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        page.render(rawBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        val whiteBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(whiteBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(rawBitmap, 0f, 0f, null)
        rawBitmap.recycle()

        val effectiveSettings = resolveEffectiveSettings()
        val processedBitmap = processBitmap(whiteBitmap, effectiveSettings)
        whiteBitmap.recycle()

        preview.setImageBitmap(processedBitmap)
        setCurrentBitmap(processedBitmap)

        displayScale = 1.0f
        preview.scaleType = ImageView.ScaleType.FIT_CENTER
        preview.imageMatrix = null

        currentPageIndex = index
        tvPageInfo.text = "${index + 1} / $totalPages"
    }

    // 使用 OpenCV 增强图像
    private fun processBitmap(bitmap: Bitmap, settings: GlobalSettings): Bitmap {
        val printClean = if (settings.usePrintMode) 0.5f else 0f
        return DocImageProcessor.enhance(
            src = bitmap,
            brightness = settings.brightness.toFloat(),
            contrast = settings.contrast,
            saturation = 1f,
            clipLimit = 3.0f,
            sharpenStrength = settings.sharpness,
            gamma = 1.0f,
            threshold = 0,
            denoise = 0f,
            colorTemp = 0,
            printCleanStrength = printClean
        )
    }

    // ==================== 设置对话框（滑块版，墨水屏风格） ====================
    private fun showSettingsDialog() {
        val global = GlobalSettingsManager.load(this)
        val doc = DocSettingsManager.getSettings(this, currentDocPath)

        var currentContrast = doc.contrast ?: global.contrast
        var currentBrightness = doc.brightness ?: global.brightness
        var currentSharpness = doc.sharpness ?: global.sharpness
        var currentPrintMode = doc.usePrintMode ?: global.usePrintMode

        // 构建对话框内容
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.WHITE)
        }

        // 标题
        root.addView(TextView(this).apply {
            text = "图像设置"
            textSize = 17f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        })

        // 辅助函数：创建一行滑块（修复参数名冲突）
        fun createSliderRow(
            label: String,
            initialValue: Float,
            minValue: Float,
            maxValue: Float,
            format: String = "%.1f",
            valueSuffix: String = "",
            onProgress: (Int) -> String
        ): Pair<SeekBar, TextView> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }

            val tvLabel = TextView(this).apply {
                text = label
                textSize = 14f
                minWidth = 55
                setTextColor(Color.BLACK)
            }
            row.addView(tvLabel)

            val seekBar = SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                // 使用外部参数 minValue, maxValue
                this.max = ((maxValue - minValue) * 100).toInt()
                this.progress = ((initialValue - minValue) * 100).toInt().coerceIn(0, this.max)
                splitTrack = false
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
                progressTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#AAAAAA"))
                stateListAnimator = null
                minHeight = 2
                maxHeight = 2
                setPadding(0, 12, 0, 12)
            }
            row.addView(seekBar)

            val tvValue = TextView(this).apply {
                text = String.format(format, initialValue) + valueSuffix
                textSize = 14f
                minWidth = 45
                gravity = Gravity.END
                setTextColor(Color.BLACK)
                setPadding(8, 0, 0, 0)
            }
            row.addView(tvValue)

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvValue.text = onProgress(progress)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })

            root.addView(row)
            return Pair(seekBar, tvValue)
        }

        // 创建四个滑块
        // 对比度：1.0~2.5，max=150
        val (sbContrast, tvContrast) = createSliderRow(
            "对比度", currentContrast, 1.0f, 2.5f,
            onProgress = { p -> String.format("%.2f", 1.0f + p / 100f) }
        )
        // 亮度：-50~50，max=10000（跨度100 * 100）
        val (sbBrightness, tvBrightness) = createSliderRow(
            "亮度", currentBrightness.toFloat(), -50f, 50f, format = "%.0f",
            onProgress = { p -> String.format("%.0f", -50f + p / 100f) }
        )
        // 锐化：0~3，max=300
        val (sbSharpness, tvSharpness) = createSliderRow(
            "锐化", currentSharpness, 0f, 3f,
            onProgress = { p -> String.format("%.2f", p / 100f) }
        )
        // 打印模式：0~1，max=100（实际上只需要0或1，但为了统一，用100表示1）
        val (sbPrintMode, tvPrintMode) = createSliderRow(
            "打印模式", if (currentPrintMode) 1f else 0f, 0f, 1f, format = "%.0f",
            onProgress = { p -> if (p >= 50) "开" else "关" }
        )

        // 按钮行
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        val btnLocal = Button(this).apply {
            text = "仅本文档"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            elevation = 0f
            stateListAnimator = null
            val border = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.RectShape())
            border.paint.style = Paint.Style.STROKE
            border.paint.color = Color.BLACK
            border.paint.strokeWidth = 2f
            background = border
            setPadding(12, 6, 12, 6)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 }
        }
        buttonRow.addView(btnLocal)

        val btnGlobal = Button(this).apply {
            text = "设为全局默认"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            elevation = 0f
            stateListAnimator = null
            val border = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.RectShape())
            border.paint.style = Paint.Style.STROKE
            border.paint.color = Color.BLACK
            border.paint.strokeWidth = 2f
            background = border
            setPadding(12, 6, 12, 6)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 8 }
        }
        buttonRow.addView(btnGlobal)

        root.addView(buttonRow)

        // 创建对话框
        val builder = AlertDialog.Builder(this)
        builder.setView(root)
        val dialog = builder.create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        dialog.show()

        // 读取滑块值的辅助函数（修复类型转换）
        fun readSliderValues(): SaveValues {
            val contrast = 1.0f + sbContrast.progress / 100f
            val brightness = (-50f + sbBrightness.progress / 100f).toInt()  // 转为Int
            val sharpness = sbSharpness.progress / 100f
            val printMode = sbPrintMode.progress >= 50
            return SaveValues(contrast, brightness, sharpness, printMode)
        }

        btnLocal.setOnClickListener {
            val values = readSliderValues()
            DocSettingsManager.saveSettings(this, currentDocPath, DocSettings(
                contrast = values.contrast,
                brightness = values.brightness,
                sharpness = values.sharpness,
                usePrintMode = values.printMode
            ))
            docSettingsOverride = null
            showPage(currentPageIndex)
            dialog.dismiss()
        }

        btnGlobal.setOnClickListener {
            val values = readSliderValues()
            GlobalSettingsManager.save(this, GlobalSettings(
                contrast = values.contrast,
                brightness = values.brightness,
                sharpness = values.sharpness,
                usePrintMode = values.printMode
            ))
            DocSettingsManager.clearSettings(this, currentDocPath)
            docSettingsOverride = null
            showPage(currentPageIndex)
            dialog.dismiss()
        }
    }

    data class SaveValues(
        val contrast: Float,
        val brightness: Int,
        val sharpness: Float,
        val printMode: Boolean
    )

    // ==================== 以下方法保持不变 ====================

    private fun applyDisplayMatrix() {
        val matrix = Matrix()
        val bmp = currentBitmap ?: return
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()
        val viewW = preview.width.toFloat()
        val viewH = preview.height.toFloat()

        val initScale = minOf(viewW / bmpW, viewH / bmpH)
        val initOffsetX = (viewW - bmpW * initScale) / 2f
        val initOffsetY = (viewH - bmpH * initScale) / 2f

        val cx = viewW / 2f
        val cy = viewH / 2f
        matrix.postScale(displayScale, displayScale, cx, cy)
        matrix.postTranslate(initOffsetX, initOffsetY)

        preview.imageMatrix = matrix
        preview.invalidate()
    }

    private fun changeZoom(direction: Int) {
        val step = 0.25f
        displayScale += step * direction
        displayScale = displayScale.coerceIn(0.5f, 4.0f)
        Log.d("ZOOM", "显示倍数: $displayScale")

        preview.scaleType = ImageView.ScaleType.MATRIX
        applyDisplayMatrix()
        overlay.clear()
    }

    private fun goPage(delta: Int) {
        var newIndex = currentPageIndex + delta
        if (newIndex < 0) newIndex = 0
        if (newIndex >= totalPages) newIndex = totalPages - 1
        if (newIndex == currentPageIndex) return
        showPage(newIndex)
        overlay.clear()
    }

    private fun toggleCropMode() {
        if (overlay.isSelecting) {
            doCrop()
        } else {
            overlay.isSelecting = true
            ToastUtil.show(this, "请用手指在屏幕上框选区域")
        }
    }

    private var safeBitmap: Bitmap? = null

    private fun setCurrentBitmap(newBmp: Bitmap?) {
        if (newBmp == null || newBmp.isRecycled) {
            safeBitmap?.recycle()
            safeBitmap = null
            currentBitmap = null
            return
        }
        safeBitmap?.recycle()
        safeBitmap = newBmp.config?.let { newBmp.copy(it, false) }
        currentBitmap = safeBitmap
    }

    private fun doCrop() {
        val screenRect = overlay.getSelectionRect() ?: return
        val bmp = safeBitmap ?: return

        if (bmp.isRecycled) {
            ToastUtil.show(this, "图片已被释放，请重新加载")
            return
        }

        val inverseMatrix = Matrix()
        preview.imageMatrix.invert(inverseMatrix)
        val points = floatArrayOf(
            screenRect.left.toFloat(), screenRect.top.toFloat(),
            screenRect.right.toFloat(), screenRect.bottom.toFloat()
        )
        inverseMatrix.mapPoints(points)

        val bmpX0 = points[0].toInt().coerceIn(0, bmp.width)
        val bmpY0 = points[1].toInt().coerceIn(0, bmp.height)
        val bmpX1 = points[2].toInt().coerceIn(0, bmp.width)
        val bmpY1 = points[3].toInt().coerceIn(0, bmp.height)

        val cropW = bmpX1 - bmpX0
        val cropH = bmpY1 - bmpY0
        if (cropW < 1 || cropH < 1) {
            ToastUtil.show(this, "选区太小")
            return
        }

        val cropped = Bitmap.createBitmap(bmp, bmpX0, bmpY0, cropW, cropH)

        Thread {
            try {
                val outDir = File(pic_path)
                if (!outDir.exists()) {
                    outDir.mkdirs()
                }

                val outFile = File(outDir, "crop_${System.currentTimeMillis()}.png")
                FileOutputStream(outFile).use { fos ->
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                cropped.recycle()
                runOnUiThread {
                    ToastUtil.show(this, "截图已保存: ${outFile.name}")
                    overlay.clear()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    ToastUtil.show(this, "截图失败: ${e.message}")
                }
            }
        }.start()
    }

    private fun getOptimalRenderWidth(): Int {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        return (screenWidth * 1.5).toInt()
    }

    private fun showJumpDialog() {
        val steps = listOf(2, 3, 5, 7, 13, 25, 50, 75, 125)
        val builder = AlertDialog.Builder(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(32, 15, 32, 24)
        }

        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        steps.forEach { step ->
            leftColumn.addView(Button(this).apply {
                text = "-$step"
                setTextColor(Color.BLACK)
                textSize = 38f
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                stateListAnimator = null
                setOnClickListener {
                    goPage(-step)
                    dismissDialog()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 8) }
            })
        }

        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        steps.forEach { step ->
            rightColumn.addView(Button(this).apply {
                text = "+$step"
                setTextColor(Color.BLACK)
                textSize = 38f
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                stateListAnimator = null
                setOnClickListener {
                    goPage(step)
                    dismissDialog()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 8) }
            })
        }

        layout.addView(leftColumn)
        layout.addView(rightColumn)

        builder.setView(layout)
        dialog = builder.show()
        dialog?.setOnDismissListener { dialog = null }
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_PAGE_UP -> {
                goPage(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                goPage(1)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun resolveEffectiveSettings(): GlobalSettings {
        return docSettingsOverride ?: run {
            val global = GlobalSettingsManager.load(this)
            val doc = DocSettingsManager.getSettings(this, currentDocPath)
            GlobalSettings(
                contrast = doc.contrast ?: global.contrast,
                brightness = doc.brightness ?: global.brightness,
                sharpness = doc.sharpness ?: global.sharpness,
                usePrintMode = doc.usePrintMode ?: global.usePrintMode
            )
        }
    }

    private var dialog: AlertDialog? = null

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    override fun onDestroy() {
        super.onDestroy()
        saveReadingProgress(currentPageIndex)
        pdfRenderer?.close()
        currentBitmap?.recycle()
    }
}