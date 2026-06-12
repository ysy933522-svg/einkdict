package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.artifex.mupdf.fitz.*
import java.io.File
import java.io.FileOutputStream

class PdfReaderActivity : AppCompatActivity() {

    private lateinit var preview: ImageView
    private lateinit var overlay: SelectionOverlay

    private var document: Document? = null
    private var currentPage: Page? = null
    private var currentPageIndex = 0
    private var totalPages = 0

    // 渲染宽度（缩放级别）
    private var renderWidth = 1400 // 默认 1400px
    private val renderWidthLevels = intArrayOf(1000, 1400, 1900, 2600)
    private var currentLevel = 1 // 索引 1 对应 1400

    // 缓存
    private val cache = PageCache(maxSize = 3)

    // 页面尺寸（pt）
    private var pageWidthPt = 0f
    private var pageHeightPt = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_pdf_reader)

        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { goPage(-1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { goPage(1) }
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { changeZoom(1) }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { changeZoom(-1) }
        findViewById<Button>(R.id.btnCrop).setOnClickListener { doCrop() }

        // 从 Intent 获取 PDF 路径（或通过文件选择器）
        val pdfPath = intent.getStringExtra("pdf_path")
        if (pdfPath != null) {
            openPdf(pdfPath)
        } else {
            pickPdf()
        }
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
        if (requestCode == 100 && resultCode == Activity.RESULT_OK && data != null) {
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

    private fun openPdf(path: String) {
        try {
            document = Document.openDocument(path)
            totalPages = document!!.countPages()
            Log.e("PDFDBG", "openPdf: pages=$totalPages, path=$path")
            currentPageIndex = 0
            // 获取页面尺寸（用于坐标换算）
            val firstPage = document!!.loadPage(0)
            pageWidthPt = firstPage.bounds.x1 - firstPage.bounds.x0
            pageHeightPt = firstPage.bounds.y1 - firstPage.bounds.y0
            firstPage.destroy()
            // 加载当前页
            loadPage(currentPageIndex)
        } catch (e: Exception) {
            Toast.makeText(this, "打开PDF失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPage(index: Int) {
        if (index < 0 || index >= totalPages) return
        currentPage?.destroy()
        currentPage = document!!.loadPage(index)
        currentPageIndex = index

        // 尝试从缓存获取
        val cached = cache.get(index)
        if (cached != null) {
            preview.setImageBitmap(cached)
            overlay.clear()
        } else {
            renderAndCache(index)
        }

        // 预渲染前后页
        preloadAdjacentPages(index)
    }

    private fun preloadAdjacentPages(index: Int) {
        Thread {
            if (index - 1 >= 0 && cache.get(index - 1) == null) {
                renderPageToCache(index - 1)
            }
            if (index + 1 < totalPages && cache.get(index + 1) == null) {
                renderPageToCache(index + 1)
            }
        }.start()
    }

    private fun renderAndCache(index: Int) {
        Thread {
            val bitmap = renderPageToBitmap(index, renderWidth)
            if (bitmap != null) {
                cache.put(index, bitmap)
                runOnUiThread {
                    preview.setImageBitmap(bitmap)
                    overlay.clear()
                }
            }
        }.start()
    }

    private fun renderPageToCache(index: Int) {
        val bitmap = renderPageToBitmap(index, renderWidth)
        if (bitmap != null) {
            cache.put(index, bitmap)
        }
    }

    /**
     * 渲染 PDF 指定页面为灰度 Bitmap
     * @param pageIndex 页码（从 0 开始）
     * @param width 渲染宽度（像素），高度按比例自动计算
     * @return 渲染成功的 Bitmap，失败返回 null
     */
    private fun renderPageToBitmap(pageIndex: Int, width: Int): Bitmap? {
        return try {
            val page = document!!.loadPage(pageIndex)
            val b = page.bounds



            val bounds = page.bounds
            val pw = bounds.x1 - bounds.x0
            val ph = bounds.y1 - bounds.y0
            Log.e("PDFDBG", "page bounds: pw=$pw, ph=$ph")
            val scale = width / pw
            val w = width
            val h = (ph * scale).toInt()
            Log.e("PDFDBG", "render size: w=$w, h=$h, scale=$scale")


            // ★ 用 DeviceRGB，每个像素 4 字节，输出铁定能被 Bitmap 消费
            val pix = Pixmap(ColorSpace.DeviceRGB, w, h)

            // 先铺白底（防透明=黑板）
            pix.clear(0xFF_FF_FF_FFL.toInt())  // ARGB: 0xAARRGGBB → 0xFFFFFFFF = 不透明白

            val dev = DrawDevice(pix)
            val ctm = Matrix()
            // PDF坐标系: y向上 / 屏幕: y向下 → 必须 flipY
            ctm.scale(scale, -scale)
            ctm.translate(0f, -h.toFloat())

            page.run(dev, ctm, Cookie())
            dev.close()


            val firstPixel = pix.samples[0].toInt() and 0xFF  // 假设 samples 是 ByteArray
            Log.e("PDFDBG", "first pixel value: $firstPixel")


            // ---- Pixmap → Bitmap（不用 pix.pixels，用逐行采样，100%格式安全）----
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val buf = pix.samples // 获取底层像素字节数组
            val row = IntArray(w)

            // 如果 pix 是 DeviceGray（单通道），步长是 1；如果是 DeviceRGB（3通道），步长是 3
            // 这里假设你是灰度图，如果是 RGB 图，下面 y*w+x 要改成 (y*w+x)*3
            for (y in 0 until h) {
                val off = y * w
                for (x in 0 until w) {
                    // 读取字节并转为 Int (0-255)
                    val g = buf[off + x].toInt() and 0xFF

                    // 拼装成 ARGB 格式：A(不透明) R G B
                    // 因为是灰度，所以 R=G=B=g
                    row[x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
                bmp.setPixels(row, 0, w, 0, y, w, 1)
            }

            // 【B】如果你悬停 pix. 看不到 sample()，就用 getPixel（老API写法）
            // for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, pix.getPixel(x, y))

            pix.destroy()
            page.destroy()   // ← 不是 destroy()，是 close()
            bmp

        } catch (e: Exception) {
            Log.e("PDFDBG", "renderPageToBitmap err", e)
            null
        }
    }





    private fun goPage(delta: Int) {
        val newIndex = currentPageIndex + delta
        if (newIndex in 0 until totalPages) {
            loadPage(newIndex)
        }
    }

    private fun changeZoom(direction: Int) {
        val newLevel = (currentLevel + direction).coerceIn(0, renderWidthLevels.size - 1)
        if (newLevel == currentLevel) return
        currentLevel = newLevel
        renderWidth = renderWidthLevels[newLevel]
        // 重新渲染当前页（清缓存，因为缩放变了）
        cache.clear()
        loadPage(currentPageIndex)
    }

    private fun doCrop() {
        val screenRect = overlay.getSelectionRect() ?: return
        val page = currentPage ?: return

        // 获取 ImageView 的显示尺寸
        val viewW = preview.width
        val viewH = preview.height
        val bmpW = renderWidth
        val bmpH = ((pageHeightPt / pageWidthPt) * renderWidth).toInt()

        // 计算 fitCenter 的偏移和缩放
        val scale = minOf(viewW.toFloat() / bmpW, viewH.toFloat() / bmpH)
        val drawW = bmpW * scale
        val drawH = bmpH * scale
        val offsetX = (viewW - drawW) / 2f
        val offsetY = (viewH - drawH) / 2f

        // 将屏幕坐标转换为预览位图坐标
        val bmpX0 = (screenRect.left - offsetX) / scale
        val bmpY0 = (screenRect.top - offsetY) / scale
        val bmpX1 = (screenRect.right - offsetX) / scale
        val bmpY1 = (screenRect.bottom - offsetY) / scale

        // 将预览位图坐标转换为 PDF 坐标（pt）
        val pdfScale = pageWidthPt / bmpW
        val pdfLeft = bmpX0 * pdfScale
        val pdfTop = (bmpH - bmpY1) * pdfScale // y 轴翻转
        val pdfRight = bmpX1 * pdfScale
        val pdfBottom = (bmpH - bmpY0) * pdfScale

        // 确保有效
        if (pdfRight - pdfLeft < 1 || pdfBottom - pdfTop < 1) {
            Toast.makeText(this, "选区太小", Toast.LENGTH_SHORT).show()
            return
        }

        // 高清区域渲染（MuPDF 只渲染选区，不渲染整页）
        val zoom = 2.5f
        val cropW = ((pdfRight - pdfLeft) * zoom).toInt()
        val cropH = ((pdfBottom - pdfTop) * zoom).toInt()

        Thread {



            try {
                // 1. 创建 Pixmap 和 DrawDevice
                // 注意：这里的 w 和 h 应该是你 clipRect 的宽度和高度
                val pix = Pixmap( ColorSpace.DeviceGray,   cropW,  cropH)
                val dev = DrawDevice(  pix)

                // 2. 构建变换矩阵 (CTM)
                val ctm = Matrix()

                // 2.1 先平移：将画布原点移动到要裁剪区域的左上角 (pdfLeft, pdfTop)
                // 这样渲染出来的内容就会刚好填满 Pixmap 的左上角开始的区域
                ctm.scale(scale, scale)   // 不翻转，只缩放

//                ctm.translate(-pdfLeft, -pdfTop)

                // 2.2 再缩放：应用你的 zoom 比例
                ctm.scale(zoom, zoom)

                // 3. 移除 clipRect 变量，因为它不能直接传给 run
                // val clipRect = ...

                // 4. 执行渲染 (只传入 dev, ctm, cookie)
                // 注意：这里不需要传入 clipRect
                page.run(dev, ctm, Cookie())

                dev.close()
                // 保存为 PNG 到应用私有目录
                val pngFile = File(filesDir, "debug_render.png")
                pix.saveAsPNG(pngFile.absolutePath)   // 如果这个方法存在
                // 或 pix.writePNG(pngFile.absolutePath)
                Log.e("PDFDBG", "Saved PNG: ${pngFile.absolutePath}, size=${pngFile.length()}")


                // 5. 将 Pixmap 转换为 Bitmap (复用你之前的逻辑)
                val bmp = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
                val pixels = pix.pixels
                bmp.setPixels(pixels, 0, cropW, 0, 0, cropW, cropH)

                pix.destroy()

                // ... 后续保存逻辑

                // 保存 PNG
                val outDir = File(filesDir, "pdf_crops")
                outDir.mkdirs()
                val outFile = File(outDir, "crop_${System.currentTimeMillis()}.png")
                FileOutputStream(outFile).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                bmp.recycle()

                runOnUiThread {
                    Toast.makeText(this, "截图已保存: ${outFile.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        cache.clear()
        currentPage?.destroy()
        document?.destroy()
    }
}