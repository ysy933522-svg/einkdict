import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

class DictDbHelper(private val context: Context) {
    private val TAG = "DictDbHelper"
    // 词典库：保留原有公共路径（只读，你手动放置）
    private val DB_FULL_PATH = "/sdcard/dicts_sqlite_diy_/dict.db"
    private var db: SQLiteDatabase? = null
    var isReady = false
        private set

    // 历史数据库：App 私有目录，系统自动管理，无需权限
    private fun getHistoryDb(): SQLiteDatabase {
        return context.openOrCreateDatabase("history_db", Context.MODE_PRIVATE, null)
    }

    init {
        // 加载外部词典数据库
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

        // 初始化私有历史表（私有目录，可正常写入）
        initHistoryTable()
    }

    // 创建历史记录表（私有库）
    private fun initHistoryTable() {
        val hDb = getHistoryDb()
        hDb.execSQL("CREATE TABLE IF NOT EXISTS search_history(id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT UNIQUE)")
        hDb.close()
    }

    // 原有词典查询方法 【完全未改动】
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

    // 新增历史记录（写入私有数据库）
    fun addHistory(word: String) {
        val hDb = getHistoryDb()
        hDb.execSQL("INSERT OR IGNORE INTO search_history(word) VALUES(?)", arrayOf(word))
        hDb.close()
    }

    // 分页查询历史记录（从私有数据库读取）
    fun getHistoryByPage(pageIndex: Int, pageSize: Int): MutableList<String> {
        val list = mutableListOf<String>()
        val offset = pageIndex * pageSize
        val hDb = getHistoryDb()
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

    // 查询历史总条数（私有数据库）
    fun getHistoryTotalCount(): Int {
        val hDb = getHistoryDb()
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