package com.example.myapplication

import android.annotation.SuppressLint
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

            // 检查是否需要插入默认单词
            val cursor = memoryDb?.rawQuery("SELECT COUNT(*) FROM word_memory", null)
            var isEmpty = true
            cursor?.let {
                if (it.moveToFirst() && it.getInt(0) > 0) isEmpty = false
                it.close()
            }
            if (isEmpty) {
                Log.d(TAG, "word_memory 表为空，插入默认单词数据")
                insertDefaultWords()
            }

            isMemoryReady = true
            Log.d(TAG, "记忆/历史数据库加载完成")
        } catch (e: Exception) {
            Log.e(TAG, "记忆/历史数据库加载失败", e)
        }
    }

    private fun insertDefaultWords() {
        val defaultWords = listOf(
            "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract", "accept", "access",
            "accident", "account", "achieve", "acknowledge", "acquire", "adapt", "address", "adjust", "admire", "admit",
            "adopt", "advance", "advantage", "adventure", "affair", "affect", "afford", "afterward", "agency", "aggressive",
            "agree", "agriculture", "allocate", "alternative", "ambition", "analyze", "announce", "annual", "anxiety", "apparent",
            "appeal", "appear", "apply", "approach", "appropriate", "argue", "arrange", "article", "aspect", "assess",
            "assign", "associate", "assume", "atmosphere", "attach", "attempt", "attend", "attitude", "attract", "authority",
            "available", "average", "avoid", "aware", "balance", "barrier", "behave", "benefit", "bitter", "blame",
            "blank", "bother", "boundary", "branch", "brand", "breath", "brief", "broad", "budget", "burden",
            "calculate", "campaign", "capable", "capacity", "capture", "career", "carve", "cast", "category", "cease",
            "celebrate", "challenge", "character"
        )

        try {
            memoryDb?.beginTransaction()
            for (word in defaultWords) {
                memoryDb?.execSQL(
                    "INSERT OR IGNORE INTO word_memory(word, category) VALUES(?, 'CET4')",
                    arrayOf(word)
                )
            }
            memoryDb?.setTransactionSuccessful()
            Log.d(TAG, "默认单词插入完成，共 ${defaultWords.size} 个")
        } catch (e: Exception) {
            Log.e(TAG, "插入默认单词失败", e)
        } finally {
            memoryDb?.endTransaction()
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
            "SELECT id, word FROM word_memory WHERE category = ? ORDER BY RANDOM() LIMIT ?",
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
}

/** 单词记忆数据类 */
data class WordMemoryItem(
    val id: Long,
    val word: String,
    var deltaFamiliarity: Int = 0,
    var deltaStrangeness: Int = 0
)