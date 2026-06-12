package com.example.myapplication

import android.graphics.Bitmap
import java.util.*

class PageCache(private val maxSize: Int = 3) {

    private val cache = object : LinkedHashMap<Int, Bitmap>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean {
            return size > maxSize
        }
    }

    fun get(pageIndex: Int): Bitmap? = cache[pageIndex]

    fun put(pageIndex: Int, bitmap: Bitmap) {
        cache[pageIndex] = bitmap
    }

    fun remove(pageIndex: Int) {
        cache.remove(pageIndex)?.recycle()
    }

    fun clear() {
        cache.values.forEach { it.recycle() }
        cache.clear()
    }
}