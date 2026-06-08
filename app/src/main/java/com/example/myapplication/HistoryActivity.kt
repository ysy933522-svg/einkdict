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

        // ★ 关键：先把window填成纯白，再setContentView
        // 这样framebuffer从"词典页残影"→"白"→"白+文字" 变成 "白+文字"一步到位
        // 驱动看到的变化区域就只是文字部分，不再是整屏替换
        window.setBackgroundDrawableResource(android.R.color.white)
        window.setFormat(android.graphics.PixelFormat.RGB_565) // 减composition复杂度

        setContentView(R.layout.activity_history)

        dbHelper = DictDbHelper(this)
        gvHistory = findViewById(R.id.gv_history)
        tvHistoryPage = findViewById(R.id.tv_history_page)
        btnHistoryPrev = findViewById(R.id.btn_history_prev)
        btnHistoryNext = findViewById(R.id.btn_history_next)
        btnBack = findViewById(R.id.btn_back)

        // 确保无任何滚动条触发额外draw
        gvHistory.isScrollbarFadingEnabled = false
        // gvHistory.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY // 不加，保持none

        val totalCount = dbHelper.getHistoryTotalCount()
        historyCurrentPage = 0
        historyTotalPage = if (totalCount == 0) 0 else (totalCount + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE

        loadHistoryPage()

        btnHistoryPrev.setOnClickListener {
            if (historyCurrentPage > 0) { historyCurrentPage--; loadHistoryPage() }
        }
        btnHistoryNext.setOnClickListener {
            if (historyCurrentPage < historyTotalPage - 1) { historyCurrentPage++; loadHistoryPage() }
        }
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

        val canPrev = historyCurrentPage > 0
        val canNext = historyCurrentPage < historyTotalPage - 1
        btnHistoryPrev.isEnabled = canPrev
        btnHistoryNext.isEnabled = canNext
        btnHistoryPrev.setTextColor(if (canPrev) 0xFF000000.toInt() else 0xFF888888.toInt())
        btnHistoryNext.setTextColor(if (canNext) 0xFF000000.toInt() else 0xFF888888.toInt())

        gvHistory.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->


            val word = pageData[pos]
            val intent = Intent().putExtra("SELECTED_WORD", word)
            setResult(RESULT_OK, intent)
            finish()
        }
    }



    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount ?: 1 > 0) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (historyCurrentPage < historyTotalPage - 1) { historyCurrentPage++; loadHistoryPage() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (historyCurrentPage > 0) { historyCurrentPage--; loadHistoryPage() }
                return true
            }
            KeyEvent.KEYCODE_BACK -> { setResult(RESULT_CANCELED); finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }




    // 处理按键事件（支持遥控器/翻页笔）



}