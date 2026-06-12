package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.io.File

class RecentFilesActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var btnClear: Button
    private lateinit var btnBack: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recent_files)

        // 全屏沉浸
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )

        listView = findViewById(R.id.listView)
        btnClear = findViewById(R.id.btnClear)
        btnBack = findViewById(R.id.btnBack)

        loadRecentFiles()

        btnClear.setOnClickListener {
            clearRecentFiles(this)
            loadRecentFiles()
        }

        btnBack.setOnClickListener { finish() }

        listView.setOnItemClickListener { _, _, position, _ ->
            val paths = getRecentPaths(this)
            if (position < paths.size) {
                val file = File(paths[position])
                if (file.exists()) {
                    val intent = Intent(this, PdfReaderActivity::class.java)
                    intent.putExtra("pdf_path", file.absolutePath)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "文件已不存在", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadRecentFiles() {
        val paths = getRecentPaths(this)
        if (paths.isEmpty()) {
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, arrayOf("暂无最近打开的文件"))
            btnClear.isEnabled = false
        } else {
            val displayNames = paths.map { path ->
                val file = File(path)
                if (file.exists()) file.name else "[已删除] " + file.name
            }
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayNames)
            btnClear.isEnabled = true
        }
    }
    companion object {
        private const val PREFS_NAME = "recent_files"
        private const val KEY_PATHS = "paths"
        private const val MAX_RECORDS = 20

        fun saveRecentPath(context: Context, path: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // 读取现有的 JSON 字符串
            val jsonStr = try {
                prefs.getString(KEY_PATHS, "[]") ?: "[]"
            } catch (e: ClassCastException) {
                // 如果旧数据是 HashSet，清除并重置
                prefs.edit().remove(KEY_PATHS).apply()
                "[]"
            }
            val arr = JSONArray(jsonStr)
            // 移除重复项
            val tempList = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                tempList.add(arr.getString(i))
            }
            tempList.remove(path)
            tempList.add(0, path) // 最新的放在最前面
            // 限制数量
            val finalList = tempList.take(MAX_RECORDS)
            val newArr = JSONArray(finalList)
            prefs.edit().putString(KEY_PATHS, newArr.toString()).apply()
        }

        fun getRecentPaths(context: Context): List<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = try {
                prefs.getString(KEY_PATHS, "[]") ?: "[]"
            } catch (e: ClassCastException) {
                // 如果旧数据是 HashSet，清除并返回空列表
                prefs.edit().remove(KEY_PATHS).apply()
                "[]"
            }
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            return list
        }

        fun clearRecentFiles(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PATHS, "[]").apply()
        }
    }
}