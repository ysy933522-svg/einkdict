package com.example.myapplication

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var etInput: EditText
    private lateinit var btnQuery: Button
    private lateinit var tvResult: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var dbHelper: DictDbHelper

    // 分页配置
    private var fullExplanation = ""
    private val pageSize = 500  // 单页字符数
    private var currentPage = 0
    private var totalPages = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 绑定控件
        etInput = findViewById(R.id.et_input)
        btnQuery = findViewById(R.id.btn_query)
        tvResult = findViewById(R.id.tv_result)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        dbHelper = DictDbHelper(this)

        // 初始化数据库
        Thread {
            val success = dbHelper.copyDatabase()
            runOnUiThread {
                tvResult.text = if (success) "词典初始化完成，请输入单词查询" else "词典初始化失败"
            }
        }.start()

        // 查询单词
        btnQuery.setOnClickListener {
            val input = etInput.text.toString().trim()
            if (input.isEmpty()) {
                tvResult.text = "请输入单词"
                return@setOnClickListener
            }

            // 重置分页状态
            fullExplanation = ""
            currentPage = 0
            totalPages = 0
            updatePageBtnState()

            Thread {
                val explain = dbHelper.queryWord(input)
                fullExplanation = explain

                // 计算总页数
                totalPages = if (fullExplanation.length % pageSize == 0) {
                    fullExplanation.length / pageSize
                } else {
                    fullExplanation.length / pageSize + 1
                }
                if (totalPages == 0) totalPages = 1

                runOnUiThread {
                    showPageContent()
                    updatePageBtnState()
                }
            }.start()
        }

        // 上一页
        btnPrev.setOnClickListener {
            if (currentPage > 0) {
                currentPage--
                showPageContent()
                updatePageBtnState()
            }
        }

        // 下一页
        btnNext.setOnClickListener {
            if (currentPage < totalPages - 1) {
                currentPage++
                showPageContent()
                updatePageBtnState()
            }
        }
    }

    // 展示当前页内容
    private fun showPageContent() {
        if (fullExplanation.isEmpty()) return
        val startIndex = currentPage * pageSize
        val endIndex = (currentPage + 1) * pageSize
        val content = if (endIndex >= fullExplanation.length) {
            fullExplanation.substring(startIndex)
        } else {
            fullExplanation.substring(startIndex, endIndex)
        }
        tvResult.text = content
    }

    // 更新按钮可用状态
    private fun updatePageBtnState() {
        btnPrev.isEnabled = currentPage > 0
        btnNext.isEnabled = currentPage < totalPages - 1
    }
}