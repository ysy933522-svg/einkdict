package com.example.myapplication

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {

    // 内置简易词库
    private val wordDict = mapOf(
        "hello" to "你好；哈喽",
        "world" to "世界；天下",
        "ink" to "墨水",
        "screen" to "屏幕",
        "book" to "书籍；书本",
        "read" to "阅读；朗读"
    )

    private lateinit var etInput: EditText
    private lateinit var btnQuery: Button
    private lateinit var tvResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 绑定控件
        etInput = findViewById(R.id.et_input)
        btnQuery = findViewById(R.id.btn_query)
        tvResult = findViewById(R.id.tv_result)

        // 查询点击事件
        btnQuery.setOnClickListener {
            val input = etInput.text.toString().trim().lowercase()
            if (input.isEmpty()) {
                tvResult.text = "请输入单词"
                return@setOnClickListener
            }

            val explain = wordDict[input]
            tvResult.text = if (explain != null) {
                "单词：$input\n释义：$explain"
            } else {
                "未查询到该单词"
            }
        }
    }
}