package com.example.myapplication

import android.content.Context
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast

object ToastUtil {
    fun show(context: Context, message: String) {
        val toast = Toast.makeText(context, "", Toast.LENGTH_SHORT)
        // 创建纯文字的 TextView
        val textView = TextView(context)
        textView.text = message
        textView.setTextColor(android.graphics.Color.BLACK)
        textView.textSize = 16f
        textView.setPadding(0, 0, 0, 0)
        textView.background = null  // 去除任何背景
        toast.view = textView
        toast.setGravity(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM, 0, 100)
        toast.show()
    }
}