package com.example.myapplication

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.*

class DirectoryPickerActivity : Activity() {

    companion object {
        const val EXTRA_SELECTED_PATH = "selected_path"
        private const val PAGE_SIZE = 20
        private const val MAX_RECENT = 50
        private const val PREF_NAME = "directory_history"
        private const val KEY_RECENT_PATHS = "recent_paths"
        const val REQUEST_RECENT = 9001

        fun getRecentPaths(context: Context): List<String> {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_RECENT_PATHS, "") ?: ""
            if (raw.isBlank()) return emptyList()
            return raw.split("\n").filter { it.isNotBlank() }
        }
    }

    private lateinit var tvCurrentPath: TextView
    private lateinit var layoutDirectoryList: LinearLayout
    private lateinit var btnBackParent: Button
    private lateinit var btnRecent: Button
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button
    private lateinit var tvPageInfo: TextView
    private lateinit var btnSelectCurrent: Button
    private lateinit var btnCancel: Button

    private var currentPath: String = "/storage/emulated/0"
    private var subDirs: List<File> = emptyList()
    private var currentPage = 0
    private var totalPages = 0
    private val navigationStack = Stack<Pair<String, Int>>()
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_directory_picker)

        tvCurrentPath = findViewById(R.id.tv_current_path)
        layoutDirectoryList = findViewById(R.id.layout_directory_list)
        btnBackParent = findViewById(R.id.btn_back_parent)
        btnRecent = findViewById(R.id.btn_recent)
        btnPrevPage = findViewById(R.id.btn_prev_page)
        btnNextPage = findViewById(R.id.btn_next_page)
        tvPageInfo = findViewById(R.id.tv_page_info)
        btnSelectCurrent = findViewById(R.id.btn_select_current)
        btnCancel = findViewById(R.id.btn_cancel)

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val allBtns = listOf(btnBackParent, btnRecent, btnPrevPage, btnNextPage,
            btnSelectCurrent, btnCancel)
        for (btn in allBtns) {
            btn.setTextColor(Color.BLACK)
            btn.setBackgroundColor(Color.WHITE)
            btn.elevation = 0f
            btn.stateListAnimator = null
        }

        loadDirectory(currentPath)

        btnBackParent.setOnClickListener { goBackParent() }
        btnRecent.setOnClickListener { openRecentDirectories() }
        btnPrevPage.setOnClickListener { prevPage() }
        btnNextPage.setOnClickListener { nextPage() }
        btnSelectCurrent.setOnClickListener { selectCurrent() }
        btnCancel.setOnClickListener { cancel() }
    }

    private fun loadDirectory(path: String) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            Toast.makeText(this, "无法访问目录: $path", Toast.LENGTH_SHORT).show()
            return
        }

        currentPath = dir.absolutePath
        tvCurrentPath.text = currentPath

        val children = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()
        subDirs = children.sortedBy { it.name.lowercase() }

        totalPages = if (subDirs.isEmpty()) 1 else (subDirs.size + PAGE_SIZE - 1) / PAGE_SIZE
        currentPage = 0
        showPage()
    }

    private fun showPage() {
        layoutDirectoryList.removeAllViews()

        val start = currentPage * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, subDirs.size)

        for (i in start until end) {
            val dir = subDirs[i]
            val btn = Button(this)

            // 统计该目录下直接图片文件数量
            val imageCount = countImagesInDir(dir)
            val suffix = if (imageCount > 0) " （${imageCount}张）" else ""
            btn.text = dir.name + suffix

            btn.textSize = 14f
            btn.setTextColor(Color.BLACK)
            btn.setBackgroundColor(Color.WHITE)
            btn.elevation = 0f
            btn.stateListAnimator = null
            btn.setPadding(16, 12, 16, 12)
            btn.isAllCaps = false
            btn.gravity = Gravity.START

            btn.setOnClickListener {
                navigationStack.push(Pair(currentPath, currentPage))
                loadDirectory(dir.absolutePath)
            }

            layoutDirectoryList.addView(btn)
        }

        if (subDirs.isEmpty()) {
            val hint = TextView(this)
            hint.text = "（该目录下没有子文件夹）"
            hint.setTextColor(Color.GRAY)
            hint.textSize = 14f
            hint.setPadding(16, 32, 16, 32)
            layoutDirectoryList.addView(hint)
        }

        tvPageInfo.text = "${currentPage + 1}/${totalPages}"
        btnBackParent.isEnabled = navigationStack.isNotEmpty()
    }

    private fun countImagesInDir(dir: File): Int {
        val extensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        return dir.listFiles()?.count { file ->
            !file.isDirectory && file.extension.lowercase(Locale.ROOT) in extensions
        } ?: 0
    }

    private fun prevPage() {
        if (totalPages == 0) return
        currentPage = if (currentPage == 0) totalPages - 1 else currentPage - 1
        showPage()
    }

    private fun nextPage() {
        if (totalPages == 0) return
        currentPage = if (currentPage >= totalPages - 1) 0 else currentPage + 1
        showPage()
    }

    private fun goBackParent() {
        if (navigationStack.isEmpty()) {
            Toast.makeText(this, "已在最顶层目录", Toast.LENGTH_SHORT).show()
            return
        }
        val (parentPath, page) = navigationStack.pop()
        currentPath = parentPath
        loadDirectory(parentPath)
        currentPage = page
        showPage()
    }

    private fun selectCurrent() {
        addToRecent(currentPath)
        val resultIntent = Intent()
        resultIntent.putExtra(EXTRA_SELECTED_PATH, currentPath)
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun openRecentDirectories() {
        val intent = Intent(this, RecentDirectoriesActivity::class.java)
        startActivityForResult(intent, REQUEST_RECENT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_RECENT && resultCode == Activity.RESULT_OK) {
            val path = data?.getStringExtra(RecentDirectoriesActivity.EXTRA_SELECTED_PATH)
            if (path != null) {
                // 直接将最近选择的路径作为结果返回给 ImageViewerActivity
                addToRecent(path)
                val resultIntent = Intent()
                resultIntent.putExtra(EXTRA_SELECTED_PATH, path)
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
        }
    }

    private fun addToRecent(path: String) {
        val recentList = getRecentPaths(this).toMutableList()
        recentList.remove(path)
        recentList.add(0, path)
        if (recentList.size > MAX_RECENT) {
            recentList.removeAt(recentList.lastIndex)
        }
        prefs.edit().putString(KEY_RECENT_PATHS, recentList.joinToString("\n")).apply()
    }
}