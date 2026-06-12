package com.example.myapplication

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.widget.*
import java.io.File
import java.security.MessageDigest
import java.util.*
import kotlin.collections.ArrayList

class ImageViewerActivity : Activity() {


    private var currentScale = 1.0f
    private val maxScale = 4.0f
    private val minScale = 0.5f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private val touchSlop = 5

    companion object {
        // 静态缓存：目录路径 -> (文件列表, 目录最后修改时间)
        private val dirCache = mutableMapOf<String, Pair<List<File>, Long>>()
    }

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

    // 从配置文件加载的目录列表
    private var imageDirectories = mutableListOf<String>()
    private var currentDirIndex = 0
    private var recursiveScan = false

    // 当前目录下的图片文件列表
    private var imageFiles = ArrayList<File>()
    private var currentIndex = 0

    // 内存中的分数缓存：pathHash -> (familiarity, strangeness)
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

        // 后台线程初始化（避免阻塞 UI）
        Thread {
            waitForDbAndScan()
        }.start()

        // 设置点击事件
        btnFamiliar.setOnClickListener { onFamiliar() }
        btnStrange.setOnClickListener { onStrange() }
        btnSave.setOnClickListener { saveScores() }
        btnPrev.setOnClickListener { prevImage() }
        btnNext.setOnClickListener { nextImage() }
        btnSwitchDir.setOnClickListener { switchDirectory() }
        btnReload.setOnClickListener { reloadConfig() }
        btnBack.setOnClickListener { finish() }

        // 放大缩小按钮
        findViewById<Button>(R.id.btn_zoom_in).setOnClickListener { zoomIn() }
        findViewById<Button>(R.id.btn_zoom_out).setOnClickListener { zoomOut() }

        // 设置 ImageView 可点击，并添加触摸监听（用于拖动）
        ivImage.isClickable = true
        ivImage.setOnTouchListener { _, event -> handleTouch(event) }



    }


    /** 重置缩放为 fitCenter */
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

    /** 应用当前缩放倍数（以视图中心为锚点） */
    private fun applyZoom() {
        val drawable = ivImage.drawable ?: return
        val bmpW = drawable.intrinsicWidth.toFloat()
        val bmpH = drawable.intrinsicHeight.toFloat()
        val viewW = ivImage.width.toFloat()
        val viewH = ivImage.height.toFloat()
        if (bmpW <= 0 || bmpH <= 0 || viewW <= 0 || viewH <= 0) return

        // 先计算 fitCenter 的基础矩阵
        val initScale = minOf(viewW / bmpW, viewH / bmpH)
        val initOffsetX = (viewW - bmpW * initScale) / 2f
        val initOffsetY = (viewH - bmpH * initScale) / 2f
        val m = Matrix()
        m.setScale(initScale, initScale)
        m.postTranslate(initOffsetX, initOffsetY)
        // 在此基础上应用用户缩放（以视图中心为锚点）
        val cx = viewW / 2f
        val cy = viewH / 2f
        m.postScale(currentScale, currentScale, cx, cy)
        ivImage.imageMatrix = m
        ivImage.invalidate()
    }

    /** 放大 */
    private fun zoomIn() {
        currentScale *= 1.25f
        if (currentScale > maxScale) currentScale = maxScale
        applyZoom()
    }

    /** 缩小 */
    private fun zoomOut() {
        currentScale /= 1.25f
        if (currentScale < minScale) currentScale = minScale
        applyZoom()
    }

    /** 处理触摸事件（单指拖动平移） */
    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 只有放大后才允许拖动
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


    // ---------- 初始化 ----------

    private fun waitForDbAndScan() {
        while (!dbHelper.isMemoryReady) {
            Thread.sleep(200)
        }
        runOnUiThread {
            loadDirectoriesFromFile()
            scanCurrentDirectory()
        }
    }

    /** 从文本文件读取目录列表 */
    private fun loadDirectoriesFromFile() {
        imageDirectories.clear()
        val file = File("/sdcard/dicts_sqlite_diy_/image_dirs.txt")
        if (!file.exists()) {
            imageDirectories.add("/sdcard/Pictures")
            imageDirectories.add("/sdcard/DCIM/Camera")
            return
        }
        try {
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    imageDirectories.add(trimmed)
                }
            }
        } catch (e: Exception) {
            ToastUtil.show(this, "读取配置文件失败: ${e.message}")
        }
        if (imageDirectories.isEmpty()) {
            imageDirectories.add("/sdcard/Pictures")
            imageDirectories.add("/sdcard/DCIM/Camera")
        }
    }

    /** 扫描当前目录下的图片（只读文件系统，不写数据库） */
    private fun scanCurrentDirectory() {
        if (currentDirIndex >= imageDirectories.size) return
        val dirPath = imageDirectories[currentDirIndex]
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            ToastUtil.show(this, "目录不存在: $dirPath")
            return
        }

        // 检查缓存是否有效
        val cached = dirCache[dirPath]
        val dirLastModified = dir.lastModified()
        if (cached != null && cached.second == dirLastModified) {
            // 缓存有效，直接使用
            imageFiles.clear()
            imageFiles.addAll(cached.first)
        } else {
            // 缓存失效，重新扫描（仅文件系统操作，不写数据库）
            imageFiles.clear()
            scanDir(dir, recursiveScan)
            dirCache[dirPath] = Pair(imageFiles.toList(), dirLastModified)
        }

        if (imageFiles.isNotEmpty()) {
            currentIndex = Random().nextInt(imageFiles.size)
            runOnUiThread {
                loadCurrentImage()
                generateNumberButtons()
            }
        } else {
            runOnUiThread {
                ToastUtil.show(this, "该目录下没有图片")
            }
        }
    }

    /** 递归扫描目录 */
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

    // ---------- 数字按钮 ----------

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
            btn.setTextColor(android.graphics.Color.BLACK)
            btn.background = null
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

    // ---------- 图片加载 ----------

    private fun loadCurrentImage() {
        if (imageFiles.isEmpty()) return
        val file = imageFiles[currentIndex]
        if (!file.exists()) {
            ToastUtil.show(this, "图片不存在")
            return
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
            ivImage.setImageBitmap(bitmap)
        } else {
            ToastUtil.show(this, "无法加载图片")
        }
        // 从内存缓存读取分数（不从数据库读取，因为分数只存在于内存和保存时写入）

        val filePath = file.absolutePath
        if (!scoreCache.containsKey(filePath)) {
            // 首次查看该图片，尝试从数据库加载历史分数（如果有）
            val dbScore = dbHelper.getImageScore(filePath)
            scoreCache[filePath] = dbScore ?: Pair(0, 0)
        }

        // 延迟重置缩放（确保布局已测量）
        ivImage.post { resetZoom() }
    }

    // ---------- 操作 ----------

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

    /** 切换目录：先保存当前目录分数，再扫描下一个目录 */
    private fun switchDirectory() {
        if (hasUnsavedChanges) saveScores() // 自动保存当前目录分数
        currentDirIndex = (currentDirIndex + 1) % imageDirectories.size
        scanCurrentDirectory()
    }

    /** 刷新：清除缓存，强制重新扫描当前目录 */
    private fun reloadConfig() {
        if (hasUnsavedChanges) saveScores()
        dirCache.clear()
        loadDirectoriesFromFile()
        currentDirIndex = 0
        scanCurrentDirectory()
    }

    /** 保存分数：将内存中所有分数批量写入数据库 */
    private fun saveScores() {
        if (!hasUnsavedChanges) {
            ToastUtil.show(this, "没有需要保存的数据")
            return
        }
        val updates = mutableMapOf<String, Pair<Int, Int>>()
        for ((hash, score) in scoreCache) {
            // 只保存有分数的（熟悉或陌生至少有一次操作）
            if (score.first > 0 || score.second > 0) {
                updates[hash] = score
            }
        }
        if (updates.isNotEmpty()) {
            dbHelper.batchUpdateImageScores(updates)
        }
        hasUnsavedChanges = false
        ToastUtil.show(this, "已保存")
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