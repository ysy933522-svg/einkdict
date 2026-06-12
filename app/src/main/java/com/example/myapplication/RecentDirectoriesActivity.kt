package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class RecentDirectoriesActivity : Activity() {

    companion object {
        const val EXTRA_SELECTED_PATH = "selected_path"
        private const val PAGE_SIZE = 15
    }

    private lateinit var layoutRecentList: LinearLayout
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button
    private lateinit var tvPageInfo: TextView
    private lateinit var btnBack: Button

    private var recentPaths: List<String> = emptyList()
    private var currentPage = 0
    private var totalPages = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recent_directories)

        layoutRecentList = findViewById(R.id.layout_recent_list)
        btnPrevPage = findViewById(R.id.btn_prev_page)
        btnNextPage = findViewById(R.id.btn_next_page)
        tvPageInfo = findViewById(R.id.tv_page_info)
        btnBack = findViewById(R.id.btn_back)

        for (btn in listOf(btnPrevPage, btnNextPage, btnBack)) {
            btn.setTextColor(Color.BLACK)
            btn.setBackgroundColor(Color.WHITE)
            btn.elevation = 0f
            btn.stateListAnimator = null
        }

        recentPaths = DirectoryPickerActivity.getRecentPaths(this)
        totalPages = if (recentPaths.isEmpty()) 1 else (recentPaths.size + PAGE_SIZE - 1) / PAGE_SIZE
        currentPage = 0
        showPage()

        btnPrevPage.setOnClickListener {
            if (totalPages > 0) {
                currentPage = if (currentPage == 0) totalPages - 1 else currentPage - 1
                showPage()
            }
        }
        btnNextPage.setOnClickListener {
            if (totalPages > 0) {
                currentPage = if (currentPage >= totalPages - 1) 0 else currentPage + 1
                showPage()
            }
        }
        btnBack.setOnClickListener { finish() }
    }

    private fun showPage() {
        layoutRecentList.removeAllViews()
        val start = currentPage * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, recentPaths.size)

        for (i in start until end) {
            val path = recentPaths[i]
            val btn = Button(this)

            val displayName = try {
                val parts = path.split("/")
                if (parts.size >= 2) {
                    ".../${parts[parts.size-2]}/${parts.last()}"
                } else {
                    path
                }
            } catch (e: Exception) {
                path
            }

            btn.text = displayName
            btn.textSize = 13f
            btn.setTextColor(Color.BLACK)
            btn.setBackgroundColor(Color.WHITE)
            btn.elevation = 0f
            btn.stateListAnimator = null
            btn.setPadding(16, 10, 16, 10)
            btn.isAllCaps = false
            btn.gravity = Gravity.START
            btn.maxLines = Int.MAX_VALUE
            btn.ellipsize = null

            btn.setOnClickListener {
                // 点击后直接返回路径，DirectoryPickerActivity 会将其转发给 ImageViewerActivity
                val resultIntent = Intent()
                resultIntent.putExtra(EXTRA_SELECTED_PATH, path)
                setResult(RESULT_OK, resultIntent)
                finish()
            }
            layoutRecentList.addView(btn)
        }

        if (recentPaths.isEmpty()) {
            val hint = TextView(this)
            hint.text = "暂无最近目录"
            hint.setTextColor(Color.GRAY)
            hint.textSize = 14f
            hint.setPadding(16, 40, 16, 40)
            layoutRecentList.addView(hint)
        }

        tvPageInfo.text = "${currentPage + 1}/${totalPages}"
    }
}