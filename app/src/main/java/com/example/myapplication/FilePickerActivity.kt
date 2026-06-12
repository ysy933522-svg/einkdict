package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class FilePickerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvTitle: TextView
    private lateinit var btnGoUp: Button
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button
    private lateinit var btnExit: Button
    private lateinit var tvPageInfo: TextView
    private var currentDir: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    // 当前目录下所有可显示的文件（文件夹+PDF）
    private var allFiles: Array<File> = emptyArray()
    // 分页相关
    private var currentPage = 0
    private val pageSize = 15
    private var totalPages = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_picker)

        // 全屏沉浸模式
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )

        listView = findViewById(R.id.listView)
        tvTitle = findViewById(R.id.tvTitle)
        btnGoUp = findViewById(R.id.btnGoUp)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        btnExit = findViewById(R.id.btnExit)
        tvPageInfo = findViewById<TextView>(R.id.tvPageInfo)

        // 标题栏点击返回上级
        tvTitle.setOnClickListener { goUp() }

        findViewById<Button>(R.id.btnRecent).setOnClickListener {
            startActivity(Intent(this, RecentFilesActivity::class.java))
        }

        // 返回上级目录
        btnGoUp.setOnClickListener { goUp() }

        // 上一页
        btnPrevPage.setOnClickListener {
            if (currentPage > 0) {
                currentPage--
                showPage()
            }
        }

        // 下一页
        btnNextPage.setOnClickListener {
            if (currentPage < totalPages - 1) {
                currentPage++
                showPage()
            }
        }

        // 退出返回
        btnExit.setOnClickListener { finish() }

        // 列表项点击：文件夹进入，PDF直接打开
        listView.setOnItemClickListener { _, _, position, _ ->
            val realIndex = currentPage * pageSize + position
            if (realIndex < allFiles.size) {
                val file = allFiles[realIndex]
                if (file.isDirectory) {
                    enterDirectory(file)
                } else {
                    openPdf(file)
                }
            }
        }

        // 加载初始目录
        enterDirectory(currentDir)
    }

    private fun enterDirectory(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        currentDir = dir
        tvTitle.text = dir.absolutePath

        // 获取所有文件夹和PDF文件，排序：文件夹在前，按名称字母序
        val items = dir.listFiles() ?: emptyArray()
        allFiles = items.filter { file ->
            file.isDirectory || (file.isFile && file.name.endsWith(".pdf", ignoreCase = true))
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).toTypedArray()

        // 计算总页数
        totalPages = if (allFiles.isEmpty()) 1 else (allFiles.size + pageSize - 1) / pageSize
        currentPage = 0
        showPage()
    }

    private fun showPage() {
        val start = currentPage * pageSize
        val end = minOf(start + pageSize, allFiles.size)
        val pageFiles = allFiles.slice(start until end)

        // 构造显示名称（文件夹加前缀，PDF加前缀）
        val displayNames = pageFiles.map { file ->
            val prefix = if (file.isDirectory) "📁 " else "📄 "
            // 文件名过长处理：截断到一定长度（例如30字符），避免换行
            val name = file.name
            if (name.length > 45) prefix + name.take(42) + "..." else prefix + name
        }

        val adapter = ArrayAdapter<String>(this, R.layout.list_item_file, displayNames)
        listView.adapter = adapter

        // 更新页码显示
        tvPageInfo.text = "${currentPage + 1} / $totalPages"



        // 更新按钮状态
        btnPrevPage.isEnabled = currentPage > 0
        btnNextPage.isEnabled = currentPage < totalPages - 1
    }

    private fun goUp() {
        val parent = currentDir.parentFile
        if (parent != null && parent.canRead()) {
            enterDirectory(parent)
        } else {
            Toast.makeText(this, "已到根目录", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPdf(file: File) {
        val intent = Intent(this, PdfReaderActivity::class.java)
        intent.putExtra("pdf_path", file.absolutePath)
        startActivity(intent)
    }
}