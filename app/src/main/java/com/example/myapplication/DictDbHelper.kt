package com.example.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

class DictDbHelper(context: Context) {
    private val TAG = "DictDbHelper"

    @SuppressLint("SdCardPath")
    private val DB_FULL_PATH = "/sdcard/dicts_sqlite_diy_/dict.db"

    private var db: SQLiteDatabase? = null
    @Volatile
    var isReady = false
        private set

    init {
        Thread {
            val dbFile = File(DB_FULL_PATH)
            Log.d(TAG, "数据库路径: $DB_FULL_PATH")
            Log.d(TAG, "文件存在: ${dbFile.exists()}, 大小: ${dbFile.length()}")
            Log.d(TAG, "文件可写: ${dbFile.canWrite()}")

            if (!dbFile.exists()) {
                Log.e(TAG, "词典数据库文件不存在")
                return@Thread
            }

            try {
                db = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE
                )
                Log.d(TAG, "数据库以读写模式打开成功")

                // 创建历史记录表（如果不存在）
                db?.execSQL("CREATE TABLE IF NOT EXISTS search_history(id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT UNIQUE)")
                Log.d(TAG, "历史表创建/确认完成")

                isReady = true
                Log.d(TAG, "词典数据库加载完成")
            } catch (e: Exception) {
                Log.e(TAG, "加载失败: ${e.message}", e)
            }
        }.start()
    }

    // ======================== 词典查询 ========================
    fun queryWordWithDict(word: String): MutableList<String> {
        val resultList = mutableListOf<String>()
        if (!isReady || db == null) return resultList
        val lowerWord = word.lowercase()

        val cursor = db!!.rawQuery(
            "SELECT dict_id, explain, word_tag FROM word_dict WHERE word = ?",
            arrayOf(lowerWord)
        )
        while (cursor.moveToNext()) {
            val name = cursor.getString(0)
            val explain = cursor.getString(1)
            val wordtagg = cursor.getString(2)
            resultList.add("【$name】  $wordtagg \n$explain")
        }
        cursor.close()
        return resultList
    }

    // ======================== 历史记录操作 ========================

    fun addHistory(word: String) {
        if (!isReady || db == null) return
        try {
            val newtime = System.currentTimeMillis()  // 当前毫秒时间戳
            db!!.execSQL("INSERT OR IGNORE INTO search_history(word,create_time) VALUES(?,?)", arrayOf(word,newtime))
        } catch (e: Exception) {
            Log.e(TAG, "添加历史记录失败", e)
        }
    }

    fun getHistoryByPage(pageIndex: Int, pageSize: Int): MutableList<String> {
        val list = mutableListOf<String>()
        if (!isReady || db == null) return list
        val offset = pageIndex * pageSize
        val cursor = db!!.rawQuery(
            "SELECT word FROM search_history ORDER BY id DESC LIMIT ?, ?",
            arrayOf(offset.toString(), pageSize.toString())
        )
        while (cursor.moveToNext()) {
            list.add(cursor.getString(0))
        }
        cursor.close()
        return list
    }

    fun getHistoryTotalCount(): Int {
        if (!isReady || db == null) return 0
        val cursor = db!!.rawQuery("SELECT COUNT(*) FROM search_history", null)
        var total = 0
        if (cursor.moveToFirst()) {
            total = cursor.getInt(0)
        }
        cursor.close()
        return total
    }

    fun clearAllHistory() {
        if (!isReady || db == null) return
        try {
            db!!.execSQL("DELETE FROM search_history")
        } catch (e: Exception) {
            Log.e(TAG, "清除历史记录失败", e)
        }
    }

    fun getAllHistory(): MutableList<String> {
        val list = mutableListOf<String>()
        if (!isReady || db == null) return list
        val cursor = db!!.rawQuery("SELECT word FROM search_history ORDER BY id DESC", null)
        while (cursor.moveToNext()) {
            list.add(cursor.getString(0))
        }
        cursor.close()
        return list
    }

    fun close() {
        db?.close()
        db = null
        isReady = false
    }
}