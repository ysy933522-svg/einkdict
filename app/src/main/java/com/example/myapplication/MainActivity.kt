package com.example.myapplication

import android.app.Activity
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
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.content.pm.PackageManager
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
// 在文件顶部添加导入
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import androidx.compose.ui.text.font.Typeface
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    // 在 MainActivity 类中添加以下方法和变量
    private lateinit var pageIndicator: LinearLayout
    private var pageButtons = mutableListOf<Button>()

    private lateinit var etInput: EditText
    private lateinit var btnQuery: Button
    private lateinit var tvResult: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var tvPageNum: TextView
    private lateinit var btnHistory: Button
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button

    private lateinit var btnClear: Button

    private lateinit var dbHelper: DictDbHelper

    private val PAGE_LINE_COUNT = 14
    private var allLineList = mutableListOf<String>()
    private var currentPage = 0
    private var totalPage = 0

    private val browseStack = mutableListOf<String>()
    private var browseIndex = -1

    private var isFastClick = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clickInterval = 600L

    // 动态申请 读取外部存储权限（读取词典db必需）
    private val REQUEST_STORAGE_PERM = 1001

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)




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
        btnClear = findViewById(R.id.btn_clear)  // 新增
        pageIndicator = findViewById(R.id.page_indicator)

        dbHelper = DictDbHelper(this)
        tvResult.movementMethod = LinkMovementMethod.getInstance()
        tvResult.text = "词典加载中，请稍候..."


        // 固定高度，防止 hint 消失时高度变化
        etInput.setLines(1)
        etInput.setMaxLines(1)
        etInput.setHorizontallyScrolling(true)   // 禁止换行
        etInput.setIncludeFontPadding(false)     // 减少字体内边距
        etInput.gravity = android.view.Gravity.CENTER_VERTICAL  // 垂直居中

        // 禁用所有点击效果
        tvResult.isClickable = true
        tvResult.isLongClickable = false
        tvResult.setHighlightColor(Color.TRANSPARENT)  // 设置高亮颜色为透明
        tvResult.isClickable = true
        tvResult.movementMethod = LinkMovementMethod.getInstance()

        // 永久启用按钮，避免 enabled 状态变化触发重绘
        btnPrev.isEnabled = true
        btnNext.isEnabled = true
        btnPrev.setStateListAnimator(null)
        btnNext.setStateListAnimator(null)
        btnPrev.background = null
        btnNext.background = null


        // 强制全屏：隐藏状态栏和导航栏
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())

        // 让用户从屏幕边缘滑动时临时显示系统栏（沉浸模式）
        window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE



        // 去掉所有按钮的点击特效（波纹、背景变化等）
        listOf(btnQuery, btnPrev, btnNext, btnBack, btnForward, btnHistory, btnClear).forEach { btn ->
            btn.isEnabled = true          // 始终启用
            btn.background = null         // 移除背景
            btn.setStateListAnimator(null) // 移除状态列表动画（API 21+）
            btn.isClickable = true
        }



        // 设置清除按钮点击事件
        btnClear.setOnClickListener {
            clearInput()
        }

        updatePageNum()
        updatePageBtnState()
        updateBrowseBtnState()


        // 设置清除按钮点击事件
        btnClear.setOnClickListener {
            clearInput()
        }


        // 设置回车键监听
        etInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                // 防止快速重复点击
                if (!isFastClick) {
                    isFastClick = true
                    doQuery(false)
                    mainHandler.postDelayed({ isFastClick = false }, clickInterval)
                }
                return@setOnKeyListener true  // 消耗事件
            }
            false
        }


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
                updatePageNum()
                // 延迟更新按钮颜色，避开点击事件的瞬时刷新
                mainHandler.post {
                    updatePageBtnState()
                    updatePageIndicator()
                    isFastClick = false
                }
            }
        }

        btnNext.setOnClickListener {
            if (!isFastClick && currentPage < totalPage - 1) {
                isFastClick = true
                currentPage++
                showCurrentPage()
                updatePageNum()
                mainHandler.post {
                    updatePageBtnState()
                    updatePageIndicator()
                    isFastClick = false
                }
            }
        }


        btnHistory.setOnClickListener {
            if (!isFastClick) {
                isFastClick = true
                // 启动HistoryActivity页面
                val intent = Intent(this@MainActivity, HistoryActivity::class.java)
                startActivityForResult(intent, 100)  // 100是请求码
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

    private val MAX_VISIBLE_PAGES = 13
    // 添加更新页码指示器的方法
    private fun updatePageIndicator() {
        // 清空现有按钮
        pageIndicator.removeAllViews()
        pageButtons.clear()

        if (totalPage <= 0) return

        val startPage: Int
        val endPage: Int

        if (totalPage <= MAX_VISIBLE_PAGES) {
            // 总页数不足10页，全部显示
            startPage = 0
            endPage = totalPage - 1
        } else {
            // 总页数大于10，显示10页，当前页尽量居中
            // 先尝试让当前页位于第5位（即前后各4页）
            var start = currentPage - 4
            if (start < 0) {
                start = 0
            }
            var end = start + MAX_VISIBLE_PAGES - 1
            if (end >= totalPage) {
                end = totalPage - 1
                start = end - MAX_VISIBLE_PAGES + 1
            }
            startPage = start
            endPage = end
        }

        // 生成页码按钮
        for (page in startPage..endPage) {
            val pageBtn = createPageButton(page, "${page + 1}")
            pageIndicator.addView(pageBtn)
            pageButtons.add(pageBtn)
        }
    }


    // 创建页码按钮
    private fun createPageButton(page: Int, text: String): Button {
        val button = Button(this)
        button.text = text
        button.textSize = 20f
        button.background = null
        button.setStateListAnimator(null)
        button.setPadding(4, 4, 4, 4)
        button.minWidth = 0
        button.minimumWidth = 0
        button.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = 4
            marginEnd = 4
        }

        // 所有页码按钮固定黑色
        button.setTextColor(Color.BLACK)
        button.isEnabled = true


        if (page >= 0) {
            button.setOnClickListener {
                if (!isFastClick && page != currentPage) {
                    isFastClick = true
                    currentPage = page
                    showCurrentPage()
                    updatePageBtnState()
                    updatePageNum()
                    updatePageIndicator()
                    mainHandler.postDelayed({ isFastClick = false }, clickInterval)
                }
            }
        } else {
            // 省略号按钮（实际不会用到，保留安全）
            button.isEnabled = false
        }

        return button
    }


    
    // 添加清除输入框的方法
    private fun clearInput() {
        etInput.setText("")
        etInput.requestFocus()  // 焦点回到输入框

        // 显示软键盘
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == Activity.RESULT_OK) {
            val selectedWord = data?.getStringExtra("SELECTED_WORD")
            if (selectedWord != null && selectedWord.isNotBlank()) {
                etInput.setText(selectedWord)
                doQuery(false)
            }
        }
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

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
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
                while (browseStack.size > browseIndex + 1) {
                    browseStack.removeAt(browseStack.lastIndex)
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
                    updatePageIndicator()  // 更新页码指示器
                    return@runOnUiThread
                }
                dbHelper.addHistory(input)
                showCurrentPage()
                updatePageBtnState()
                updatePageNum()
                updatePageIndicator()  // 更新页码指示器
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

    private fun updatePageNum() {
        tvPageNum.text = "${currentPage + 1} / $totalPage"
    }

    private fun updatePageBtnState() {
        // 不改变 enabled，只设置颜色

    }



    private fun updateBrowseBtnState() {

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
                if (currentPage < totalPage - 1) {
                    currentPage++
                    showCurrentPage()
                    updatePageBtnState()
                    updatePageNum()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (currentPage > 0) {
                    currentPage--
                    showCurrentPage()
                    updatePageBtnState()
                    updatePageNum()
                }
                return true
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
    }
}
