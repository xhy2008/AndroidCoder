package com.coderagent.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 额外按键行（移植 Termux ExtraKeysView 布局）：
 * 两行按钮，参考 Termux 默认布局：
 *   ESC  /   -   HOME  ↑  END  PGUP
 *   TAB CTRL ALT  ←    ↓  →    PGDN
 * CTRL/ALT 为 sticky 修饰键：按下后高亮，对下一个键生效。
 */
class ExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    /** 发送字节到 pty（Activity 提供） */
    var onSend: ((String) -> Unit)? = null

    /** 修饰键状态变化回调（ctrl, alt），供 TerminalView 组合输入 */
    var onModifier: ((ctrl: Boolean, alt: Boolean) -> Unit)? = null

    private var ctrl = false
    private var alt = false
    private var ctrlBtn: TextView? = null
    private var altBtn: TextView? = null

    private val normalBg = Color.rgb(0x3A, 0x3F, 0x45)
    private val ctrlBg = Color.rgb(0x64, 0xB5, 0xF6)
    private val altBg = Color.rgb(0x81, 0xC7, 0x84)

    init {
        orientation = VERTICAL
        setPadding(dp(3), dp(2), dp(3), dp(2))
        background = GradientDrawable().apply {
            setColor(Color.rgb(0x2B, 0x2F, 0x33))
            setStroke(dp(1), Color.rgb(0x40, 0x44, 0x48))
        }
        isClickable = true

        // 第一行
        addRow(
            Triple("ESC", "\u001B", null),
            Triple("/", "/", null),
            Triple("-", "-", null),
            Triple("HOME", "\u001B[H", null),
            Triple("↑", "\u001B[A", null),
            Triple("END", "\u001B[F", null),
            Triple("PGUP", "\u001B[5~", null)
        )
        // 第二行
        addRow(
            Triple("TAB", "\t", null),
            Triple("CTRL", null) { ctrl = !ctrl; updateModifiers(); onModifier?.invoke(ctrl, alt) },
            Triple("ALT", null) { alt = !alt; updateModifiers(); onModifier?.invoke(ctrl, alt) },
            Triple("←", "\u001B[D", null),
            Triple("↓", "\u001B[B", null),
            Triple("→", "\u001B[C", null),
            Triple("PGDN", "\u001B[6~", null)
        )
    }

    private fun addRow(vararg keys: Triple<String, String?, (() -> Unit)?>) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        for ((label, send, action) in keys) {
            val tv = makeButton(label) {
                if (action != null) action() else onSend?.invoke(send ?: label)
            }
            row.addView(tv)
            if (label == "CTRL") ctrlBtn = tv
            if (label == "ALT") altBtn = tv
        }
        addView(row)
    }

    private fun makeButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(normalBg)
                cornerRadius = dp(3).toFloat()
            }
            setPadding(dp(6), dp(5), dp(6), dp(5))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(1)
                marginEnd = dp(1)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun updateModifiers() {
        ctrlBtn?.background = GradientDrawable().apply {
            setColor(if (ctrl) ctrlBg else normalBg)
            cornerRadius = dp(3).toFloat()
        }
        altBtn?.background = GradientDrawable().apply {
            setColor(if (alt) altBg else normalBg)
            cornerRadius = dp(3).toFloat()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
