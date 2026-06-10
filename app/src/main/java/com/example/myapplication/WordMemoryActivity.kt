package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.util.*

class WordMemoryActivity : Activity() {

    private lateinit var layoutWordGrid: LinearLayout
    private lateinit var tvCurrentWord: TextView
    private lateinit var btnFamiliar: Button
    private lateinit var btnStrange: Button
    private lateinit var btnUpdate: Button
    private lateinit var btnSwitch: Button
    private lateinit var tvProgress: TextView

    private lateinit var dbHelper: DictDbHelper

    private val TOTAL_WORDS = 100
    private val DISPLAY_COUNT = 20

    private var allLoadedWords = mutableListOf<WordMemoryItem>()
    private var pendingQueue = mutableListOf<WordMemoryItem>()
    private var displayedWords = mutableListOf<WordMemoryItem>()
    private var currentSelectedIndex = 0
    private var hasUnsavedChanges = false

    private val rowViews = mutableListOf<LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word_memory)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        layoutWordGrid = findViewById(R.id.layout_word_grid)
        tvCurrentWord = findViewById(R.id.tv_current_word)
        btnFamiliar = findViewById(R.id.btn_familiar)
        btnStrange = findViewById(R.id.btn_strange)
        btnUpdate = findViewById(R.id.btn_update)
        btnSwitch = findViewById(R.id.btn_switch)
        tvProgress = findViewById(R.id.tv_progress)

        dbHelper = (application as MyApplication).dbHelper

        val category = intent.getStringExtra("CATEGORY") ?: "CET4"
        loadWords(category)

        btnFamiliar.setOnClickListener { onFamiliar() }
        btnStrange.setOnClickListener { onStrange() }
        btnUpdate.setOnClickListener { saveChanges() }
        btnSwitch.setOnClickListener { reshuffle() }

        // 点击当前选中单词跳转到主页面查词
        tvCurrentWord.setOnClickListener {
            if (currentSelectedIndex < displayedWords.size) {
                val word = displayedWords[currentSelectedIndex].word
                val intent = Intent(this@WordMemoryActivity, MainActivity::class.java)
                intent.putExtra("QUERY_WORD", word)
                intent.putExtra("FROM_MEMORY", true)
                startActivity(intent)
            }
        }

    }

    private fun loadWords(category: String) {
        allLoadedWords = dbHelper.loadWordsForMemory(category, TOTAL_WORDS)
        if (allLoadedWords.isEmpty()) {
            ToastUtil.show(this, "该分类暂无单词，请先导入")
            finish()
            return
        }
        allLoadedWords.shuffle()
        displayedWords.clear()
        for (i in 0 until minOf(DISPLAY_COUNT, allLoadedWords.size)) {
            displayedWords.add(allLoadedWords[i])
        }
        pendingQueue.clear()
        for (i in DISPLAY_COUNT until allLoadedWords.size) {
            pendingQueue.add(allLoadedWords[i])
        }

        buildWordGrid()
        if (displayedWords.isNotEmpty()) {
            currentSelectedIndex = 0
            updateCurrentSelection()
        }
        updateProgress()
    }

    private fun reshuffle() {
        if (allLoadedWords.isEmpty()) return
        val shuffled = allLoadedWords.shuffled()
        displayedWords.clear()
        for (i in 0 until minOf(DISPLAY_COUNT, shuffled.size)) {
            displayedWords.add(shuffled[i])
        }
        pendingQueue.clear()
        for (i in DISPLAY_COUNT until shuffled.size) {
            pendingQueue.add(shuffled[i])
        }
        currentSelectedIndex = 0
        refreshWordGrid()
        updateCurrentSelection()
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
                tvWord.textSize = 35f
                tvWord.setTextColor(Color.BLACK)
                tvWord.gravity = Gravity.CENTER
                tvWord.setPadding(10, 10, 10, 10)

                // 点击跳转到主页面查词
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
            tvCurrentWord.text = displayedWords[currentSelectedIndex].word
            btnFamiliar.isEnabled = true
            btnStrange.isEnabled = true
        } else {
            tvCurrentWord.text = ""
            btnFamiliar.isEnabled = false
            btnStrange.isEnabled = false
        }
    }

    private fun onFamiliar() {
        if (currentSelectedIndex >= displayedWords.size) return
        val item = displayedWords[currentSelectedIndex]
        item.deltaFamiliarity++
        hasUnsavedChanges = true

        displayedWords.removeAt(currentSelectedIndex)
        pendingQueue.remove(item)

        if (pendingQueue.isNotEmpty()) {
            displayedWords.add(currentSelectedIndex, pendingQueue.removeAt(0))
        }

        if (currentSelectedIndex >= displayedWords.size && displayedWords.isNotEmpty()) {
            currentSelectedIndex = displayedWords.size - 1
        }

        refreshWordGrid()
        updateCurrentSelection()
        updateProgress()

        if (displayedWords.isEmpty() && pendingQueue.isEmpty()) {
            ToastUtil.show(this, "本轮所有单词已学习完成")
        }
    }

    private fun onStrange() {
        if (currentSelectedIndex >= displayedWords.size) return
        val item = displayedWords[currentSelectedIndex]
        item.deltaStrangeness++
        hasUnsavedChanges = true

        displayedWords.removeAt(currentSelectedIndex)
        pendingQueue.add(item)

        if (pendingQueue.isNotEmpty()) {
            displayedWords.add(currentSelectedIndex, pendingQueue.removeAt(0))
        }

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
            ToastUtil.show(this, "没有需要更新的数据")
            return
        }
        val changedItems = allLoadedWords.filter { it.deltaFamiliarity != 0 || it.deltaStrangeness != 0 }
        if (changedItems.isEmpty()) {
            ToastUtil.show(this, "没有变化")
            return
        }
        dbHelper.batchUpdateMemory(changedItems)
        hasUnsavedChanges = false
        ToastUtil.show(this, "已更新")
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