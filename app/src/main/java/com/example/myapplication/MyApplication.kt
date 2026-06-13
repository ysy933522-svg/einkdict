package com.example.myapplication

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors

class MyApplication : Application() {
    lateinit var dbHelper: DictDbHelper
        private set

    override fun onCreate() {
        super.onCreate()
        dbHelper = DictDbHelper(this)
        // 子线程加载 OpenCV，防止主线程卡死、段错误
        Executors.newSingleThreadExecutor().execute {
            val success = OpenCVLoader.initDebug()
            if (success) {
                Log.i("OpenCV", "库加载成功")
            } else {
                Log.e("OpenCV", "库加载失败")
            }
        }
    }
}