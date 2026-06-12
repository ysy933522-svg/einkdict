package com.example.myapplication

data class DisplaySettings(
    val contrast: Float? = null,       // 对比度
    val brightness: Int? = null,       // 亮度
    val sharpness: Float? = null,      // 锐化
    val usePrintMode: Boolean? = null  // 印刷模式
)