package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

class DictDbHelper(private val mContext: Context) :
    SQLiteOpenHelper(mContext, "local_dict.db", null, 1) {

    private val TAG = "DictDbHelper"
    private val ASSETS_DB_NAME = "dict.db"
    private val DB_NAME = "local_dict.db"
    private val SP_KEY_DB_SIZE = "last_db_file_size"
    private val sp: SharedPreferences by lazy {
        mContext.getSharedPreferences("db_version", Context.MODE_PRIVATE)
    }

    fun copyDatabase(): Boolean {
        val dbFile = mContext.getDatabasePath(DB_NAME)
        val assetFileSize = getAssetsFileSize(ASSETS_DB_NAME)
        val lastSize = sp.getLong(SP_KEY_DB_SIZE, -1)

        if (!dbFile.exists() || assetFileSize != lastSize) {
            try {
                dbFile.parentFile?.mkdirs()
                val inputStream: InputStream = mContext.assets.open(ASSETS_DB_NAME)
                val outputStream = FileOutputStream(dbFile)

                val buffer = ByteArray(4096)
                var length: Int
                while (inputStream.read(buffer).also { length = it } > 0) {
                    outputStream.write(buffer, 0, length)
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()

                sp.edit().putLong(SP_KEY_DB_SIZE, assetFileSize).apply()
                Log.i(TAG, "词典已更新，重新复制完成")
                return true
            } catch (e: IOException) {
                Log.e(TAG, "数据库复制失败：${e.message}", e)
                return false
            }
        } else {
            Log.i(TAG, "词典无变化，跳过复制")
            return true
        }
    }

    private fun getAssetsFileSize(assetName: String): Long {
        return try {
            val stream = mContext.assets.open(assetName)
            val size = stream.available().toLong()
            stream.close()
            size
        } catch (e: IOException) {
            0L
        }
    }

    fun queryWord(word: String): String {
        val db = readableDatabase
        val sql = "SELECT explain FROM word_dict WHERE LOWER(word) = ?"
        val cursor = db.rawQuery(sql, arrayOf(word.lowercase()))

        val result = if (cursor.moveToFirst()) {
            cursor.getString(0)
        } else {
            "未查询到该单词"
        }
        cursor.close()
        return result
    }

    override fun onCreate(db: SQLiteDatabase?) {}

    // 补全抽象方法，当前版本号固定为1，暂不需要升级逻辑
    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {}
}