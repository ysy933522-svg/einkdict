package com.example.myapplication

import DictDbHelper
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var etInput: EditText
    private lateinit var btnQuery: Button
    private lateinit var tvResult: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var tvPageNum: TextView
    private lateinit var btnHistory: Button
    private lateinit var dbHelper: DictDbHelper

    // 分页配置：每页固定 14 行
    private val PAGE_LINE_COUNT = 14
    // 所有结果合并后的 行列表（按换行分割）
    private var allLineList = mutableListOf<String>()
    private var currentPage = 0
    private var totalPage = 0

    private var isFastClick = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clickInterval = 600L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
                return
            }
        }

        etInput = findViewById(R.id.et_input)
        btnQuery = findViewById(R.id.btn_query)
        tvResult = findViewById(R.id.tv_result)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        tvPageNum = findViewById(R.id.tv_page_num)
        btnHistory = findViewById(R.id.btn_history)

        dbHelper = DictDbHelper(this)
        tvResult.movementMethod = LinkMovementMethod.getInstance()
        tvResult.text = "词典加载中，请稍候..."

        updatePageNum()
        updatePageBtnState()

        btnQuery.setOnClickListener {
            if (!isFastClick) {
                isFastClick = true
                doQuery()
                mainHandler.postDelayed({ isFastClick = false }, clickInterval)
            }
        }

        btnPrev.setOnClickListener {
            if (!isFastClick && currentPage > 0) {
                isFastClick = true
                currentPage--
                showCurrentPage()
                updatePageBtnState()
                updatePageNum()
                mainHandler.postDelayed({ isFastClick = false }, clickInterval)
            }
        }

        btnNext.setOnClickListener {
            if (!isFastClick && currentPage < totalPage - 1) {
                isFastClick = true
                currentPage++
                showCurrentPage()
                updatePageBtnState()
                updatePageNum()
                mainHandler.postDelayed({ isFastClick = false }, clickInterval)
            }
        }

        btnHistory.setOnClickListener {
            if (!isFastClick) {
                isFastClick = true
                showHistoryDialog()
                mainHandler.postDelayed({ isFastClick = false }, clickInterval)
            }
        }

        checkDbReady()
    }

    private fun checkDbReady() {
        mainHandler.postDelayed({
            if (dbHelper.isReady) {
                tvResult.text = "词典已就绪，请输入单词查询"
            } else {
                checkDbReady()
            }
        }, 800)
    }

    private fun doQuery() {
        val input = etInput.text.toString().trim()
        if (input.isEmpty()) {
            tvResult.text = "请输入英文单词"
            return
        }
        if (!dbHelper.isReady) {
            tvResult.text = "词典尚未加载完成，请稍后"
            return
        }

        Thread {
            // 1. 查询所有词典结果
            val dictResultList = dbHelper.queryWordWithDict(input)
            // 2. 拼接所有结果为一整段文本
            val allText = dictResultList.joinToString(separator = "\n\n\n\n")
            // 3. 按换行分割成单行列表，用于按行分页
            allLineList = allText.split("\n").toMutableList()
            // 过滤空行，避免空白占位
            allLineList.removeAll { it.isBlank() }

            // 4. 计算总页数
            totalPage = if (allLineList.isEmpty()) {
                0
            } else {
                (allLineList.size + PAGE_LINE_COUNT - 1) / PAGE_LINE_COUNT
            }
            currentPage = 0

            // 5. 更新UI
            runOnUiThread {
                if (allLineList.isEmpty()) {
                    tvResult.text = "未查询到该单词"
                    updatePageNum()
                    updatePageNum()
                    updatePageBtnState()
                    return@runOnUiThread
                }
                dbHelper.addHistory(this@MainActivity, input)
                showCurrentPage()
                updatePageBtnState()
                updatePageNum()
            }
        }.start()
    }

    // 展示当前页：截取 14 行数据
    private fun showCurrentPage() {
        val startIndex = currentPage * PAGE_LINE_COUNT
        val endIndex = startIndex + PAGE_LINE_COUNT
        // 截取当前页行范围
        val pageLines = if (endIndex >= allLineList.size) {
            allLineList.subList(startIndex, allLineList.size)
        } else {
            allLineList.subList(startIndex, endIndex)
        }
        val pageContent = pageLines.joinToString("\n")

        // 单词点击高亮
        val spannable = SpannableString(pageContent)
        val wordRegex = Regex("[a-zA-Z]+")
        val matches = wordRegex.findAll(pageContent)
        for (match in matches) {
            val clickSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    etInput.setText(match.value)
                    doQuery()
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = 0xFF000000.toInt()
                    ds.isUnderlineText = false
                }
            }
            spannable.setSpan(
                clickSpan,
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tvResult.text = spannable
    }

    private fun showHistoryDialog() {
        val historyList = dbHelper.getHistoryList(this)
        val listView = ListView(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, historyList)
        listView.adapter = adapter

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val word = historyList[position]
            etInput.setText(word)
            doQuery()
        }

        AlertDialog.Builder(this)
            .setTitle("查询历史")
            .setView(listView)
            .setNegativeButton("关闭", null)
            .create()
            .show()
    }

    private fun updatePageNum() {
        tvPageNum.text = "${currentPage + 1} / $totalPage"
    }

    private fun updatePageBtnState() {
        btnPrev.isEnabled = currentPage > 0
        btnNext.isEnabled = currentPage < totalPage - 1
    }

    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
    }
}