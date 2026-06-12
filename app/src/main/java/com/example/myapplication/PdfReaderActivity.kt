package com.example.myapplication
import android.R.attr.textColor
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.app.AlertDialog
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast


import androidx.appcompat.app.AppCompatActivity

import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.Locale.getDefault

class PdfReaderActivity : AppCompatActivity() {


    private lateinit var settingsButton: Button
    private var docSettingsOverride: GlobalSettings? = null
    private lateinit var preview: ImageView
    private lateinit var overlay: SelectionOverlay
    private lateinit var tvPageInfo: TextView

    private var pdfRenderer: PdfRenderer? = null
    private var currentPageIndex = 0
    private var totalPages = 0

    // 添加这一行
    private var currentDocPath: String = ""
    private var pic_path = "/sdcard/dicts_sqlite_diy_/__pic_note__/__note__1__/";


    // 显示缩放倍数
    private var displayScale = 1.0f

    // 手势相关
    private var lastTouch = PointF()
    private var isDragging = false
    private var scaleDetector: ScaleGestureDetector? = null

    // 当前显示的 Bitmap
    private var currentBitmap: Bitmap? = null
    private lateinit var borderPaint: Paint


    override fun onResume() {
        super.onResume()

        // 你的全屏沉浸代码写在这里
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

        // 全屏沉浸模式
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

        // ★ 设置按钮绑定（必须放在所有 return 之前，确保总是执行）
        settingsButton = findViewById(R.id.btnSettings)
        settingsButton.setOnClickListener {
            if (currentDocPath.isNotEmpty()) {
                showSettingsDialog()
            } else {
                Toast.makeText(this, "请先打开一个PDF文件", Toast.LENGTH_SHORT).show()
            }
        }

        // 按钮绑定（翻页、缩放、截图、返回）
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


        // 优先处理从“最近打开”传来的 URI
        val pdfUri = intent.getStringExtra("pdf_uri")
        if (pdfUri != null) {
            openPdfFromUri(Uri.parse(pdfUri))
            return
        }

        // 处理从其他应用接收的 PDF（分享）
        handleReceivedIntent(intent)

        // 初始化截图框画笔
        borderPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = false
            alpha = 255
        }

        // 初始化手势检测器（双指缩放）
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

        // 设置触摸监听（单指拖动 + 双指缩放）
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
                    if (!isDragging) {
                        // 点击翻页（可选）
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }

        // 打开 PDF
        val pdfPath = intent.getStringExtra("pdf_path")
        if (pdfPath != null) {
            openPdf(pdfPath)
        } else {
            // 如果没有传入任何路径，启动文件选择器
            startActivity(Intent(this, FilePickerActivity::class.java))
            finish()
        }
    }

    /**
     * 通过 Content URI 直接打开 PDF（不复制文件）
     * 用于处理系统分享和最近打开记录中的 URI
     */


    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        handleReceivedIntent(newIntent)
    }

    private var sharedUri: Uri? = null // 保存分享来的 URI

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

    /** 从 URI 打开 PDF（不复制，直接使用原始文件） */
    private fun openPdfFromUri(uri: Uri) {
        // ★ 关键：设置当前文档路径，使设置按钮能正常工作
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

            // 保存最近打开记录（使用 URI 字符串和文件名）
            RecentFilesActivity.saveRecentPath(this, uri.toString(), fileName)
        } catch (e: Exception) {
            ToastUtil.show(this, "打开PDF失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /** 从文件路径打开 PDF（原有逻辑） */
    private fun openPdfFromPath(path: String) {
        try {
            currentDocPath = path  // ★ 确保有这一行
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
            // 【新增】获取文件名
            val currentFileName = getFileNameFromPath(path)
            RecentFilesActivity.saveRecentPath(this, path, currentFileName)
        } catch (e: Exception) {
            ToastUtil.show(this, "打开PDF失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /** 从 URI 提取文件名 */
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
        } catch (e: Exception) { /* 忽略 */ }
        map[pdfPath] = pageIndex
        // 修正：确保 drop 参数不小于 0
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
        } catch (e: Exception) { /* 忽略 */ }
        return 0 // 默认第一页
    }

    private fun saveRecentPath(path: String) {
        val prefs = getSharedPreferences("recent_files", MODE_PRIVATE)
        val recentList = prefs.getStringSet("paths", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        recentList.add(path)
        // 限制最多保存 10 条
        if (recentList.size > 10) {
            val sorted = recentList.sortedByDescending { File(it).lastModified() }
            recentList.clear()
            recentList.addAll(sorted.take(10))
        }
        prefs.edit().putStringSet("paths", recentList).apply()
    }

    private fun pickPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, 100)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            val path = copyToAppFiles(uri, "temp.pdf")
            openPdf(path)
        }
    }

    private fun copyToAppFiles(uri: Uri, name: String): String {
        val out = File(filesDir, name)
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out.absolutePath
    }

    /** 根据路径提取文件名 */
    private fun getFileNameFromPath(path: String): String {
        return try {
            // 情况1：如果是普通的本地文件路径 (file:// 或直接路径)
            if (!path.startsWith("content://")) {
                return File(path).name
            }

            // 情况2：如果是 Content URI (分享过来的文件通常属于这种情况)
            val cursor = contentResolver.query(Uri.parse(path), null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return it.getString(nameIndex)
                    }
                }
            }
            // 兜底默认名
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
                currentPageIndex =savedPage

            }
            showPage(currentPageIndex)
            // 【新增】获取文件名
            val currentFileName = getFileNameFromPath(path)
            RecentFilesActivity.saveRecentPath(this, path, currentFileName)
        } catch (e: Exception) {
            ToastUtil.show(this,  "打开PDF失败: ${e.message}")
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

        // 白色背景处理
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




    private fun showSettingsDialog() {
        val global = GlobalSettingsManager.load(this)
        val doc = DocSettingsManager.getSettings(this, currentDocPath)

        var currentContrast = doc.contrast ?: global.contrast
        var currentBrightness = doc.brightness ?: global.brightness
        var currentSharpness = doc.sharpness ?: global.sharpness
        var currentPrintMode = doc.usePrintMode ?: global.usePrintMode

        val builder = AlertDialog.Builder(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(android.graphics.Color.WHITE)
        }

        // 对比度
        layout.addView(createInputRow("对比度", currentContrast, 1.0f, 2.5f, "1.0~2.5"))
        // 亮度
        layout.addView(createInputRow("亮度", currentBrightness.toFloat(), -50f, 50f, "-50~50"))
        // 锐化
        layout.addView(createInputRow("锐化", currentSharpness, 0f, 3f, "0~3"))
        // 打印模式
        layout.addView(createInputRow("打印模式", if (currentPrintMode) 1f else 0f, 0f, 1f, "0关 1开"))

        // 两个按钮的容器（水平排列）
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        // 按钮1：仅本文档
        val btnLocal = Button(this).apply {
            text = "仅本文档"
            setTextColor(android.graphics.Color.BLACK)
            background = null
            val border = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.RectShape())
            border.paint.style = android.graphics.Paint.Style.STROKE
            border.paint.color = android.graphics.Color.BLACK
            border.paint.strokeWidth = 2f
            background = border
            setPadding(12, 6, 12, 6)
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { rightMargin = 8 }
        }
        buttonContainer.addView(btnLocal)

        // 按钮2：设为全局默认
        val btnGlobal = Button(this).apply {
            text = "设为全局默认"
            setTextColor(android.graphics.Color.BLACK)
            background = null
            val border = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.RectShape())
            border.paint.style = android.graphics.Paint.Style.STROKE
            border.paint.color = android.graphics.Color.BLACK
            border.paint.strokeWidth = 2f
            background = border
            setPadding(12, 6, 12, 6)
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { leftMargin = 8 }
        }
        buttonContainer.addView(btnGlobal)

        layout.addView(buttonContainer)

        builder.setView(layout)
        val dialog = builder.create()
        dialog.show()

        // 从布局中获取输入框的工具函数
        fun getEditText(tag: String): EditText? = layout.findViewWithTag(tag)

        // 读取输入值的函数
        fun readValues(): SaveValues {
            val etContrast = getEditText("对比度")
            val etBrightness = getEditText("亮度")
            val etSharpness = getEditText("锐化")
            val etPrintMode = getEditText("打印模式")

            val contrast = etContrast?.text.toString().toFloatOrNull()?.coerceIn(1.0f, 2.5f) ?: currentContrast
            val brightness = etBrightness?.text.toString().toIntOrNull()?.coerceIn(-50, 50) ?: currentBrightness
            val sharpness = etSharpness?.text.toString().toFloatOrNull()?.coerceIn(0f, 3f) ?: currentSharpness
            val printModeValue = etPrintMode?.text.toString().toIntOrNull()?.coerceIn(0, 1) ?: 0
            val printMode = printModeValue == 1
            return SaveValues(contrast, brightness, sharpness, printMode)
        }

        // 按钮点击事件
        btnLocal.setOnClickListener {
            val values = readValues()
            // 保存为本文档设置
            DocSettingsManager.saveSettings(this, currentDocPath, DocSettings(
                contrast = values.contrast,
                brightness = values.brightness,
                sharpness = values.sharpness,
                usePrintMode = values.printMode
            ))
            // 清除临时覆盖
            docSettingsOverride = null
            showPage(currentPageIndex)
            dialog.dismiss()
        }

        btnGlobal.setOnClickListener {
            val values = readValues()
            // 保存为全局设置
            GlobalSettingsManager.save(this, GlobalSettings(
                contrast = values.contrast,
                brightness = values.brightness,
                sharpness = values.sharpness,
                usePrintMode = values.printMode
            ))
            // 清除本文档的自定义设置（使其跟随全局）
            DocSettingsManager.clearSettings(this, currentDocPath)
            docSettingsOverride = null
            showPage(currentPageIndex)
            dialog.dismiss()
        }
    }

    // 辅助数据类（用于保存读取的值）
    data class SaveValues(
        val contrast: Float,
        val brightness: Int,
        val sharpness: Float,
        val printMode: Boolean
    )





    private fun createInputRow(label: String, initial: Float, min: Float, max: Float, hint: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            setBackgroundColor(android.graphics.Color.WHITE)
        }

        val tvLabel = TextView(this).apply {
            text = label
            textSize = 16f
            minWidth = 56
            setTextColor(android.graphics.Color.BLACK)
        }
        row.addView(tvLabel)

        val editText = EditText(this).apply {
            setText(String.format("%.1f", initial))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.BLACK)
            background = null
            val underline = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.RectShape())
            underline.paint.style = android.graphics.Paint.Style.STROKE
            underline.paint.color = android.graphics.Color.BLACK
            underline.paint.strokeWidth = 1f
            background = underline
            setPadding(8, 4, 8, 4)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            // ★ 设置 Tag 为 label（用于后续查找）
            tag = label
        }
        row.addView(editText)

        val tvHint = TextView(this).apply {
            text = hint
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(8, 0, 0, 0)
        }
        row.addView(tvHint)

        return row
    }


    /** 临时应用设置（不保存，仅用于实时预览） */
    private fun reRenderWithTempSettings(contrast: Float, brightness: Int, sharpness: Float, printMode: Boolean) {
        // 临时覆盖 docSettings 并重绘
        val tempGlobal = GlobalSettings(contrast, brightness, sharpness, printMode)
        // 直接使用临时设置渲染当前页（不保存）
        // 这里简单起见，我们直接修改 docSettings 并重绘，但不保存
        // 更好的做法是单独传参给 showPage，但为了简化，我们直接修改 docSettings 并重绘
        // 注意：这只是临时预览，不会保存
        docSettingsOverride = tempGlobal // 新增成员变量
        showPage(currentPageIndex)
        docSettingsOverride = null
    }



    /**
     * 核心图像处理函数
     * 根据传入的 DisplaySettings 对 Bitmap 进行亮度、对比度、锐化处理
     */
    private fun processBitmap(bitmap: Bitmap, settings: GlobalSettings): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        if (settings.contrast != 1.0f || settings.brightness != 0) {
            val cm = ColorMatrix()
            val scale = settings.contrast
            val translate = settings.brightness + (1f - scale) * 127f
            cm.set(floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))
            paint.colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }


    private fun enhanceContrast(bitmap: Bitmap, contrast: Float): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val paint = Paint()
        val cm = ColorMatrix().apply {
            set(floatArrayOf(
                contrast, 0f, 0f, 0f, 0f,
                0f, contrast, 0f, 0f, 0f,
                0f, 0f, contrast, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun applyDisplayMatrix() {
        val matrix = Matrix()
        // 获取 Bitmap 实际尺寸
        val bmp = currentBitmap ?: return
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()
        val viewW = preview.width.toFloat()
        val viewH = preview.height.toFloat()

        // 计算初始 fitCenter 的缩放和偏移
        val initScale = minOf(viewW / bmpW, viewH / bmpH)
        val initOffsetX = (viewW - bmpW * initScale) / 2f
        val initOffsetY = (viewH - bmpH * initScale) / 2f

        // 应用用户缩放倍数（以视图中心为锚点）
        val cx = viewW / 2f
        val cy = viewH / 2f
        matrix.postScale(displayScale, displayScale, cx, cy)
        // 再加上初始偏移（使内容居中）
        matrix.postTranslate(initOffsetX, initOffsetY)

        preview.imageMatrix = matrix
        preview.invalidate()
    }

    private fun changeZoom(direction: Int) {
        val step = 0.25f
        displayScale += step * direction
        displayScale = displayScale.coerceIn(0.5f, 4.0f)
        Log.d("ZOOM", "显示倍数: $displayScale")

        // 切换到 MATRIX 模式并应用缩放
        preview.scaleType = ImageView.ScaleType.MATRIX
        applyDisplayMatrix()
        overlay.clear()
    }

    private fun goPage(delta: Int) {
        var newIndex = currentPageIndex + delta
        // 越界时直接跳到边界
        if (newIndex < 0) newIndex = 0
        if (newIndex >= totalPages) newIndex = totalPages - 1
        if (newIndex == currentPageIndex) return // 无变化则不刷新
        showPage(newIndex)
        overlay.clear()
    }

    private fun toggleCropMode() {
        if (overlay.isSelecting) {
            // 执行截图
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
        val bmp = safeBitmap ?: return  // 改为 safeBitmap

        // ★ 关键修复：检查源 Bitmap 是否已被回收
        if (bmp.isRecycled) {
            ToastUtil.show(this, "图片已被释放，请重新加载")
            return
        }

        // 计算当前矩阵的逆矩阵，将屏幕坐标转换为 Bitmap 坐标
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

        // 创建裁剪图（此时 bmp 一定是有效的）
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
                cropped.recycle()  // 释放裁剪图，不影响源图
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
        // 渲染宽度 = 屏幕宽度 × 1.5（可根据需要调整倍数）
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

        // 左列：后退按钮
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        steps.forEach { step ->
            leftColumn.addView(Button(this).apply {
                text = "-$step"
                setTextColor(Color.BLACK)
                textSize = 42f
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

        // 右列：前进按钮
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        steps.forEach { step ->
            rightColumn.addView(Button(this).apply {
                text = "+$step"
                setTextColor(Color.BLACK)
                textSize = 42f
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
        dialog?.setOnDismissListener { dialog = null }  // 关键修复
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
    private fun showSaveScopeDialog(contrast: Float, brightness: Int, sharpness: Float, printMode: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("保存设置")
            .setMessage("应用到所有文档还是仅本文档？")
            .setPositiveButton("仅本文档") { _, _ ->
                DocSettingsManager.saveSettings(this, currentDocPath, DocSettings(
                    contrast = contrast,
                    brightness = brightness,
                    sharpness = sharpness,
                    usePrintMode = printMode
                ))
                // 清除临时覆盖
                docSettingsOverride = null
                showPage(currentPageIndex)
            }
            .setNeutralButton("设为全局默认") { _, _ ->
                GlobalSettingsManager.save(this, GlobalSettings(
                    contrast = contrast,
                    brightness = brightness,
                    sharpness = sharpness,
                    usePrintMode = printMode
                ))
                // 清除本文档的自定义设置（使其跟随全局）
                DocSettingsManager.clearSettings(this, currentDocPath)
                docSettingsOverride = null
                showPage(currentPageIndex)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private var dialog: AlertDialog? = null

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    override fun onDestroy() {
        super.onDestroy()
        saveReadingProgress(currentPageIndex) // 确保退出时保存
        pdfRenderer?.close()
        currentBitmap?.recycle()
    }
}
