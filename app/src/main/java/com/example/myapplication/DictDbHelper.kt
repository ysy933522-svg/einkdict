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
                Log.e(TAG, "词典数据库文件不存在，请检查路径！")
                return@Thread
            }

            try {
                db = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )
                isReady = true
                Log.d(TAG, "词典数据库加载完成")
            } catch (e: Exception) {
                Log.e(TAG, "词典数据库加载失败", e)
            }
        }.start()

        initHistoryTable()
    }

    private fun initHistoryTable() {
        val hDb = context.openOrCreateDatabase("history_db", Context.MODE_PRIVATE, null)
        hDb.execSQL("CREATE TABLE IF NOT EXISTS search_history(id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT UNIQUE)")
        hDb.close()
    }

    fun queryWordWithDict(word: String): MutableList<String> {
        val resultList = mutableListOf<String>()
        if (!isReady || db == null) return resultList

        val cursor = db!!.rawQuery("SELECT dict_name, explain FROM word_dict WHERE word = ?", arrayOf(word))
        while (cursor.moveToNext()) {
            val name = cursor.getString(0)
            val explain = cursor.getString(1)
            resultList.add("【$name】\n$explain")
        }
        cursor.close()
        return resultList
    }

    fun addHistory(word: String) {
        val hDb = context.openOrCreateDatabase("history_db", Context.MODE_PRIVATE, null)
        hDb.execSQL("INSERT OR IGNORE INTO search_history(word) VALUES(?)", arrayOf(word))
        hDb.close()
    }

    fun getHistoryByPage(pageIndex: Int, pageSize: Int): MutableList<String> {
        val list = mutableListOf<String>()
        val offset = pageIndex * pageSize
        val hDb = context.openOrCreateDatabase("history_db", Context.MODE_PRIVATE, null)
        val cursor = hDb.rawQuery(
            "SELECT word FROM search_history ORDER BY id DESC LIMIT ?, ?",
            arrayOf(offset.toString(), pageSize.toString())
        )
        while (cursor.moveToNext()) {
            list.add(cursor.getString(0))
        }
        cursor.close()
        hDb.close()
        return list
    }

    fun getHistoryTotalCount(): Int {
        val hDb = context.openOrCreateDatabase("history_db", Context.MODE_PRIVATE, null)
        val cursor = hDb.rawQuery("SELECT COUNT(*) FROM search_history", null)
        var total = 0
        if (cursor.moveToFirst()) {
            total = cursor.getInt(0)
        }
        cursor.close()
        hDb.close()
        return total
    }

    fun close() {
        db?.close()
    }
}