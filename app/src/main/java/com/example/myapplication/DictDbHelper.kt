package com.example.myapplication

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

class DictDbHelper(context: Context) {
    private val TAG = "DictDbHelper"

    // 词典库路径（只读）
    @SuppressLint("SdCardPath")
    private val DB_DICT_PATH = "/sdcard/dicts_sqlite_diy_/dict.db"

    // 记忆/历史库路径（可读写）
    @SuppressLint("SdCardPath")
    private val DB_MEMORY_PATH = "/sdcard/dicts_sqlite_diy_/memory.db"

    private var dictDb: SQLiteDatabase? = null   // 词典数据库（只读）
    private var memoryDb: SQLiteDatabase? = null // 记忆/历史数据库（读写）

    @Volatile
    var isDictReady = false
        private set
    @Volatile
    var isMemoryReady = false
        private set

    init {
        // 并行加载两个数据库
        Thread { loadDictDb() }.start()
        Thread { loadMemoryDb() }.start()
    }

    // ---------- 词典数据库（只读） ----------
    private fun loadDictDb() {
        val dbFile = File(DB_DICT_PATH)
        Log.d(TAG, "词典库路径: $DB_DICT_PATH")
        Log.d(TAG, "文件存在: ${dbFile.exists()}, 大小: ${dbFile.length()}")

        if (!dbFile.exists()) {
            Log.e(TAG, "词典数据库文件不存在")
            return
        }

        try {
            dictDb = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            isDictReady = true
            Log.d(TAG, "词典数据库加载完成")
        } catch (e: Exception) {
            Log.e(TAG, "词典数据库加载失败", e)
        }
    }

    // ---------- 记忆/历史数据库（读写） ----------
    private fun loadMemoryDb() {
        val dbFile = File(DB_MEMORY_PATH)
        // 确保目录存在
        dbFile.parentFile?.mkdirs()
        Log.d(TAG, "记忆库路径: $DB_MEMORY_PATH")

        try {
            memoryDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            // 创建历史记录表
            memoryDb?.execSQL("CREATE TABLE IF NOT EXISTS search_history(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "word TEXT UNIQUE, " +
                    "create_time INTEGER DEFAULT 0)")
            // 创建单词记忆表
            memoryDb?.execSQL("CREATE TABLE IF NOT EXISTS word_memory(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "word TEXT NOT NULL UNIQUE, " +
                    "familiarity INTEGER DEFAULT 0, " +
                    "strangeness INTEGER DEFAULT 0, " +
                    "category TEXT DEFAULT '')")



            // 图片记忆表（新增）
            memoryDb?.execSQL("CREATE TABLE IF NOT EXISTS image_memory(" +
                    "path_hash TEXT PRIMARY KEY, " +
                    "file_path TEXT NOT NULL, " +
                    "familiarity INTEGER DEFAULT 0, " +
                    "strangeness INTEGER DEFAULT 0, " +
                    "directory TEXT DEFAULT '')")

// 记事本表
            memoryDb?.execSQL("CREATE TABLE IF NOT EXISTS note(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "content TEXT NOT NULL, " +
                    "create_time INTEGER DEFAULT 0)")

            isMemoryReady = true
            Log.d(TAG, "记忆/历史数据库加载完成")
        } catch (e: Exception) {
            Log.e(TAG, "记忆/历史数据库加载失败", e)
        }
    }



    // ---------- 对外接口 ----------

    /** 词典查询（只读） */
    fun queryWordWithDict(word: String): MutableList<String> {
        val resultList = mutableListOf<String>()
        if (!isDictReady || dictDb == null) return resultList
        val lowerWord = word.lowercase()
        val cursor = dictDb!!.rawQuery(
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

    /** 添加历史记录 */
    fun addHistory(word: String) {
        if (!isMemoryReady || memoryDb == null) return
        try {
            val newtime = System.currentTimeMillis()
            memoryDb!!.beginTransaction()
            memoryDb!!.execSQL(
                "INSERT OR IGNORE INTO search_history(word, create_time) VALUES(?, ?)",
                arrayOf(word, newtime)
            )
            memoryDb!!.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "添加历史记录失败", e)
        } finally {
            memoryDb!!.endTransaction()
        }
    }

    /** 分页查询历史记录 */
    fun getHistoryByPage(pageIndex: Int, pageSize: Int): MutableList<String> {
        val list = mutableListOf<String>()
        if (!isMemoryReady || memoryDb == null) return list
        val offset = pageIndex * pageSize
        val cursor = memoryDb!!.rawQuery(
            "SELECT word FROM search_history ORDER BY id DESC LIMIT ?, ?",
            arrayOf(offset.toString(), pageSize.toString())
        )
        while (cursor.moveToNext()) list.add(cursor.getString(0))
        cursor.close()
        return list
    }

    /** 历史总条数 */
    fun getHistoryTotalCount(): Int {
        if (!isMemoryReady || memoryDb == null) return 0
        val cursor = memoryDb!!.rawQuery("SELECT COUNT(*) FROM search_history", null)
        var total = 0
        if (cursor.moveToFirst()) total = cursor.getInt(0)
        cursor.close()
        return total
    }

    /** 清除历史 */
    fun clearAllHistory() {
        if (!isMemoryReady || memoryDb == null) return
        try {
            memoryDb!!.execSQL("DELETE FROM search_history")
        } catch (e: Exception) {
            Log.e(TAG, "清除历史失败", e)
        }
    }

    /** 获取所有历史（用于导出） */
    fun getAllHistory(): MutableList<String> {
        val list = mutableListOf<String>()
        if (!isMemoryReady || memoryDb == null) return list
        val cursor = memoryDb!!.rawQuery("SELECT word FROM search_history ORDER BY id DESC", null)
        while (cursor.moveToNext()) list.add(cursor.getString(0))
        cursor.close()
        return list
    }

    /** 加载单词记忆列表 */
    fun loadWordsForMemory(category: String, limit: Int = 300): MutableList<WordMemoryItem> {
        val list = mutableListOf<WordMemoryItem>()
        if (!isMemoryReady || memoryDb == null) return list
        val cursor = memoryDb!!.rawQuery(
            "SELECT id, word FROM word_memory WHERE category = ? ORDER BY strangeness desc  LIMIT ?",
            arrayOf(category, limit.toString())
        )
        while (cursor.moveToNext()) {
            list.add(WordMemoryItem(cursor.getLong(0), cursor.getString(1)))
        }
        cursor.close()
        return list
    }

    /** 批量更新单词记忆分数 */
    fun batchUpdateMemory(changes: List<WordMemoryItem>) {
        if (!isMemoryReady || memoryDb == null || changes.isEmpty()) return
        memoryDb!!.beginTransaction()
        try {
            for (item in changes) {
                if (item.deltaFamiliarity != 0 || item.deltaStrangeness != 0) {
                    memoryDb!!.execSQL(
                        "UPDATE word_memory SET familiarity = familiarity + ?, strangeness = strangeness + ? WHERE id = ?",
                        arrayOf(item.deltaFamiliarity, item.deltaStrangeness, item.id)
                    )
                }
            }
            memoryDb!!.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "批量更新失败", e)
        } finally {
            memoryDb!!.endTransaction()
        }
    }

    /** 关闭所有数据库 */
    fun close() {
        dictDb?.close()
        memoryDb?.close()
        isDictReady = false
        isMemoryReady = false
    }





    // 历史记录缓存（内存列表）
    private val pendingHistory = mutableListOf<String>()
    private val pendingLock = Any()  // 用于线程安全

    /**
     * 添加历史记录（仅加入缓存，不立即写库）
     */
    fun cacheHistory(word: String) {
        synchronized(pendingLock) {
            if (!pendingHistory.contains(word)) {  // 避免重复
                pendingHistory.add(word)
            }
        }
    }

    /**
     * 将缓存中的历史记录批量写入数据库（事务）
     */
    fun flushHistory() {
        if (!isMemoryReady || memoryDb == null) return
        val wordsToInsert: List<String>
        synchronized(pendingLock) {
            if (pendingHistory.isEmpty()) return
            wordsToInsert = pendingHistory.toList()
            pendingHistory.clear()
        }
        try {
            memoryDb!!.beginTransaction()
            for (word in wordsToInsert) {
                memoryDb!!.execSQL(
                    "INSERT OR IGNORE INTO search_history(word, create_time) VALUES(?, ?)",
                    arrayOf(word, System.currentTimeMillis())
                )
            }
            memoryDb!!.setTransactionSuccessful()
            Log.d(TAG, "批量写入历史记录 ${wordsToInsert.size} 条")
        } catch (e: Exception) {
            Log.e(TAG, "批量写入历史失败", e)
        } finally {
            memoryDb!!.endTransaction()
        }
    }

    /**
     * 从 word_memory 表中随机获取一个单词（指定分类可选）
     */
    fun getRandomWordFromMemory(category: String? = null): String? {
        if (!isMemoryReady || memoryDb == null) return null
        val sql = if (category != null) {
            "SELECT word FROM word_memory WHERE category = ? ORDER BY strangeness desc LIMIT 1"
        } else {
            "SELECT word FROM word_memory ORDER BY strangeness desc LIMIT 1"
        }
        val cursor = if (category != null) {
            memoryDb!!.rawQuery(sql, arrayOf(category))
        } else {
            memoryDb!!.rawQuery(sql, null)
        }
        var word: String? = null
        if (cursor.moveToFirst()) {
            word = cursor.getString(0)
        }
        cursor.close()
        return word
    }



    // ---------- 图片记忆操作 ----------
    /** 获取图片的记忆分数 */
    fun getImageScore(pathHash: String): Pair<Int, Int>? {
        if (!isMemoryReady || memoryDb == null) return null
        val cursor = memoryDb!!.rawQuery(
            "SELECT familiarity, strangeness FROM image_memory WHERE path_hash = ?",
            arrayOf(pathHash)
        )
        var result: Pair<Int, Int>? = null
        if (cursor.moveToFirst()) {
            result = Pair(cursor.getInt(0), cursor.getInt(1))
        }
        cursor.close()
        return result
    }

    /** 批量更新图片分数（事务） */
    fun batchUpdateImageScores(scores: Map<String, Pair<Int, Int>>) {
        if (!isMemoryReady || memoryDb == null || scores.isEmpty()) return
        memoryDb!!.beginTransaction()
        try {
            for ((pathHash, pair) in scores) {
                val (familiarity, strangeness) = pair
                memoryDb!!.execSQL(
                    "UPDATE image_memory SET familiarity = ?, strangeness = ? WHERE path_hash = ?",
                    arrayOf(familiarity, strangeness, pathHash)
                )
            }
            memoryDb!!.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "批量更新图片分数失败", e)
        } finally {
            memoryDb!!.endTransaction()
        }
    }

    /** 插入或更新一条图片记录（用于首次扫描时记录路径） */
    fun insertImageRecord(pathHash: String, filePath: String, directory: String) {
        if (!isMemoryReady || memoryDb == null) return
        memoryDb!!.execSQL(
            "INSERT OR IGNORE INTO image_memory(path_hash, file_path, directory) VALUES(?, ?, ?)",
            arrayOf(pathHash, filePath, directory)
        )
    }




    // ---------- 记事本操作 ----------
    fun insertNote(content: String): Long {
        if (!isMemoryReady || memoryDb == null) return -1
        val values = ContentValues().apply {
            put("content", content)
            put("create_time", System.currentTimeMillis())
        }
        return memoryDb!!.insert("note", null, values)
    }

    fun getNotesByPage(pageIndex: Int, pageSize: Int): List<NoteItem> {
        val list = mutableListOf<NoteItem>()
        if (!isMemoryReady || memoryDb == null) return list
        val offset = pageIndex * pageSize
        val cursor = memoryDb!!.rawQuery(
            "SELECT id, content, create_time FROM note ORDER BY id DESC LIMIT ?, ?",
            arrayOf(offset.toString(), pageSize.toString())
        )
        while (cursor.moveToNext()) {
            list.add(NoteItem(
                id = cursor.getLong(0),
                content = cursor.getString(1),
                createTime = cursor.getLong(2)
            ))
        }
        cursor.close()
        return list
    }

    /** 获取记事本总条数 */
    fun getNoteTotalCount(): Int {
        if (!isMemoryReady || memoryDb == null) return 0
        val cursor = memoryDb!!.rawQuery("SELECT COUNT(*) FROM note", null)
        var count = 0
        if (cursor.moveToFirst()) count = cursor.getInt(0)
        cursor.close()
        return count
    }

    /** 删除单条记事 */
    fun deleteNote(id: Long) {
        if (!isMemoryReady || memoryDb == null) return
        memoryDb!!.delete("note", "id = ?", arrayOf(id.toString()))
    }

    /** 更新记事内容 */
    fun updateNote(id: Long, newContent: String) {
        if (!isMemoryReady || memoryDb == null) return
        val values = ContentValues().apply {
            put("content", newContent)
        }
        memoryDb!!.update("note", values, "id = ?", arrayOf(id.toString()))
    }



}

data class NoteItem(
    val id: Long,
    val content: String,
    val createTime: Long
)

/** 单词记忆数据类 */
data class WordMemoryItem(
    val id: Long,
    val word: String,
    var deltaFamiliarity: Int = 0,
    var deltaStrangeness: Int = 0
)