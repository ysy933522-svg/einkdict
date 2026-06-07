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
    // 新增：前进、后退按钮
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button

    private lateinit var dbHelper: DictDbHelper

    // 分页配置：每页14行
    private val PAGE_LINE_COUNT = 14
    private var allLineList = mutableListOf<String>()
    private var currentPage = 0
    private var totalPage = 0

    // ========== 单词浏览前进/后退 核心变量 ==========
    // 访问历史栈：按点击/查询顺序存储单词
    private val browseStack = mutableListOf<String>()
    // 当前在栈中的索引
    private var browseIndex = -1

    private var isFastClick = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clickInterval = 600L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android11+ 文件权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
                return
            }
        }

        // 绑定控件
        etInput = findViewById(R.id.et_input)
        btnQuery = findViewById(R.id.btn_query)
        tvResult = findViewById(R.id.tv_result)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        tvPageNum = findViewById(R.id.tv_page_num)
        btnHistory = findViewById(R.id.btn_history)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)

        dbHelper = DictDbHelper(this)
        tvResult.movementMethod = LinkMovementMethod.getInstance()
        tvResult.text = "词典加载中，请稍候..."

        updatePageNum()
        updatePageBtnState()
        // 初始化前进后退按钮状态
        updateBrowseBtnState()

        // 查询按钮
        btnQuery.setOnClickListener {
            if (!isFastClick) {
                isFastClick = true
                doQuery(false)
                mainHandler.postDelayed({ isFastClick = false }, clickInterval)
            }
        }

        // 上一页
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

        // 下一页
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

        // 历史记录
        btnHistory.setOnClickListener {
            if (!isFastClick) {
                isFastClick = true
                showHistoryDialog()
                mainHandler.postDelayed({ isFastClick = false }, clickInterval)
            }
        }

        // 后退按钮
        btnBack.setOnClickListener {
            if (browseIndex > 0) {
                browseIndex--
                val word = browseStack[browseIndex]
                etInput.setText(word)
                doQuery(true)
                updateBrowseBtnState()
            }
        }

        // 前进按钮
        btnForward.setOnClickListener {
            if (browseIndex < browseStack.size - 1) {
                browseIndex++
                val word = browseStack[browseIndex]
                etInput.setText(word)
                doQuery(true)
                updateBrowseBtnState()
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

    /**
     * @param isJump true=前进/后退跳转查询，不再新增栈；false=手动/点击单词，新增栈
     */
    private fun doQuery(isJump: Boolean) {
        val input = etInput.text.toString().trim()
        if (input.isEmpty()) {
            tvResult.text = "请输入英文单词"
            return
        }
        if (!dbHelper.isReady) {
            tvResult.text = "词典尚未加载完成，请稍后"
            return
        }

        // 非跳转操作：压入浏览历史栈
        if (!isJump) {
            // 清除当前索引之后的历史（类似浏览器新访问清空前进记录）
            if (browseIndex != browseStack.size - 1) {
                while (browseStack.size > browseIndex + 1) {
                    browseStack.removeLast()
                }
            }
            browseStack.add(input)
            browseIndex = browseStack.size - 1
            updateBrowseBtnState()
        }

        Thread {
            val dictResultList = dbHelper.queryWordWithDict(input)
            val allText = dictResultList.joinToString(separator = "\n\n")
            allLineList = allText.split("\n").toMutableList()
            allLineList.removeAll { it.isBlank() }

            totalPage = if (allLineList.isEmpty()) {
                0
            } else {
                (allLineList.size + PAGE_LINE_COUNT - 1) / PAGE_LINE_COUNT
            }
            currentPage = 0

            runOnUiThread {
                if (allLineList.isEmpty()) {
                    tvResult.text = "未查询到该单词"
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

    private fun showCurrentPage() {
        val startIndex = currentPage * PAGE_LINE_COUNT
        val endIndex = startIndex + PAGE_LINE_COUNT
        val pageLines = if (endIndex >= allLineList.size) {
            allLineList.subList(startIndex, allLineList.size)
        } else {
            allLineList.subList(startIndex, endIndex)
        }
        val pageContent = pageLines.joinToString("\n")

        val spannable = SpannableString(pageContent)
        val wordRegex = Regex("[a-zA-Z]+")
        val matches = wordRegex.findAll(pageContent)
        for (match in matches) {
            val clickSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    etInput.setText(match.value)
                    // 点击单词：走正常查询，新增历史栈
                    doQuery(false)
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = 0xFF000000.toInt()
                    ds.isUnderlineText = false // 取消下划线
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
            doQuery(false)
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

    // 更新前进、后退按钮可用状态
    private fun updateBrowseBtnState() {
        btnBack.isEnabled = browseIndex > 0
        btnForward.isEnabled = browseIndex < browseStack.size - 1
    }

    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
    }
}