package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Stack

class FilePickerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvTitle: TextView
    private lateinit var btnGoUp: Button
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button
    private lateinit var btnExit: Button
    private lateinit var tvPageInfo: TextView
    private var currentDir: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    private var allFiles: Array<File> = emptyArray()
    private var currentPage = 0
    private val pageSize = 15
    private var totalPages = 0

    // 栈：记录进入子目录前的 (上级目录, 当时的页码)
    private val dirStack = Stack<Pair<File, Int>>()

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

        // 返回上级目录（带记忆）
        btnGoUp.setOnClickListener { goUp() }

        // 上一页（循环）
        btnPrevPage.setOnClickListener {
            if (totalPages > 0) {
                currentPage = if (currentPage > 0) currentPage - 1 else totalPages - 1
                showPage()
            }
        }

        // 下一页（循环）
        btnNextPage.setOnClickListener {
            if (totalPages > 0) {
                currentPage = if (currentPage < totalPages - 1) currentPage + 1 else 0
                showPage()
            }
        }

        // 退出返回
        btnExit.setOnClickListener {
            // 直接启动查单词页面
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }

        // 列表项点击：文件夹进入（记录栈），PDF直接打开
        listView.setOnItemClickListener { _, _, position, _ ->
            val realIndex = currentPage * pageSize + position
            if (realIndex < allFiles.size) {
                val file = allFiles[realIndex]
                if (file.isDirectory) {
                    // 进入子目录前，将当前目录和页码入栈
                    dirStack.push(Pair(currentDir, currentPage))
                    enterDirectory(file, 0)
                } else {
                    openPdf(file)
                }
            }
        }

        // 加载初始目录
        enterDirectory(currentDir, 0)
    }

    /**
     * 进入指定目录，并跳转到目标页码
     */
    private fun enterDirectory(dir: File, targetPage: Int) {
        if (!dir.exists() || !dir.isDirectory) return
        currentDir = dir
        tvTitle.text = dir.absolutePath

        val items = dir.listFiles() ?: emptyArray()
        allFiles = items.filter { file ->
            file.isDirectory || (file.isFile && file.name.endsWith(".pdf", ignoreCase = true))
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).toTypedArray()

        totalPages = if (allFiles.isEmpty()) 1 else (allFiles.size + pageSize - 1) / pageSize
        currentPage = targetPage.coerceIn(0, totalPages - 1)
        showPage()
    }

    private fun showPage() {
        val start = currentPage * pageSize
        val end = minOf(start + pageSize, allFiles.size)
        val pageFiles = allFiles.slice(start until end)

        val displayNames = pageFiles.map { file ->
            val prefix = if (file.isDirectory) "📁 " else "📄 "
            val name = file.name
            if (name.length > 45) prefix + name.take(42) + "..." else prefix + name
        }

        val adapter = ArrayAdapter<String>(this, R.layout.list_item_file, displayNames)
        listView.adapter = adapter

        tvPageInfo.text = "${currentPage + 1} / $totalPages"

        // 循环模式下，按钮始终可用（不需要禁用）
        btnPrevPage.isEnabled = true
        btnNextPage.isEnabled = true
    }

    /**
     * 返回上级目录，并从栈中恢复上次浏览的页码
     */
    private fun goUp() {
        if (dirStack.isNotEmpty()) {
            val (parentDir, page) = dirStack.pop()
            enterDirectory(parentDir, page)
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