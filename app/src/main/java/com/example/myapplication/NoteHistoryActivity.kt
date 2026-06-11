package com.example.myapplication

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class NoteHistoryActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var tvPageInfo: TextView

    private lateinit var dbHelper: DictDbHelper

    private val PAGE_SIZE = 10
    private var currentPage = 0
    private var totalPages = 0
    private val notes = mutableListOf<NoteItem>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_history)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        listView = findViewById(R.id.list_notes)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        tvPageInfo = findViewById(R.id.tv_page_info)

        dbHelper = (application as MyApplication).dbHelper

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter

        // 在 btnNext.setOnClickListener 之后添加
        val btnBack = findViewById<Button>(R.id.btn_back)
        btnBack.setOnClickListener {
            finish()
        }

        // 长按删除
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (position < notes.size) {
                val note = notes[position]
                dbHelper.deleteNote(note.id)
                loadPage(currentPage)
            }
            true
        }

        // 点击修改
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position < notes.size) {
                val note = notes[position]
                // 弹出对话框修改
                val editText = EditText(this)
                editText.setText(note.content)
                editText.setSelection(note.content.length)
                AlertDialog.Builder(this)
                    .setTitle("修改记事")
                    .setView(editText)
                    .setPositiveButton("保存") { _, _ ->
                        val newContent = editText.text.toString().trim()
                        if (newContent.isNotEmpty()) {
                            dbHelper.updateNote(note.id, newContent)
                            loadPage(currentPage)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        btnPrev.setOnClickListener {
            if (currentPage > 0) {
                currentPage--
                loadPage(currentPage)
            }
        }

        btnNext.setOnClickListener {
            if (currentPage < totalPages - 1) {
                currentPage++
                loadPage(currentPage)
            }
        }

        waitForDbAndLoad()
    }

    private fun waitForDbAndLoad() {
        if (dbHelper.isMemoryReady) {
            loadPage(0)
        } else {
            Handler(Looper.getMainLooper()).postDelayed({
                waitForDbAndLoad()
            }, 200)
        }
    }

    private fun loadPage(page: Int) {
        val totalCount = dbHelper.getNoteTotalCount()
        totalPages = if (totalCount == 0) 0 else (totalCount + PAGE_SIZE - 1) / PAGE_SIZE
        if (page >= totalPages && totalPages > 0) {
            currentPage = totalPages - 1
        } else {
            currentPage = page
        }

        notes.clear()
        notes.addAll(dbHelper.getNotesByPage(currentPage, PAGE_SIZE))

        val displayList = notes.map { note ->
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val timeStr = dateFormat.format(Date(note.createTime))
            "[$timeStr] ${note.content.take(30)}${if (note.content.length > 30) "..." else ""}"
        }
        adapter.clear()
        adapter.addAll(displayList)
        adapter.notifyDataSetChanged()

        tvPageInfo.text = "${currentPage + 1} / $totalPages"
    }
}