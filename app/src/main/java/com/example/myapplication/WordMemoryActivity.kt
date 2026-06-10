package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.util.*

class WordMemoryActivity : Activity() {

    private lateinit var tvCategory: TextView
    private lateinit var tvProgress: TextView
    private lateinit var layoutWordGrid: LinearLayout
    private lateinit var tvCurrentWord: TextView
    private lateinit var tvExplanation: TextView
    private lateinit var btnFamiliar: Button
    private lateinit var btnStrange: Button
    private lateinit var btnUpdate: Button

    private lateinit var dbHelper: DictDbHelper

    private val TOTAL_WORDS = 100
    private val DISPLAY_COUNT = 20

    // 内存数据
    private var allLoadedWords = mutableListOf<WordMemoryItem>()
    private var pendingQueue = mutableListOf<WordMemoryItem>()
    private var displayedWords = mutableListOf<WordMemoryItem>()
    private var currentSelectedIndex = 0
    private var hasUnsavedChanges = false

    // 存储每一行的引用
    private val rowViews = mutableListOf<LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word_memory)

        // 全屏（无状态栏）
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        tvProgress = findViewById(R.id.tv_progress)
        layoutWordGrid = findViewById(R.id.layout_word_grid)
        tvCurrentWord = findViewById(R.id.tv_current_word)
        tvExplanation = findViewById(R.id.tv_explanation)
        btnFamiliar = findViewById(R.id.btn_familiar)
        btnStrange = findViewById(R.id.btn_strange)
        btnUpdate = findViewById(R.id.btn_update)

        dbHelper = (application as MyApplication).dbHelper

        val category = intent.getStringExtra("CATEGORY") ?: "CET4"


        loadWords(category)

        btnFamiliar.setOnClickListener { onFamiliar() }
        btnStrange.setOnClickListener { onStrange() }
        btnUpdate.setOnClickListener { saveChanges() }
    }

    private fun loadWords(category: String) {
        allLoadedWords = dbHelper.loadWordsForMemory(category, TOTAL_WORDS)
        if (allLoadedWords.isEmpty()) {
            Toast.makeText(this, "该分类暂无单词，请先导入", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        allLoadedWords.shuffle()
        pendingQueue = allLoadedWords.toMutableList()

        displayedWords.clear()
        for (i in 0 until minOf(DISPLAY_COUNT, pendingQueue.size)) {
            displayedWords.add(pendingQueue.removeAt(0))
        }

        buildWordGrid()
        // 默认选中第一个单词
        if (displayedWords.isNotEmpty()) {
            currentSelectedIndex = 0
            updateCurrentSelection()
        }
        updateProgress()
    }

    private fun buildWordGrid() {
        layoutWordGrid.removeAllViews()
        rowViews.clear()

        for (row in 0 until 10) {
            val rowLayout = LinearLayout(this)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            rowLayout.gravity = Gravity.CENTER_VERTICAL
            rowLayout.setPadding(0, 2, 0, 2)

            for (col in 0 until 2) {
                val index = row * 2 + col
                val tvWord = TextView(this)
                tvWord.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                tvWord.textSize = 17f
                tvWord.setTextColor(Color.BLACK)
                tvWord.gravity = Gravity.CENTER
                tvWord.setPadding(4, 4, 4, 4)

                // ★★★ 在这里添加点击事件 ★★★
                tvWord.setOnClickListener {
                    if (index < displayedWords.size) {
                        val word = displayedWords[index].word
                        val intent = Intent(this@WordMemoryActivity, MainActivity::class.java)
                        intent.putExtra("QUERY_WORD", word)
                        intent.putExtra("FROM_MEMORY", true)
                        startActivity(intent)
                    }
                }

                rowLayout.addView(tvWord)
            }
            layoutWordGrid.addView(rowLayout)
            rowViews.add(rowLayout)
        }

        refreshWordGrid()
    }

    private fun refreshWordGrid() {
        for (i in 0 until 20) {
            val row = i / 2
            val col = i % 2
            val rowLayout = rowViews[row]
            val tvWord = rowLayout.getChildAt(col) as TextView

            if (i < displayedWords.size) {
                tvWord.text = displayedWords[i].word
                tvWord.visibility = TextView.VISIBLE
            } else {
                tvWord.text = ""
                tvWord.visibility = TextView.INVISIBLE
            }
        }
    }

    private fun updateCurrentSelection() {
        if (currentSelectedIndex < displayedWords.size) {
            val wordItem = displayedWords[currentSelectedIndex]
            tvCurrentWord.text = wordItem.word
            btnFamiliar.isEnabled = true
            btnStrange.isEnabled = true

            // 不再在此处查询释义，而是跳转到主页面
            // 但保留点击单词跳转的功能（在buildWordGrid中设置）
        } else {
            tvCurrentWord.text = ""
            btnFamiliar.isEnabled = false
            btnStrange.isEnabled = false
        }
    }

    private fun queryExplanation(word: String) {
        // 在后台线程查询，结果在主线程更新
        Thread {
            val explanations = dbHelper.queryWordWithDict(word)
            runOnUiThread {
                if (explanations.isNotEmpty()) {
                    tvExplanation.text = explanations.joinToString("\n\n")
                } else {
                    tvExplanation.text = "（无释义）"
                }
            }
        }.start()
    }

    private fun onFamiliar() {
        if (currentSelectedIndex >= displayedWords.size) return
        val item = displayedWords[currentSelectedIndex]
        item.deltaFamiliarity++
        hasUnsavedChanges = true

        // 从显示列表和待显示队列移除
        displayedWords.removeAt(currentSelectedIndex)
        pendingQueue.remove(item)

        // 补充新单词
        if (pendingQueue.isNotEmpty()) {
            displayedWords.add(pendingQueue.removeAt(0))
        }

        // 调整选中索引
        if (currentSelectedIndex >= displayedWords.size && displayedWords.isNotEmpty()) {
            currentSelectedIndex = displayedWords.size - 1
        }

        refreshWordGrid()
        updateCurrentSelection()
        updateProgress()

        if (displayedWords.isEmpty() && pendingQueue.isEmpty()) {
            Toast.makeText(this, "本轮所有单词已学习完成！", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onStrange() {
        if (currentSelectedIndex >= displayedWords.size) return
        val item = displayedWords[currentSelectedIndex]
        item.deltaStrangeness++
        hasUnsavedChanges = true

        // 从显示列表移除
        displayedWords.removeAt(currentSelectedIndex)
        // 放回待显示队列末尾
        pendingQueue.add(item)

        // 补充新单词
        if (pendingQueue.isNotEmpty()) {
            displayedWords.add(pendingQueue.removeAt(0))
        }

        // 调整选中索引
        if (currentSelectedIndex >= displayedWords.size && displayedWords.isNotEmpty()) {
            currentSelectedIndex = displayedWords.size - 1
        }

        refreshWordGrid()
        updateCurrentSelection()
        updateProgress()
    }

    private fun updateProgress() {
        val remaining = pendingQueue.size + displayedWords.size
        tvProgress.text = "剩余: $remaining"
    }

    private fun saveChanges() {
        if (!hasUnsavedChanges) {
            Toast.makeText(this, "没有需要更新的数据", Toast.LENGTH_SHORT).show()
            return
        }
        val changedItems = allLoadedWords.filter { it.deltaFamiliarity != 0 || it.deltaStrangeness != 0 }
        if (changedItems.isEmpty()) {
            Toast.makeText(this, "没有变化", Toast.LENGTH_SHORT).show()
            return
        }
        dbHelper.batchUpdateMemory(changedItems)
        hasUnsavedChanges = false
        Toast.makeText(this, "已更新 ${changedItems.size} 个单词", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        if (hasUnsavedChanges) saveChanges()
    }

    override fun onBackPressed() {
        if (hasUnsavedChanges) saveChanges()
        super.onBackPressed()
    }
}