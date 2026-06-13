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
    private var allFiles: Array<File> = emptyArray()
    private var currentPage = 0
    private val pageSize = 15
    private var totalPages = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_picker)

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

        tvTitle.setOnClickListener { goUp() }

        findViewById<Button>(R.id.btnRecent).setOnClickListener {
            startActivity(Intent(this, RecentFilesActivity::class.java))
        }

        btnGoUp.setOnClickListener { goUp() }

        btnPrevPage.setOnClickListener {
            if (totalPages > 0) {
                currentPage = if (currentPage > 0) currentPage - 1 else totalPages - 1
                showPage()
            }
        }

        btnNextPage.setOnClickListener {
            if (totalPages > 0) {
                currentPage = if (currentPage < totalPages - 1) currentPage + 1 else 0
                showPage()
            }
        }

        btnExit.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val realIndex = currentPage * pageSize + position
            if (realIndex < allFiles.size) {
                val file = allFiles[realIndex]
                if (file.isDirectory) {
                    // 直接进入子目录，不压栈
                    enterDirectory(file, 0)
                } else {
                    openPdf(file)
                }
            }
        }

        // 初始目录容错
        val defaultDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (defaultDir.exists() && defaultDir.isDirectory) {
            enterDirectory(defaultDir, 0)
        } else {
            enterDirectory(Environment.getExternalStorageDirectory(), 0)
        }
    }

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
            if (name.length > 39) prefix + name.take(37) + "..." else prefix + name
        }

        val adapter = ArrayAdapter<String>(this, R.layout.list_item_file, displayNames)
        listView.adapter = adapter

        tvPageInfo.text = "${currentPage + 1} / $totalPages"
        btnPrevPage.isEnabled = true
        btnNextPage.isEnabled = true
    }

    /**
     * 返回上级目录：始终进入当前目录的父目录，不依赖任何历史记录。
     */
    private fun goUp() {
        val parentFile = currentDir.parentFile
        if (parentFile != null && parentFile.exists()) {
            enterDirectory(parentFile, 0)
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