package com.coderagent.android

/**
 * 精简版终端模拟器（移植 Termux TerminalEmulator / EscapeSequence 的核心设计）：
 * - 行列屏幕缓冲 + 历史滚动区（Termux Screen / TerminalBuffer）
 * - 状态机解析 ANSI 转义序列（Termux EscapeSequence）：CSI 光标/清屏/SGR 颜色/alt 屏、OSC 跳过
 * - 输入字节流 feed 后只修改缓冲，渲染交给 TerminalView（只画可见区）
 *
 * 说明：宿主进程非 PTY（未引入 NDK），`\n` 按 ONLCR 语义处理（回行首+下移），
 * 因此普通命令输出与交互式 shell 一致。
 */
class TerminalBuffer(initialRows: Int, initialCols: Int) {

    /** 屏幕单元：字符 + 前景色 + 背景色 + 粗体 */
    class Cell(val ch: Char = ' ', val fg: Int = DEFAULT_FG, val bg: Int = DEFAULT_BG, val bold: Boolean = false)

    private var rows = 0
    private var cols = 0
    private var screen: Array<Array<Cell>> = emptyArray()
    private var altScreen: Array<Array<Cell>> = emptyArray()
    private val history = ArrayDeque<Array<Cell>>()

    private var cursorRow = 0
    private var cursorCol = 0
    private var fg = DEFAULT_FG
    private var bg = DEFAULT_BG
    private var bold = false
    private var altMode = false
    private var scrollTop = 0
    private var scrollBottom = 0

    // 线程安全：pump 线程写缓冲、渲染线程读，公共读写经 lock 同步
    private val lock = Any()

    // 转义解析状态
    private var state = STATE_NORMAL
    private val params = mutableListOf<Int>()
    private var cur = 0
    private var privateMode = false
    private var saveRow = 0
    private var saveCol = 0

    /** 当前滚动偏移：0 = 底部（跟随），>0 = 查看历史 */
    @Volatile
    var scrollOffset = 0
        private set
    @Volatile
    var historySize: Int = 0
        private set

    init {
        resize(initialRows, initialCols)
    }

    fun resize(newRows: Int, newCols: Int) = synchronized(lock) {
        if (newRows == rows && newCols == cols) return
        rows = newRows
        cols = newCols
        // 简单实现：重建缓冲（保留历史行裁剪到新列宽）
        val newScreen = Array(rows) { Array(cols) { Cell() } }
        val newAlt = Array(rows) { Array(cols) { Cell() } }
        for (r in 0 until minOf(rows, screen.size)) {
            for (c in 0 until minOf(cols, screen[r].size)) newScreen[r][c] = screen[r][c]
            if (r < altScreen.size) for (c in 0 until minOf(cols, altScreen[r].size)) newAlt[r][c] = altScreen[r][c]
        }
        screen = newScreen
        altScreen = newAlt
        scrollTop = 0
        scrollBottom = rows - 1
        if (cursorRow >= rows) cursorRow = rows - 1
        if (cursorCol >= cols) cursorCol = cols - 1
        // 历史行截列
        val nh = ArrayDeque<Array<Cell>>()
        for (line in history) {
            nh.addLast(Array(cols) { c -> if (c < line.size) line[c] else Cell() })
        }
        history.clear()
        history.addAll(nh)
        if (historySize != history.size) historySize = history.size
    }

    /** 行可视总数（含历史） */
    fun totalRows(): Int = history.size + rows

    /** 获取可见行（0 = 屏幕顶行，可能为历史行），超出返回 null */
    fun getRow(idx: Int): Array<Cell>? = synchronized(lock) {
        if (idx < 0 || idx >= totalRows()) null
        else if (idx < history.size) history[idx] else screen[idx - history.size]
    }

    fun getCursorRow(): Int = synchronized(lock) { cursorRow }
    fun getCursorCol(): Int = synchronized(lock) { cursorCol }
    fun isCursorVisible(): Boolean = true

    fun feed(data: ByteArray) = synchronized(lock) {
        // 先按 UTF-8 解码成字符流再喂给状态机（转义序列均为 ASCII，不受影响；
        // 多字节 UTF-8 内容因此能正确保留，而不是逐字节乱码）
        val text = String(data, Charsets.UTF_8)
        for (c in text) feedChar(c)
    }

    fun feed(text: String) = synchronized(lock) {
        for (c in text) feedChar(c)
    }

    private fun feedChar(c: Char) {
        when (state) {
            STATE_NORMAL -> when (c) {
                '\u001b' -> state = STATE_ESC
                '\n' -> { cursorCol = 0; moveDown() } // LF：模拟 ONLCR（回行首+下移）
                '\r' -> cursorCol = 0
                '\b' -> if (cursorCol > 0) cursorCol--
                '\t' -> cursorCol = ((cursorCol / 8) + 1) * 8
                '\u0007' -> Unit // BEL 忽略
                else -> if (c.code >= 32) writeChar(c)
            }
            STATE_ESC -> when (c) {
                '[' -> { params.clear(); cur = 0; privateMode = false; state = STATE_CSI }
                ']' -> state = STATE_OSC
                '(', ')', '*', '+', '-', '.', '/' -> state = STATE_SKIP_CHARSET
                '7' -> { saveRow = cursorRow; saveCol = cursorCol; state = STATE_NORMAL }
                '8' -> { cursorRow = saveRow; cursorCol = saveCol; state = STATE_NORMAL }
                'M' -> moveUp()
                'c' -> resetTerminal()
                else -> state = STATE_NORMAL
            }
            STATE_CSI -> when {
                c in '0'..'9' -> cur = cur * 10 + (c - '0')
                c == ';' -> { params.add(cur); cur = 0 }
                c == '?' -> privateMode = true
                c == ':' -> { params.add(cur); cur = 0 }
                c in 'A'..'Z' || c in 'a'..'z' || c == '`' -> {
                    params.add(cur)
                    applyCSI(c)
                    state = STATE_NORMAL
                }
                else -> state = STATE_NORMAL
            }
            STATE_OSC -> if (c == '\u0007') state = STATE_NORMAL else if (c == '\u001b') state = STATE_OSC_ESC
            STATE_OSC_ESC -> state = STATE_NORMAL
            STATE_SKIP_CHARSET -> state = STATE_NORMAL
        }
    }

    private fun writeChar(c: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            moveDown()
        }
        screen[cursorRow][cursorCol] = Cell(c, fg, bg, bold)
        cursorCol++
    }

    private fun moveDown() {
        if (cursorRow < scrollBottom) cursorRow++ else scrollRegionUp()
    }

    private fun moveUp() {
        if (cursorRow > scrollTop) cursorRow-- else {
            // 向上滚入历史
            if (!altMode) {
                history.addLast(Array(cols) { c -> screen[scrollBottom][c] })
                trimHistory()
            }
            for (r in scrollBottom downTo scrollTop + 1) screen[r] = screen[r - 1]
            screen[scrollTop] = Array(cols) { Cell() }
        }
    }

    private fun scrollRegionUp() {
        // 把滚动区顶部一行移入历史
        if (!altMode) {
            val top = screen[scrollTop]
            history.addLast(Array(cols) { c -> top[c] })
            trimHistory()
        }
        for (r in scrollTop until scrollBottom) screen[r] = screen[r + 1]
        screen[scrollBottom] = Array(cols) { Cell() }
    }

    private fun trimHistory() {
        if (history.size > MAX_HISTORY) history.removeFirst()
        historySize = history.size
    }

    private fun applyCSI(fin: Char) {
        val p0 = params.getOrElse(0) { 1 }
        val p1 = params.getOrElse(1) { 1 }
        val row = if (params.isEmpty()) 0 else (p0 - 1).coerceIn(0, rows - 1)
        val col = if (params.isEmpty() || params.size < 2) 0 else (p1 - 1).coerceIn(0, cols - 1)
        when (fin) {
            'A' -> cursorRow = (cursorRow - p0.coerceAtLeast(1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + p0.coerceAtLeast(1)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + p0.coerceAtLeast(1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - p0.coerceAtLeast(1)).coerceAtLeast(0)
            'H', 'f' -> { cursorRow = row; cursorCol = col }
            'G', '`' -> cursorCol = (p0 - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (p0 - 1).coerceIn(0, rows - 1)
            'J' -> clearScreen(p0)
            'K' -> clearLine(p0)
            'X' -> eraseChars(p0.coerceAtLeast(1))
            'm' -> applySGR()
            'r' -> {
                val t = (p0 - 1).coerceIn(0, rows - 1)
                val b = (p1 - 1).coerceIn(0, rows - 1)
                scrollTop = minOf(t, b)
                scrollBottom = maxOf(t, b)
                cursorRow = 0; cursorCol = 0
            }
            'h' -> if (privateMode && p0 == 1049) setAltScreen(true)
            'l' -> if (privateMode && p0 == 1049) setAltScreen(false)
            else -> Unit // 其余序列（S/T 滚动、n 状态等）忽略
        }
    }

    private fun clearScreen(mode: Int) {
        when (mode) {
            0 -> for (c in cursorCol until cols) screen[cursorRow][c] = Cell()
            1 -> {
                for (c in 0..cursorCol) screen[cursorRow][c] = Cell()
                for (r in 0 until cursorRow) for (c in 0 until cols) screen[r][c] = Cell()
            }
            2 -> {
                // ED 2：只清屏（Termux 同样不清滚动缓冲）
                for (r in 0 until rows) for (c in 0 until cols) screen[r][c] = Cell()
            }
            else -> {
                // ED 3：清屏并清空历史
                for (r in 0 until rows) for (c in 0 until cols) screen[r][c] = Cell()
                history.clear()
                historySize = 0
            }
        }
    }

    private fun clearLine(mode: Int) {
        when (mode) {
            0 -> for (c in cursorCol until cols) screen[cursorRow][c] = Cell()
            1 -> for (c in 0..cursorCol) screen[cursorRow][c] = Cell()
            else -> for (c in 0 until cols) screen[cursorRow][c] = Cell()
        }
    }

    private fun eraseChars(n: Int) {
        for (i in 0 until n) {
            val c = cursorCol + i
            if (c >= cols) break
            screen[cursorRow][c] = Cell()
        }
    }

    private fun applySGR() {
        if (params.isEmpty()) params.add(0)
        var i = 0
        while (i < params.size) {
            when (val code = params[i]) {
                0 -> { fg = DEFAULT_FG; bg = DEFAULT_BG; bold = false }
                1 -> bold = true
                22 -> bold = false
                in 30..37 -> fg = ANSI_COLORS[code - 30]
                in 90..97 -> fg = ANSI_BRIGHT[code - 90]
                in 40..47 -> bg = ANSI_COLORS[code - 40]
                in 100..107 -> bg = ANSI_BRIGHT[code - 100]
                39 -> fg = DEFAULT_FG
                49 -> bg = DEFAULT_BG
                else -> Unit // 38/48 256 色与 truecolor 暂忽略
            }
            i++
        }
    }

    private fun setAltScreen(on: Boolean) {
        if (altMode == on) return
        if (on) {
            for (r in 0 until rows) for (c in 0 until cols) altScreen[r][c] = screen[r][c]
            for (r in 0 until rows) for (c in 0 until cols) screen[r][c] = Cell()
        } else {
            for (r in 0 until rows) for (c in 0 until cols) screen[r][c] = altScreen[r][c]
        }
        altMode = on
        cursorRow = 0; cursorCol = 0
    }

    private fun resetTerminal() {
        for (r in 0 until rows) for (c in 0 until cols) screen[r][c] = Cell()
        cursorRow = 0; cursorCol = 0
        fg = DEFAULT_FG; bg = DEFAULT_BG; bold = false
        scrollTop = 0; scrollBottom = rows - 1
    }

    /** 清空全部（含历史与滚动），供重启 shell 时使用 */
    fun reset() = synchronized(lock) {
        resetTerminal()
        history.clear()
        historySize = 0
        scrollOffset = 0
    }

    /** 触摸滚动：offset=0 底部，>0 向上翻历史 */
    fun setScrollOffset(offset: Int) = synchronized(lock) {
        scrollOffset = offset.coerceIn(0, history.size)
    }

    /** 调试用：导出屏幕内容（. 表示空格，C 标记有颜色的字符），用于排查渲染问题 */
    fun dumpScreen(): String = synchronized(lock) {
        val sb = StringBuilder()
        sb.append("rows=$rows cols=$cols history=$historySize scroll=$scrollOffset cursor=($cursorRow,$cursorCol) state=$state\n")
        for (r in 0 until rows) {
            val chars = StringBuilder()
            val colors = StringBuilder()
            for (c in 0 until cols) {
                val cell = screen[r][c]
                chars.append(if (cell.ch == ' ') '.' else cell.ch)
                colors.append(if (cell.fg == DEFAULT_FG) '-' else 'C')
            }
            sb.append(chars).append("  |  ").append(colors).append('\n')
        }
        sb.toString()
    }

    companion object {
        const val DEFAULT_FG = -1
        const val DEFAULT_BG = -2
        private const val MAX_HISTORY = 3000
        private const val STATE_NORMAL = 0
        private const val STATE_ESC = 1
        private const val STATE_CSI = 2
        private const val STATE_OSC = 3
        private const val STATE_OSC_ESC = 4
        private const val STATE_SKIP_CHARSET = 5

        // 标准 ANSI 色板（与 Termux 默认配色一致）：
        // 注意：必须带 0xFF alpha 前缀，否则作为 Android ARGB 颜色时 alpha=0 完全透明不可见
        val ANSI_COLORS = intArrayOf(
            0xFF000000.toInt(), 0xFFCC0000.toInt(), 0xFF00CC00.toInt(), 0xFFCCCC00.toInt(),
            0xFF0000CC.toInt(), 0xFFCC00CC.toInt(), 0xFF00CCCC.toInt(), 0xFFCCCCCC.toInt()
        )
        val ANSI_BRIGHT = intArrayOf(
            0xFF555555.toInt(), 0xFFFF5555.toInt(), 0xFF55FF55.toInt(), 0xFFFFFF55.toInt(),
            0xFF5555FF.toInt(), 0xFFFF55FF.toInt(), 0xFF55FFFF.toInt(), 0xFFFFFFFF.toInt()
        )
    }
}
