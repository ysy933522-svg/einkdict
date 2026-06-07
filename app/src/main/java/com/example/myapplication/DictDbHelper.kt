import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

class DictDbHelper(private val context: Context) {
    private val TAG = "DictDbHelper"
    private val DB_FULL_PATH = "/sdcard/dicts_sqlite_diy_/dict.db"
    private var db: SQLiteDatabase? = null
    var isReady = false
        private set

    init {
        Thread {
            val dbFile = File(DB_FULL_PATH)
            Log.d(TAG, "数据库路径: $DB_FULL_PATH")
            Log.d(TAG, "文件存在: ${dbFile.exists()}, 大小: ${dbFile.length()}")

            if (!dbFile.exists()) {
                Log.e(TAG, "数据库文件不存在，请检查路径！")
                return@Thread
            }

            try {
                db = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )
                isReady = true
                Log.d(TAG, "数据库异步打开成功")
            } catch (e: Exception) {
                Log.e(TAG, "打开数据库失败", e)
            }
        }.start()
    }

    fun queryWordWithDict(word: String): MutableList<String> {
        val resultList = mutableListOf<String>()
        if (!isReady || db == null) return resultList

        val lowerWord = word.lowercase()   // 转为小写

        val cursor = db!!.rawQuery("SELECT dict_name, explain FROM word_dict WHERE word = ?", arrayOf(lowerWord))
        while (cursor.moveToNext()) {
            val name = cursor.getString(0)
            val explain = cursor.getString(1)
            // 单条格式：词典名 + 释义
            resultList.add("【$name】\n$explain")
        }
        cursor.close()
        return resultList
    }

    private val HISTORY_DB = "history_db"
    private fun getHistoryDb(ctx: Context): SQLiteDatabase {
        return ctx.openOrCreateDatabase(HISTORY_DB, Context.MODE_PRIVATE, null)
    }

    fun addHistory(ctx: Context, word: String) {
        val hDb = getHistoryDb(ctx)
        hDb.execSQL("CREATE TABLE IF NOT EXISTS search_history(id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT UNIQUE)")
        hDb.execSQL("INSERT OR IGNORE INTO search_history(word) VALUES(?)", arrayOf(word))
        hDb.close()
    }

    fun getHistoryList(ctx: Context): MutableList<String> {
        val list = mutableListOf<String>()
        val hDb = getHistoryDb(ctx)
        val cursor = hDb.rawQuery("SELECT word FROM search_history ORDER BY id DESC", null)
        while (cursor.moveToNext()) {
            list.add(cursor.getString(0))
        }
        cursor.close()
        hDb.close()
        return list
    }

    fun close() {
        db?.close()
    }
}