package com.coderagent.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.text.TextPaint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * 终端渲染视图（移植 Termux TerminalView 核心思路）：
 * - 只绘制可见 viewport 行（起始行 = 历史数 - 滚动偏移），海量输出不会卡 UI
 * - 每行按颜色分段绘制（fg/bg/bold 变化处分段），等宽字体逐段 drawText
 * - 触摸拖动浏览历史，点击回调 onTap（用于弹出软键盘）
 * - 实现 InputConnection 接收软键盘文本/功能键，硬键盘经 onKeyDown 处理，
 *   CTRL/ALT（来自底部 ExtraKeys sticky 修饰键）组合编码后写入 pty
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val bgPaint = Paint()
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
    private val bgColor = 0xFF101418.toInt()
    private val defaultFg = 0xFFE8E8E8.toInt()

    private var lineHeight = 20f
    private var charWidth = 8f
    private var cols = 0
    private var rows = 0

    private var buffer = TerminalBuffer(30, 80)

    /** 绑定共享会话缓冲（会话持久化）：恢复上次终端内容，之后输出由会话回调 refresh */
    fun bindBuffer(b: TerminalBuffer) {
        buffer = b
        postInvalidate()
    }

    /** 会话有新输出时刷新（pump 线程回调，postInvalidate 线程安全） */
    fun refresh() = postInvalidate()
    private var touchScrolling = false
    private var lastY = 0f
    private var downTime = 0L

    // 选择模式（长按进入，拖动选矩形，用于复制文本）
    private var selectionMode = false
    private var selStartRow = 0
    private var selStartCol = 0
    private var selEndRow = 0
    private var selEndCol = 0
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x6680D8FF }

    /** 选择模式开关变化回调（Activity 据此显示/隐藏复制按钮） */
    var onSelectionChanged: ((active: Boolean) -> Unit)? = null

    /** 点击终端区域（非拖动）时回调，用于弹出软键盘 */
    var onTap: (() -> Unit)? = null

    /** 尺寸（行列数）变化回调，用于同步 pty 窗口大小（SIGWINCH） */
    var onSize: ((rows: Int, cols: Int) -> Unit)? = null

    /** 把按键/文本字节写入 pty（由 Activity 提供） */
    var writeToPty: ((String) -> Unit)? = null

    /** ExtraKeys 的 sticky 修饰键状态（按下后对下一个字符生效） */
    var extraCtrl = false
    var extraAlt = false

    /** 外部把终端输出字节流 feed 进来（postInvalidate 只请求一帧，天然节流） */
    fun feed(data: ByteArray) {
        buffer.feed(data)
        postInvalidate()
    }

    fun feed(text: String) {
        buffer.feed(text)
        postInvalidate()
    }

    fun reset() {
        buffer.reset()
        postInvalidate()
    }

    /** 调试用：导出缓冲状态（含历史与滚动），由 adb 广播触发写入文件 */
    fun dumpBuffer(): String = buffer.dumpScreen()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val fm = textPaint.fontMetrics
        lineHeight = fm.descent - fm.ascent + 3f
        charWidth = textPaint.measureText("M").coerceAtLeast(4f)
        val newCols = (w / charWidth).toInt().coerceAtLeast(10)
        val newRows = (h / lineHeight).toInt().coerceAtLeast(5)
        if (newCols != cols || newRows != rows) {
            cols = newCols
            rows = newRows
            buffer.resize(newRows, newCols)
            onSize?.invoke(newRows, newCols)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val firstVisible = buffer.historySize - buffer.scrollOffset
        for (r in 0 until rows) {
            val row = buffer.getRow(r + firstVisible) ?: continue
            drawRow(canvas, row, r, r + firstVisible)
        }
        if (buffer.scrollOffset == 0) {
            val cr = buffer.getCursorRow()
            val cc = buffer.getCursorCol()
            if (cr in 0 until rows && cc in 0 until cols) {
                val x = cc * charWidth
                val y = cr * lineHeight
                canvas.drawRect(x, y, x + charWidth, y + lineHeight, cursorPaint)
            }
        }
    }

    private fun drawRow(canvas: Canvas, row: Array<TerminalBuffer.Cell>, screenRow: Int, globalRow: Int) {
        val baseY = screenRow * lineHeight
        val fm = textPaint.fontMetrics
        val baseline = baseY + lineHeight / 2f - (fm.ascent + fm.descent) / 2f
        // 选中高亮（矩形选区）
        if (selectionMode) {
            val r1 = minOf(selStartRow, selEndRow)
            val r2 = maxOf(selStartRow, selEndRow)
            if (globalRow in r1..r2) {
                val c1 = if (globalRow == r1) minOf(selStartCol, selEndCol) else 0
                val c2 = if (globalRow == r2) maxOf(selStartCol, selEndCol) else cols - 1
                canvas.drawRect(c1 * charWidth, baseY, (c2 + 1) * charWidth, baseY + lineHeight, selectPaint)
            }
        }
        var i = 0
        while (i < cols) {
            val cell = row[i]
            var j = i + 1
            while (j < cols && sameStyle(row[j], cell)) j++
            val left = i * charWidth
            val width = (j - i) * charWidth
            if (cell.bg != TerminalBuffer.DEFAULT_BG) {
                bgPaint.color = cell.bg
                canvas.drawRect(left, baseY, left + width, baseY + lineHeight, bgPaint)
            }
            textPaint.color = if (cell.fg == TerminalBuffer.DEFAULT_FG) defaultFg else cell.fg
            textPaint.isFakeBoldText = cell.bold
            // 文本：段内再按非空格连续区间切分绘制。
            // 不能只看段首字符（段首可能是空格：缩进、颜色重置后的" - 描述"），
            // 否则整段含文字的字符都会被吞掉。
            var k = i
            while (k < j) {
                if (row[k].ch != ' ') {
                    var e = k + 1
                    while (e < j && row[e].ch != ' ') e++
                    val cs = CharArray(e - k)
                    for (m in k until e) cs[m - k] = row[m].ch
                    canvas.drawText(cs, 0, cs.size, k * charWidth, baseline, textPaint)
                    k = e
                } else {
                    k++
                }
            }
            i = j
        }
    }

    private fun sameStyle(a: TerminalBuffer.Cell, b: TerminalBuffer.Cell): Boolean =
        a.fg == b.fg && a.bg == b.bg && a.bold == b.bold

    // ---------- 触摸 ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (selectionMode) {
                    // 选择模式下点击：退出选择
                    selectionMode = false
                    onSelectionChanged?.invoke(false)
                    invalidate()
                    return true
                }
                touchScrolling = true
                lastY = e.y
                downTime = System.currentTimeMillis()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (selectionMode) {
                    getCellAt(e.x, e.y)?.let {
                        selEndRow = it.first
                        selEndCol = it.second
                        invalidate()
                    }
                    return true
                }
                if (touchScrolling) {
                    val dy = (e.y - lastY).toInt()
                    lastY = e.y
                    val offset = buffer.scrollOffset + (dy / lineHeight).toInt()
                    buffer.setScrollOffset(offset)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val isTap = System.currentTimeMillis() - downTime < 200 && touchScrolling
                touchScrolling = false
                if (isTap) {
                    onTap?.invoke()
                    requestFocus()
                }
                return true
            }
            else -> return false
        }
    }

    // ---------- 选择模式（长按选择文本复制） ----------

    init {
        setOnLongClickListener {
            getCellAt(it.x, it.y)?.let { cell ->
                selStartRow = cell.first
                selStartCol = cell.second
                selEndRow = cell.first
                selEndCol = cell.second
                selectionMode = true
                onSelectionChanged?.invoke(true)
                invalidate()
            }
            true
        }
    }

    private fun getCellAt(x: Float, y: Float): Pair<Int, Int>? {
        val rowIdx = (y / lineHeight).toInt() + (buffer.historySize - buffer.scrollOffset)
        if (rowIdx < 0 || rowIdx >= buffer.totalRows()) return null
        val colIdx = (x / charWidth).toInt().coerceIn(0, cols - 1)
        return Pair(rowIdx, colIdx)
    }

    fun hasSelection(): Boolean = selectionMode

    fun clearSelection() {
        if (selectionMode) {
            selectionMode = false
            onSelectionChanged?.invoke(false)
            invalidate()
        }
    }

    /** 提取选中矩形区域的文本（逐行取列区间，行尾去空格） */
    fun getSelectionText(): String {
        val r1 = minOf(selStartRow, selEndRow)
        val r2 = maxOf(selStartRow, selEndRow)
        val c1 = minOf(selStartCol, selEndCol)
        val c2 = maxOf(selStartCol, selEndCol)
        val sb = StringBuilder()
        for (r in r1..r2) {
            val row = buffer.getRow(r) ?: continue
            val line = StringBuilder()
            for (c in c1..c2) line.append(if (c < row.size) row[c].ch else ' ')
            sb.append(line.toString().trimEnd(' '))
            if (r != r2) sb.append('\n')
        }
        return sb.toString()
    }

    // ---------- 输入（Termux 式：View 直接接收软键盘/硬键盘） ----------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.toString()?.let { sendTypedText(it) }
                return super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) writeToPty?.invoke("\u007f")
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && handleKeyEvent(event)) return true
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKeyEvent(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DEL) return true
        return super.onKeyUp(keyCode, event)
    }

    /** 发送文本：应用 sticky CTRL/ALT 修饰后写入 pty */
    private fun sendTypedText(t: String) {
        if (t.isEmpty()) return
        if (t.length == 1 && extraCtrl) {
            val c = t[0]
            val enc = encodeCtrl(c)
            if (enc != null) {
                extraCtrl = false
                writeToPty?.invoke(enc)
                return
            }
        }
        writeToPty?.invoke(t)
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> { writeToPty?.invoke("\r"); return true }
            KeyEvent.KEYCODE_DEL -> { writeToPty?.invoke("\u007f"); return true }
            KeyEvent.KEYCODE_TAB -> { writeToPty?.invoke("\t"); return true }
            KeyEvent.KEYCODE_ESCAPE -> { writeToPty?.invoke("\u001b"); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { writeToPty?.invoke("\u001b[A"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { writeToPty?.invoke("\u001b[B"); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { writeToPty?.invoke("\u001b[C"); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { writeToPty?.invoke("\u001b[D"); return true }
            KeyEvent.KEYCODE_BACK -> return false
        }
        val meta = event.metaState
        val unicode = event.getUnicodeChar(meta)
        if (unicode != 0) {
            var c = unicode.toChar()
            if (meta and KeyEvent.META_CTRL_ON != 0 || extraCtrl) {
                encodeCtrl(c)?.let { writeToPty?.invoke(it) }
                extraCtrl = false
                return true
            }
            if (meta and KeyEvent.META_ALT_ON != 0 || extraAlt) {
                writeToPty?.invoke("\u001b${c}")
                extraAlt = false
                return true
            }
            writeToPty?.invoke(c.toString())
            return true
        }
        return false
    }

    /** Ctrl+字符 → 控制字节（a-z→0x01-0x1A，@[\]^_→0x00-0x1F，其他原样） */
    private fun encodeCtrl(c: Char): String? {
        val lower = c.lowercaseChar()
        return when (lower) {
            in 'a'..'z' -> (lower - 'a' + 1).toChar().toString()
            in '@'..'_' -> (c.code - '@'.code).toChar().toString()
            '?' -> "\u007f"
            else -> null
        }
    }

    /** 供外部请求软键盘（点击终端时调用） */
    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}
