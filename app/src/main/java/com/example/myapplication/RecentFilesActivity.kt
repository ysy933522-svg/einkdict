package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.net.Uri
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
                val pathOrUri = paths[position]
                if (pathOrUri.startsWith("content://")) {
                    // 直接打开 URI
                    val intent = Intent(this, PdfReaderActivity::class.java)
                    intent.putExtra("pdf_uri", pathOrUri)
                    startActivity(intent)
                } else {
                    val file = File(pathOrUri)
                    if (file.exists()) {
                        val intent = Intent(this, PdfReaderActivity::class.java)
                        intent.putExtra("pdf_path", file.absolutePath)
                        startActivity(intent)
                    } else {
                        ToastUtil.show(this, "文件已不存在")
                    }
                }
            }
        }
    }
    /** 从 URI 提取文件名 */
    public fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }
    private fun loadRecentFiles() {
        val paths = getRecentPaths(this)
        if (paths.isEmpty()) {
            listView.adapter = ArrayAdapter(this, R.layout.list_item_file, arrayOf("暂无最近打开的文件"))
            btnClear.isEnabled = false
        } else {
            val displayNames = paths.map { path ->
                if (path.startsWith("content://")) {
                    // 尝试获取文件名
                    val fileName = getFileNameFromUri(Uri.parse(path))
                    fileName ?: "[分享文件]"
                } else {
                    val file = File(path)
                    if (file.exists()) file.name else "[已删除] " + file.name
                }
            }
            listView.adapter = ArrayAdapter(this, R.layout.list_item_file, displayNames)
            btnClear.isEnabled = true
        }
    }
    companion object {
        private const val PREFS_NAME = "recent_files"
        private const val KEY_PATHS = "paths"
        private const val MAX_RECORDS = 20

        fun saveRecentPath(context: Context, path: String, fileName: String) {
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
