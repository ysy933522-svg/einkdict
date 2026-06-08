package com.example.myapplication

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.ContextCompat

class HistoryActivity : Activity() {

    private lateinit var dbHelper: DictDbHelper
    private lateinit var gvHistory: GridView
    private lateinit var tvHistoryPage: TextView
    private lateinit var btnHistoryPrev: Button
    private lateinit var btnHistoryNext: Button
    private lateinit var btnBack: Button
    private lateinit var btnClearHistory: Button
    private lateinit var btnExportHistory: Button

    private val HISTORY_PAGE_SIZE = 54
    private var historyCurrentPage = 0
    private var historyTotalPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置窗口格式
        window.setBackgroundDrawableResource(android.R.color.white)

        setContentView(R.layout.activity_history)

        dbHelper = DictDbHelper(this)
        gvHistory = findViewById(R.id.gv_history)
        tvHistoryPage = findViewById(R.id.tv_history_page)
        btnHistoryPrev = findViewById(R.id.btn_history_prev)
        btnHistoryNext = findViewById(R.id.btn_history_next)
        btnBack = findViewById(R.id.btn_back)
        btnClearHistory = findViewById(R.id.btn_clear_history)
        btnExportHistory = findViewById(R.id.btn_export_history)

        // 禁用 GridView 的所有点击视觉效果
        gvHistory.isVerticalScrollBarEnabled = false
        gvHistory.isHorizontalScrollBarEnabled = false
        gvHistory.selector = ContextCompat.getDrawable(this, android.R.color.transparent)

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

        // 清除历史按钮
        btnClearHistory.setOnClickListener {
            showClearHistoryDialog()
        }

        // 导出历史按钮
        btnExportHistory.setOnClickListener {
            exportHistoryToFile()
        }
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

    // 显示清除历史确认对话框
    private fun showClearHistoryDialog() {
        val totalCount = dbHelper.getHistoryTotalCount()
        if (totalCount == 0) {
            Toast.makeText(this, "历史记录已为空", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("清除历史记录")
            .setMessage("确定要清除所有 $totalCount 条历史记录吗？")
            .setPositiveButton("确定") { dialog, which ->
                dbHelper.clearAllHistory()
                Toast.makeText(this, "已清除所有历史记录", Toast.LENGTH_SHORT).show()

                // 重新加载数据
                historyCurrentPage = 0
                historyTotalPage = 0
                loadHistoryPage()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 导出历史记录到文件
    private fun exportHistoryToFile() {
        val allHistory = dbHelper.getAllHistory()

        if (allHistory.isEmpty()) {
            Toast.makeText(this, "没有历史记录可导出", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // 创建文件名，包含时间戳
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "dict_history_$timeStamp.txt"

            // 获取外部存储目录（在汉王N10 Pro上应该是可访问的）
            val exportDir = File("/sdcard/DictExport/")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val exportFile = File(exportDir, fileName)

            // 写入文件内容
            val content = StringBuilder()
            content.append("=== 词典查询历史记录 ===\n")
            content.append("导出时间: $timeStamp\n")
            content.append("记录总数: ${allHistory.size}\n")
            content.append("========================\n\n")

            for ((index, word) in allHistory.withIndex()) {
                content.append("${index + 1}. $word\n")
            }

            FileOutputStream(exportFile).use { fos ->
                fos.write(content.toString().toByteArray())
            }

            Toast.makeText(this, "已导出到: ${exportFile.absolutePath}", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        loadHistoryPage()
    }

    // 处理按键事件（支持遥控器/翻页笔）
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount ?: 1 > 0) return super.onKeyDown(keyCode, event)

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