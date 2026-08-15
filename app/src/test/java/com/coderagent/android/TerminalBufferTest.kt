package com.coderagent.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TerminalBuffer 终端模拟器单元测试（JVM 本地运行，验证 SGR 颜色/换行/滚动等解析）。
 */
class TerminalBufferTest {

    @Test
    fun sgrGreenThenReset() {
        val buf = TerminalBuffer(30, 80)
        buf.feed("\u001b[32mGREEN\u001b[0m".toByteArray())
        val row = buf.getRow(0)!!
        assertEquals('G', row[0].ch)
        assertEquals(TerminalBuffer.ANSI_COLORS[2], row[0].fg)
        assertEquals('N', row[4].ch)
        assertEquals(TerminalBuffer.ANSI_COLORS[2], row[4].fg)
        // 重置后写入应为默认色
        buf.feed("X".toByteArray())
        assertEquals('X', buf.getRow(0)!![5].ch)
        assertEquals(TerminalBuffer.DEFAULT_FG, buf.getRow(0)!![5].fg)
    }

    @Test
    fun sgrBoldGreen() {
        val buf = TerminalBuffer(30, 80)
        buf.feed("\u001b[1;32mA\u001b[0m".toByteArray())
        val cell = buf.getRow(0)!![0]
        assertEquals('A', cell.ch)
        assertEquals(TerminalBuffer.ANSI_COLORS[2], cell.fg)
        assertEquals(true, cell.bold)
    }

    @Test
    fun autoWrap() {
        val buf = TerminalBuffer(3, 10)
        buf.feed("1234567890ABCDE".toByteArray())
        assertEquals('A', buf.getRow(1)!![0].ch)
        assertEquals('E', buf.getRow(1)!![4].ch)
    }

    @Test
    fun scrollIntoHistory() {
        val buf = TerminalBuffer(2, 10)
        // 4 行文本，超过 2 行屏幕，前两行应进入历史
        buf.feed("line1\nline2\nline3\nline4".toByteArray())
        assertEquals(2, buf.historySize)
        // 屏幕最后一行是 line4
        val row = buf.getRow(2 + 1)!!
        assertEquals('l', row[0].ch)
        assertEquals('4', row[4].ch)
    }

    @Test
    fun cursorPositionAndClear() {
        val buf = TerminalBuffer(30, 80)
        buf.feed("\u001b[10;5HX".toByteArray())
        assertEquals('X', buf.getRow(9)!![4].ch)
        // 清屏
        buf.feed("\u001b[2J".toByteArray())
        assertEquals(' ', buf.getRow(9)!![4].ch)
    }

    @Test
    fun splitEscapeAcrossFeeds() {
        val buf = TerminalBuffer(30, 80)
        // 转义序列拆成两段 feed，状态应保持
        buf.feed("\u001b[".toByteArray())
        buf.feed("32mG".toByteArray())
        assertEquals('G', buf.getRow(0)!![0].ch)
        assertEquals(TerminalBuffer.ANSI_COLORS[2], buf.getRow(0)!![0].fg)
    }

    @Test
    fun colorResetThenDescription() {
        // apt search 输出：包名绿色加粗，重置后是" - 描述"
        // 渲染层曾因段首空格把整段吞掉，这里锁定 buffer 层必须完整保留
        val buf = TerminalBuffer(30, 80)
        buf.feed("\u001b[1;32mpython3\u001b[0m - interpreter".toByteArray())
        val row = buf.getRow(0)!!
        assertEquals('p', row[0].ch)
        assertEquals(TerminalBuffer.ANSI_COLORS[2], row[0].fg)
        assertEquals(true, row[0].bold)
        // 重置后的空格、连字符、描述文字都应保留
        assertEquals(' ', row[7].ch)
        assertEquals('-', row[8].ch)
        assertEquals('i', row[10].ch)
        assertEquals(TerminalBuffer.DEFAULT_FG, row[8].fg)
    }
}
