package com.example.myapplication

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
import android.widget.LinearLayout

import androidx.appcompat.app.AppCompatActivity

import java.io.File
import java.io.FileOutputStream

class PdfReaderActivity : AppCompatActivity() {

    private lateinit var preview: ImageView
    private lateinit var overlay: SelectionOverlay
    private lateinit var tvPageInfo: TextView

    private var pdfRenderer: PdfRenderer? = null
    private var currentPageIndex = 0
    private var totalPages = 0

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

        // 按钮绑定
        findViewById<Button>(R.id.btnPrev).setOnClickListener { goPage(-1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { goPage(1) }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { changeZoom(-1) }
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { changeZoom(1) }
        findViewById<Button>(R.id.btnCrop).setOnClickListener { toggleCropMode() }
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            saveReadingProgress(currentPageIndex) // 保存进度
            finish()
        }




        // 优先处理从“最近打开”传来的 URI
        val pdfUri = intent.getStringExtra("pdf_uri")
        if (pdfUri != null) {
            openPdfFromUri(Uri.parse(pdfUri))
            return // 直接返回，避免执行后续的文件选择器逻辑
        }

        // 处理从其他应用接收的 PDF
        handleReceivedIntent(intent)



        // 初始化截图框画笔 (移到这里，确保 layout 已经测量完成)
        borderPaint = Paint().apply {
            color = Color.BLACK // 边框颜色
            style = Paint.Style.STROKE // 只画边框，不填充内部
            strokeWidth = 4f // 【重点】边框粗细，可以根据需要调整 (例如 6f)
            isAntiAlias = false // 【关键】墨水屏不需要抗锯齿，保持像素清晰锐利
            alpha = 255 // 不透明度 0-255
        }



        // 初始化手势检测器（双指缩放）
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // 只在非框选模式下响应双指缩放
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
                // 框选模式下不处理拖动，交给 SelectionOverlay
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
                            // 平移矩阵
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
                        // 如果是点击（非拖动），可以在这里处理点击翻页（可选）
                        // 但因为我们有按钮，这里暂时不做处理
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
            // 改为启动我们的文件选择页面
            startActivity(Intent(this, FilePickerActivity::class.java))
            finish() // 关闭当前阅读器，等用户选完文件后再打开新的
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

        // 白色背景处理（解决透明区域变黑的问题）
        val whiteBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(whiteBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(rawBitmap, 0f, 0f, null)
        rawBitmap.recycle()

        // 直接使用 whiteBitmap，不进行任何对比度增强
        preview.setImageBitmap(whiteBitmap)

        // 安全回收旧 Bitmap
        currentBitmap?.recycle()
        currentBitmap = whiteBitmap

        // 重置缩放状态
        displayScale = 1.0f
        preview.scaleType = ImageView.ScaleType.FIT_CENTER
        preview.imageMatrix = null

        currentPageIndex = index
        tvPageInfo.text = "${index + 1} / $totalPages"
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

    private fun doCrop() {
        val screenRect = overlay.getSelectionRect() ?: return
        val bmp = currentBitmap ?: return

        // 计算当前矩阵的逆矩阵，将屏幕坐标转换为 Bitmap 坐标
        val inverseMatrix = Matrix()
        preview.imageMatrix.invert(inverseMatrix)
        val points = floatArrayOf(screenRect.left.toFloat(), screenRect.top.toFloat(),
            screenRect.right.toFloat(), screenRect.bottom.toFloat())
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

                outDir.mkdirs()
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
