package com.coderagent.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout

/**
 * 编辑器行号列：与右侧 EditText 同处一个 ScrollView，纵向同步滚动。
 * - 通过 sync() 与编辑器的行高/顶部内边距/行数对齐绘制 1..N 行号
 * - 幂等：数值无变化时不触发重排，避免与 onGlobalLayout 互激
 */
class LineNumberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 关联的编辑器，行号据此对齐 */
    var editor: EditText? = null
        set(value) {
            field = value
            sync()
        }

    private val numPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A93A3.toInt()
        textSize = 12f * resources.displayMetrics.scaledDensity
    }

    // 编辑器文字度量（与编辑器同源，保证行号基线与文字基线对齐）
    private var lineHeightPx = 0f
    private var topPad = 0f

    /** 与编辑器对齐并刷新（幂等） */
    fun sync() {
        val e = editor ?: return
        val newLineH = e.lineHeight.toFloat()
        val newTop = e.paddingTop.toFloat()
        val digits = e.lineCount.toString().length.coerceAtLeast(2)
        val newW = (numPaint.measureText("9") * digits + paddingLeft + paddingRight).toInt()

        var changed = false
        if (newLineH != lineHeightPx) { lineHeightPx = newLineH; changed = true }
        if (newTop != topPad) { topPad = newTop; changed = true }
        if (newW != width) {
            layoutParams = (layoutParams as? LinearLayout.LayoutParams)
                ?.apply { this.width = newW }
                ?: LinearLayout.LayoutParams(newW, height)
            changed = true
        }
        if (e.height != height) {
            layoutParams = (layoutParams as? LinearLayout.LayoutParams)
                ?.apply { this.height = e.height }
                ?: LinearLayout.LayoutParams(width, e.height)
            changed = true
        }
        if (changed) requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val e = editor ?: return
        val n = e.lineCount
        if (n <= 0 || lineHeightPx <= 0f) return
        val fm = e.paint.fontMetrics
        // 第一行文字基线（与 EditText 内部布局一致）
        val base0 = topPad - fm.ascent
        for (i in 0 until n) {
            val num = (i + 1).toString()
            val x = width - paddingRight - numPaint.measureText(num)
            canvas.drawText(num, x, base0 + i * lineHeightPx, numPaint)
        }
    }
}
