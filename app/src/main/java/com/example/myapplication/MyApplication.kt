package com.example.myapplication

import android.app.Application

class MyApplication : Application() {
    lateinit var dbHelper: DictDbHelper
        private set

    override fun onCreate() {
        super.onCreate()
        dbHelper = DictDbHelper(this)
    }
}