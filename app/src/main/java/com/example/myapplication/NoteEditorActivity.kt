package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText

class NoteEditorActivity : Activity() {

    private lateinit var etContent: EditText
    private lateinit var btnSave: Button
    private lateinit var btnViewHistory: Button
    private lateinit var dbHelper: DictDbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        etContent = findViewById(R.id.et_note_content)
        btnSave = findViewById(R.id.btn_save)
        btnViewHistory = findViewById(R.id.btn_view_history)


        // 在 btnViewHistory.setOnClickListener 之后添加
        val btnBack = findViewById<Button>(R.id.btn_back)
        btnBack.setOnClickListener {
            finish()
        }



        dbHelper = (application as MyApplication).dbHelper

        btnSave.setOnClickListener {
            val content = etContent.text.toString().trim()
            if (content.isEmpty()) {
                ToastUtil.show(this, "内容不能为空")
                return@setOnClickListener
            }
            val id = dbHelper.insertNote(content)
            if (id != -1L) {
                ToastUtil.show(this, "保存成功")
                etContent.text.clear()
            } else {
                ToastUtil.show(this, "保存失败")
            }
        }

        btnViewHistory.setOnClickListener {
            val intent = Intent(this, NoteHistoryActivity::class.java)
            startActivity(intent)
        }
    }
}