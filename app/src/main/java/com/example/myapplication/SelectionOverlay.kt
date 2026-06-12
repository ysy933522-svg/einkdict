package com.example.myapplication

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SelectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var startX = -1f
    private var startY = -1f
    private var endX = -1f
    private var endY = -1f

    // 是否处于框选模式（默认关闭，点击截图按钮后开启）
    var isSelecting = false

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 255, 0, 0)
        style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 只有在框选模式下才消费事件
        if (!isSelecting) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                endX = event.x
                endY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x
                endY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                endX = event.x
                endY = event.y
                invalidate()
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (startX < 0 || endX < 0) return
        val left = minOf(startX, endX)
        val top = minOf(startY, endY)
        val right = maxOf(startX, endX)
        val bottom = maxOf(startY, endY)

        canvas.drawRect(left, top, right, bottom, borderPaint)
//        canvas.drawRect(left, top, right, bottom, fillPaint)
    }

    fun getSelectionRect(): Rect? {
        if (startX < 0 || endX < 0) return null
        return Rect(
            minOf(startX, endX).toInt(),
            minOf(startY, endY).toInt(),
            maxOf(startX, endX).toInt(),
            maxOf(startY, endY).toInt()
        )
    }

    fun clear() {
        startX = -1f
        startY = -1f
        endX = -1f
        endY = -1f
        isSelecting = false
        invalidate()
    }


}