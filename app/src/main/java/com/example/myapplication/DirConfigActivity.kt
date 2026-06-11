package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.*
import java.io.File

class DirConfigActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var btnBrowse: Button
    private lateinit var dbHelper: DictDbHelper
    private val dirs = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    companion object {
        private const val REQUEST_CODE_DIR = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dir_config)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        listView = findViewById(R.id.list_dirs)
        btnBrowse = findViewById(R.id.btn_browse)

        dbHelper = (application as MyApplication).dbHelper

        // 加载已有目录
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, dirs)
        listView.adapter = adapter

        // 长按删除
        listView.setOnItemLongClickListener { _, _, position, _ ->
            dirs.removeAt(position)
            adapter.notifyDataSetChanged()
            true
        }

        // 浏览按钮：启动 SAF 目录选择器
        btnBrowse.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, REQUEST_CODE_DIR)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_DIR && resultCode == RESULT_OK && data != null) {
            val treeUri = data.data
            if (treeUri != null) {
                // 尝试将 content URI 转换为真实文件路径
                val realPath = getRealPathFromURI(treeUri)
                if (realPath != null) {
                    if (!dirs.contains(realPath)) {
                        dirs.add(realPath)
                        adapter.notifyDataSetChanged()
                    } else {
                        ToastUtil.show(this, "该目录已存在")
                    }
                } else {
                    ToastUtil.show(this, "无法识别该目录，请手动输入路径")
                }
            }
        }
    }

    /**
     * 将 SAF 返回的 content URI 转换为可读的文件路径
     * 仅适用于外部存储，对于其他存储可能返回 null
     */
    private fun getRealPathFromURI(uri: Uri): String? {
        // 尝试通过 DocumentsContract 解析
        if (DocumentsContract.isTreeUri(uri)) {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            // docId 格式类似 "primary:Pictures" 或 "XXXX-XXXX:Pictures"
            val split = docId.split(":")
            if (split.size >= 2) {
                val type = split[0]
                val relativePath = split[1]
                // 判断是否为内置存储（primary）
                if ("primary".equals(type, ignoreCase = true)) {
                    return "/storage/emulated/0/$relativePath"
                } else {
                    // 外置 SD 卡，路径为 /storage/XXXX-XXXX/relativePath
                    return "/storage/$type/$relativePath"
                }
            }
        }
        // 备用：直接使用 uri 的路径（可能不可靠）
        return uri.path?.let { path ->
            // 去掉 "/tree/" 前缀
            if (path.startsWith("/tree/")) {
                val sub = path.substringAfter("/tree/")
                val decoded = java.net.URLDecoder.decode(sub, "UTF-8")
                // 将 "primary:" 替换为 "/storage/emulated/0/"
                if (decoded.startsWith("primary:")) {
                    "/storage/emulated/0/${decoded.removePrefix("primary:")}"
                } else {
                    null
                }
            } else null
        }
    }

}