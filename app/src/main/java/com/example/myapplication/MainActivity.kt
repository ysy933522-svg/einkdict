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
import android.widget.GridView
import android.widget.TextView
import android.graphics.Color


class MainActivity : Activity() {

    private lateinit var etInput: EditText
    private lateinit var btnQuery: Button
    private lateinit var tvResult: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var tvPageNum: TextView
    private lateinit var btnHistory: Button
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button

    private lateinit var dbHelper: DictDbHelper

    private val PAGE_LINE_COUNT = 14
    private var allLineList = mutableListOf<String>()
    private var currentPage = 0
    private var totalPage = 0

    private val browseStack = mutableListOf<String>()
    private var browseIndex = -1

    private val HISTORY_PAGE_SIZE = 30
    private var historyCurrentPage = 0
    private var historyTotalPage = 0
    private var historyDialog: AlertDialog? = null

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
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)

        dbHelper = DictDbHelper(this)
        tvResult.movementMethod = LinkMovementMethod.getInstance()
        tvResult.text = "词典加载中，请稍候..."

        updatePageNum()
        updatePageBtnState()
        updateBrowseBtnState()

        btnQuery.setOnClickListener {
            if (!isFastClick) {
                isFastClick = true
                doQuery(false)
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

        btnBack.setOnClickListener {
            if (browseIndex > 0) {
                browseIndex--
                val word = browseStack[browseIndex]
                etInput.setText(word)
                doQuery(true)
                updateBrowseBtnState()
            }
        }

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

        if (!isJump) {
            if (browseIndex != browseStack.size - 1) {
                while (browseStack.size > browseIndex + 1) browseStack.removeLast()
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
                dbHelper.addHistory(input)
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
            allLineList.subList(startIndex, allLineList.size).toMutableList()
        } else {
            allLineList.subList(startIndex, endIndex).toMutableList()
        }
        while (pageLines.size < PAGE_LINE_COUNT) pageLines.add("")
        val pageContent = pageLines.joinToString("\n")

        val spannable = SpannableString(pageContent)
        val wordRegex = Regex("[a-zA-Z]+")
        val matches = wordRegex.findAll(pageContent)

        for (match in matches) {
            val clickSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    etInput.setText(match.value)
                    doQuery(false)
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
        val totalCount = dbHelper.getHistoryTotalCount()
        historyCurrentPage = 0
        historyTotalPage = if (totalCount == 0) 0 else (totalCount + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE

        val rootView = layoutInflater.inflate(R.layout.history_dialog_layout, null)
        val gvHistory = rootView.findViewById<GridView>(R.id.gv_history)
        val tvHistoryPage = rootView.findViewById<TextView>(R.id.tv_history_page)
        val btnHistoryPrev = rootView.findViewById<Button>(R.id.btn_history_prev)
        val btnHistoryNext = rootView.findViewById<Button>(R.id.btn_history_next)

        gvHistory.isVerticalScrollBarEnabled = false
        gvHistory.isHorizontalScrollBarEnabled = false

        // 加载当前页数据 + 刷新UI状态
        fun loadHistoryPage() {
            val pageData = dbHelper.getHistoryByPage(historyCurrentPage, HISTORY_PAGE_SIZE)
            val adapter = ArrayAdapter(this, R.layout.history_item, pageData)
            gvHistory.adapter = adapter

            tvHistoryPage.text = "${historyCurrentPage + 1} / $historyTotalPage"
            btnHistoryPrev.isEnabled = historyCurrentPage > 0
            btnHistoryNext.isEnabled = historyCurrentPage < historyTotalPage - 1

            gvHistory.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                val word = pageData[position]
                historyDialog?.dismiss()
                etInput.setText(word)
                doQuery(false)
            }
        }

        // 上一页点击逻辑
        btnHistoryPrev.setOnClickListener {
            if (historyCurrentPage > 0) {
                historyCurrentPage--
                loadHistoryPage()
            }
        }

        // 下一页点击逻辑
        btnHistoryNext.setOnClickListener {
            if (historyCurrentPage < historyTotalPage - 1) {
                historyCurrentPage++
                loadHistoryPage()
            }
        }

        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle("查询历史")
            .setView(rootView)
            .setCancelable(true)

        historyDialog = dialogBuilder.create()
        historyDialog?.show()
        // 首次加载第一页
        loadHistoryPage()
    }




    private fun updatePageNum() {
        tvPageNum.text = "${currentPage + 1} / $totalPage"
    }

    private fun updatePageBtnState() {
        val canPrev = currentPage > 0
        val canNext = currentPage < totalPage - 1

        btnPrev.isEnabled = canPrev
        btnNext.isEnabled = canNext

        // 可用黑色，禁用灰色
        btnPrev.setTextColor(if (canPrev) Color.BLACK else Color.GRAY)
        btnNext.setTextColor(if (canNext) Color.BLACK else Color.GRAY)
    }

    private fun updateBrowseBtnState() {
        btnBack.isEnabled = browseIndex > 0
        btnForward.isEnabled = browseIndex < browseStack.size - 1
    }

    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
        historyDialog?.dismiss()
    }
}