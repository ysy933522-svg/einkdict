package com.example.myapplication

import DictDbHelper
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.TextView

class HistoryActivity : Activity() {

    private lateinit var dbHelper: DictDbHelper
    private lateinit var gvHistory: GridView
    private lateinit var tvHistoryPage: TextView
    private lateinit var btnHistoryPrev: Button
    private lateinit var btnHistoryNext: Button
    private lateinit var btnBack: Button

    // 调整为54个（3列×18行），适合10.3寸墨水屏
    private val HISTORY_PAGE_SIZE = 54
    private var historyCurrentPage = 0
    private var historyTotalPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        dbHelper = DictDbHelper(this)
        gvHistory = findViewById(R.id.gv_history)
        tvHistoryPage = findViewById(R.id.tv_history_page)
        btnHistoryPrev = findViewById(R.id.btn_history_prev)
        btnHistoryNext = findViewById(R.id.btn_history_next)
        btnBack = findViewById(R.id.btn_back)

        // 初始化数据
        val totalCount = dbHelper.getHistoryTotalCount()
        historyCurrentPage = 0
        historyTotalPage = if (totalCount == 0) 0 else (totalCount + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE

        // 加载当前页数据
        loadHistoryPage()

        // 上一页
        btnHistoryPrev.setOnClickListener {
            if (historyCurrentPage > 0) {
                historyCurrentPage--
                loadHistoryPage()
            }
        }

        // 下一页
        btnHistoryNext.setOnClickListener {
            if (historyCurrentPage < historyTotalPage - 1) {
                historyCurrentPage++
                loadHistoryPage()
            }
        }

        // 返回按钮
        btnBack.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏切换时，GridView会根据新的屏幕尺寸自动调整
        // 如果布局发生变化，可以重新加载当前页
        loadHistoryPage()
    }

    private fun loadHistoryPage() {
        val pageData = dbHelper.getHistoryByPage(historyCurrentPage, HISTORY_PAGE_SIZE)
        val adapter = ArrayAdapter(this, R.layout.history_item, pageData)
        gvHistory.adapter = adapter

        tvHistoryPage.text = "${historyCurrentPage + 1} / $historyTotalPage"

        val canHPrev = historyCurrentPage > 0
        val canHNext = historyCurrentPage < historyTotalPage - 1

        btnHistoryPrev.isEnabled = canHPrev
        btnHistoryNext.isEnabled = canHNext

        btnHistoryPrev.setTextColor(if (canHPrev) Color.BLACK else Color.GRAY)
        btnHistoryNext.setTextColor(if (canHNext) Color.BLACK else Color.GRAY)

        // 点击条目返回主界面并查询
        gvHistory.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val word = pageData[position]
            val resultIntent = Intent()
            resultIntent.putExtra("SELECTED_WORD", word)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    // 处理按键事件（支持遥控器/翻页笔）
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) {
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (historyCurrentPage < historyTotalPage - 1) {
                    historyCurrentPage++
                    loadHistoryPage()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (historyCurrentPage > 0) {
                    historyCurrentPage--
                    loadHistoryPage()
                }
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                setResult(RESULT_CANCELED)
                finish()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }
}