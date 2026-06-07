package com.example.myapplication

import DictDbHelper
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
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
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.TextView
import android.content.pm.PackageManager
import android.view.LayoutInflater
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.app.Dialog

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
    private var historyDialog: Dialog? = null

    private var isHistoryDialogShowing = false

    private var isFastClick = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clickInterval = 600L

    // 动态申请 读取外部存储权限（读取词典db必需）
    private val REQUEST_STORAGE_PERM = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ========== 新增开始 ==========
        // 检查读取SD卡权限
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_STORAGE_PERM
            )
        }
        // ========== 新增结束 ==========


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
                    ds.color = Color.BLACK
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

        // 加载你新建的完整弹窗布局 dialog_history_full.xml
        val dialogLayout = layoutInflater.inflate(R.layout.dialog_history_full, null)
        val gvHistory = dialogLayout.findViewById<GridView>(R.id.gv_history)
        val tvHistoryPage = dialogLayout.findViewById<TextView>(R.id.tv_history_page)
        val btnHistoryPrev = dialogLayout.findViewById<Button>(R.id.btn_history_prev)
        val btnHistoryNext = dialogLayout.findViewById<Button>(R.id.btn_history_next)

        gvHistory.isVerticalScrollBarEnabled = false
        gvHistory.isHorizontalScrollBarEnabled = false

        fun loadHistoryPage() {
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

            gvHistory.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                val word = pageData[position]
                historyDialog?.dismiss()
                etInput.setText(word)
                doQuery(false)
            }
        }

        btnHistoryPrev.setOnClickListener {
            if (historyCurrentPage > 0) {
                historyCurrentPage--
                loadHistoryPage()
            }
        }

        btnHistoryNext.setOnClickListener {
            if (historyCurrentPage < historyTotalPage - 1) {
                historyCurrentPage++
                loadHistoryPage()
            }
        }

        // 改用 Dialog，完全自定义，无系统分割线
        historyDialog = Dialog(this)
        historyDialog?.setContentView(dialogLayout)
        historyDialog?.setCancelable(true)
        historyDialog?.window?.setBackgroundDrawableResource(android.R.drawable.screen_background_light)
        historyDialog?.show()

        isHistoryDialogShowing = true
        historyDialog?.setOnDismissListener {
            isHistoryDialogShowing = false
        }

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

        btnPrev.setTextColor(if (canPrev) Color.BLACK else Color.GRAY)
        btnNext.setTextColor(if (canNext) Color.BLACK else Color.GRAY)
    }

    private fun updateBrowseBtnState() {
        val canBack = browseIndex > 0
        val canForward = browseIndex < browseStack.size - 1

        btnBack.isEnabled = canBack
        btnForward.isEnabled = canForward

        btnBack.setTextColor(if (canBack) Color.BLACK else Color.GRAY)
        btnForward.setTextColor(if (canForward) Color.BLACK else Color.GRAY)
    }

    // 蓝牙遥控器 / 翻页笔 按键翻页
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) {
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (isHistoryDialogShowing) {
                    if (historyCurrentPage < historyTotalPage - 1) {
                        historyCurrentPage++
                        val pageData = dbHelper.getHistoryByPage(historyCurrentPage, HISTORY_PAGE_SIZE)
                        val adapter = ArrayAdapter(this, R.layout.history_item, pageData)
                        val gvHistory = historyDialog?.findViewById<GridView>(R.id.gv_history)
                        val tvHistoryPage = historyDialog?.findViewById<TextView>(R.id.tv_history_page)
                        val btnHistoryPrev = historyDialog?.findViewById<Button>(R.id.btn_history_prev)
                        val btnHistoryNext = historyDialog?.findViewById<Button>(R.id.btn_history_next)

                        gvHistory?.adapter = adapter
                        tvHistoryPage?.text = "${historyCurrentPage + 1} / $historyTotalPage"

                        val canHPrev = historyCurrentPage > 0
                        val canHNext = historyCurrentPage < historyTotalPage - 1
                        btnHistoryPrev?.isEnabled = canHPrev
                        btnHistoryNext?.isEnabled = canHNext
                        btnHistoryPrev?.setTextColor(if (canHPrev) Color.BLACK else Color.GRAY)
                        btnHistoryNext?.setTextColor(if (canHNext) Color.BLACK else Color.GRAY)
                    }
                    return true
                } else {
                    if (currentPage < totalPage - 1) {
                        currentPage++
                        showCurrentPage()
                        updatePageBtnState()
                        updatePageNum()
                    }
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (isHistoryDialogShowing) {
                    if (historyCurrentPage > 0) {
                        historyCurrentPage--
                        val pageData = dbHelper.getHistoryByPage(historyCurrentPage, HISTORY_PAGE_SIZE)
                        val adapter = ArrayAdapter(this, R.layout.history_item, pageData)
                        val gvHistory = historyDialog?.findViewById<GridView>(R.id.gv_history)
                        val tvHistoryPage = historyDialog?.findViewById<TextView>(R.id.tv_history_page)
                        val btnHistoryPrev = historyDialog?.findViewById<Button>(R.id.btn_history_prev)
                        val btnHistoryNext = historyDialog?.findViewById<Button>(R.id.btn_history_next)

                        gvHistory?.adapter = adapter
                        tvHistoryPage?.text = "${historyCurrentPage + 1} / $historyTotalPage"

                        val canHPrev = historyCurrentPage > 0
                        val canHNext = historyCurrentPage < historyTotalPage - 1
                        btnHistoryPrev?.isEnabled = canHPrev
                        btnHistoryNext?.isEnabled = canHNext
                        btnHistoryPrev?.setTextColor(if (canHPrev) Color.BLACK else Color.GRAY)
                        btnHistoryNext?.setTextColor(if (canHNext) Color.BLACK else Color.GRAY)
                    }
                    return true
                } else {
                    if (currentPage > 0) {
                        currentPage--
                        showCurrentPage()
                        updatePageBtnState()
                        updatePageNum()
                    }
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    // 权限申请结果回调
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限通过，继续加载词典
                checkDbReady()
            } else {
                tvResult.text = "请授予存储权限，否则无法加载词典"
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
        historyDialog?.dismiss()
        isHistoryDialogShowing = false
    }
}