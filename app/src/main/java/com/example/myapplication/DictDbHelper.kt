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

        // 初始化历史记录表
        initHistoryTable()
    }

    // 初始化历史记录表
    private fun initHistoryTable() {
        val hDb = context.openOrCreateDatabase("history_db", Context.MODE_PRIVATE, null)
        hDb.execSQL("CREATE TABLE IF NOT EXISTS search_history(id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT UNIQUE)")
        hDb.close()
    }

    // 词典单词查询
    fun queryWordWithDict(word: String): MutableList<String> {
        val resultList = mutableListOf<String>()
        if (!isReady || db == null) return resultList
        val lowerWord = word.lowercase()   // 转为小写

        val cursor = db!!.rawQuery("SELECT dict_name, explain FROM word_dict WHERE word = ?", arrayOf(lowerWord))
        while (cursor.moveToNext()) {
            val name = cursor.getString(0)
            val explain = cursor.getString(1)
            resultList.add("【$name】\n$explain")
        }
        cursor.close()
        return resultList
    }

    // 新增历史记录
    fun addHistory(word: String) {
        val hDb = context.openOrCreateDatabase("history_db", Context.MODE_PRIVATE, null)
        hDb.execSQL("INSERT OR IGNORE INTO search_history(word) VALUES(?)", arrayOf(word))
        hDb.close()
    }

    // ========== 后端分页核心方法 ==========
    /**
     * 分页查询历史记录
     * @param pageIndex 当前页码（从0开始）
     * @param pageSize 每页条数
     */
    fun getHistoryByPage(pageIndex: Int, pageSize: Int): MutableList<String> {
        val list = mutableListOf<String>()
        val offset = pageIndex * pageSize
        val hDb = context.openOrCreateDatabase("history_db", Context.MODE_PRIVATE, null)
        // 倒序查询 + 分页 LIMIT offset,size
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

    // 查询历史记录总条数
    fun getHistoryTotalCount(): Int {
        val hDb = context.openOrCreateDatabase("history_db", Context.MODE_PRIVATE, null)
        val cursor = hDb.rawQuery("SELECT COUNT(*) FROM search_history", null)
        var total = 0
        if (cursor.moveToFirst()) {
            total = cursor.getInt(0)
        }
        cursor.close()
        hDb.close()
        hDb.close()
        return total
    }

    // 关闭数据库
    fun close() {
        db?.close()
    }
}