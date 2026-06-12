package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.widget.*
import java.io.File
import java.util.*
import kotlin.collections.ArrayList

class ImageViewerActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_DIRECTORY = 1001
        private val dirCache = mutableMapOf<String, Pair<List<File>, Long>>()
    }

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
    private lateinit var layoutNumberButtons: LinearLayout

    private lateinit var dbHelper: DictDbHelper

    private var currentDirectoryPath: String = ""
    private var recursiveScan = false

    private var imageFiles = ArrayList<File>()
    private var currentIndex = 0

    private val scoreCache = mutableMapOf<String, Pair<Int, Int>>()
    private var hasUnsavedChanges = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

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
        layoutNumberButtons = findViewById(R.id.layout_number_buttons)

        dbHelper = (application as MyApplication).dbHelper

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

        findViewById<Button>(R.id.btn_zoom_in).setOnClickListener { zoomIn() }
        findViewById<Button>(R.id.btn_zoom_out).setOnClickListener { zoomOut() }

        ivImage.isClickable = true
        ivImage.setOnTouchListener { _, event -> handleTouch(event) }
    }

    private fun applyEinkStyle() {
        val buttons = listOf(
            btnFamiliar, btnStrange, btnSave, btnPrev, btnNext,
            btnSwitchDir, btnReload, btnBack,
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

    // ==================== 初始化 ====================

    private fun waitForDbAndScan() {
        while (!dbHelper.isMemoryReady) {
            Thread.sleep(200)
        }
        runOnUiThread {
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

    // ==================== 图片加载（修复抖动） ====================

    // ==================== 图片加载（修复抖动） ====================

    private fun loadCurrentImage() {
        if (imageFiles.isEmpty()) return
        val file = imageFiles[currentIndex]
        if (!file.exists()) {
            Toast.makeText(this, "图片不存在", Toast.LENGTH_SHORT).show()
            return
        }

        // 解码图片
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show()
            return
        }

        // 直接设置新图片，不清除旧图（避免空白闪烁）
        ivImage.setImageBitmap(bitmap)

        // 重置缩放状态
        currentScale = 1.0f
        ivImage.scaleType = ImageView.ScaleType.MATRIX

        // 立即尝试应用 fitCenter 矩阵（如果 ImageView 已有尺寸）
        if (ivImage.width > 0 && ivImage.height > 0) {
            applyFitCenterMatrix()
        } else {
            // 等待布局完成后应用
            ivImage.viewTreeObserver.addOnGlobalLayoutListener(object :
                android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    ivImage.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    applyFitCenterMatrix()
                }
            })
        }

        // 分数缓存（与之前相同）
        val filePath = file.absolutePath
        if (!scoreCache.containsKey(filePath)) {
            val dbScore = dbHelper.getImageScore(filePath)
            scoreCache[filePath] = dbScore ?: Pair(0, 0)
        }
    }

    /**
     * 将图片以 fitCenter 方式居中显示（不缩放，即原始大小适应视图）
     */
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