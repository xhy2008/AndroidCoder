package com.coderagent.android

import android.content.Context
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程级终端会话单例（Termux ShellTermSession 思路的轻量版）：
 * - 持有 PTY fd 与 TerminalBuffer，pump 线程持续读取，与 UI 生命周期解耦
 * - 退出终端界面（Activity finish）后会话继续在后台运行、缓冲继续累积；
 *   重新打开界面时 bindBuffer 到同一缓冲即可恢复内容与当前目录
 * - 多个 TerminalView 可 attach/detach（同一时间通常一个）
 */
object TerminalSession {

    /** 共享终端缓冲（屏幕 + 历史 + 光标/颜色状态） */
    val buffer = TerminalBuffer(30, 80)

    /** 有新输出时通知已附着的视图刷新（回调在 pump 线程，需线程安全） */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** shell 意外退出回调（UI 据此提示），由 TerminalActivity 设置 */
    @Volatile
    var onShellDead: ((exitCode: Int) -> Unit)? = null

    private var ptyFd = -1
    private var pumpThread: Thread? = null

    @Volatile
    private var stopping = false

    @Volatile
    var isRunning = false
        private set

    /** 附着视图：之后每次有输出都会回调 listener（用于 postInvalidate） */
    fun attach(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun detach(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 启动容器 shell（幂等：已有会话则直接复用） */
    fun start(context: Context): Boolean {
        synchronized(this) {
            if (isRunning) return true
            stopping = false
        }
        try {
            val launcher = File(context.applicationInfo.nativeLibraryDir, "libproroot.so")
            val rootfs = ContainerRuntime.rootfsDir(context).absolutePath
            val tmp = ContainerRuntime.tmpDir(context)
            if (!tmp.exists()) tmp.mkdirs()
            val args = listOf(
                launcher.absolutePath,
                "-r", rootfs,
                "-0",
                "--link2symlink",
                "-b", "${tmp.absolutePath}:${ContainerRuntime.HOST_TMP_GUEST}",
                // 默认进入 workspace 目录
                "-w", ContainerRuntime.WORKSPACE_DEFAULT,
                // 用 bash 而非 sh(dash)：readline 行编辑 + 历史命令（↑/↓ 调出）
                "/bin/bash", "-i"
            )
            val prorootCmd = args.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
            val cmd = "export HOME=/root; export LANG=C.UTF-8; " +
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                "export PROROOT_TMP_DIR=${shq(context.filesDir.absolutePath)}; " +
                "export TERM=xterm-256color; " +
                prorootCmd

            val fd = PtyBridge.openSession(cmd)
            if (fd < 0) return false
            synchronized(this) {
                ptyFd = fd
                isRunning = true
            }
            val t = Thread({ pump(fd) }, "terminal-pump")
            pumpThread = t
            t.start()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** 写入字节到 pty（输入/按键） */
    fun write(s: String) {
        if (s.isEmpty()) return
        val fd = ptyFd
        if (fd < 0) return
        try {
            val b = s.toByteArray(Charsets.UTF_8)
            var off = 0
            while (off < b.size) {
                val n = PtyBridge.writeFd(fd, b, off, b.size - off)
                if (n <= 0) break
                off += n
            }
        } catch (e: Exception) {
        }
    }

    /** 同步 pty 窗口尺寸（SIGWINCH） */
    fun resize(rows: Int, cols: Int) {
        val fd = ptyFd
        if (fd >= 0) runCatching { PtyBridge.resize(fd, rows, cols) }
    }

    /** 清屏：清空缓冲（屏幕+历史+滚动），并向 shell 发回车让它重新显示提示符 */
    fun clearScreen() {
        buffer.reset()
        write("\r")
        for (l in listeners) l.invoke()
    }

    /** 结束会话：置停止标志 + 关闭 pty，pump 线程 30ms 内退出 */
    fun stop() {
        val old = synchronized(this) {
            stopping = true
            val fd = ptyFd
            ptyFd = -1
            isRunning = false
            if (fd >= 0) runCatching { PtyBridge.closeFd(fd) }
            pumpThread
        }
        // 非阻塞读下 pump 立即感知停止，join 只需几十毫秒，不会卡 UI
        old?.join(500)
        synchronized(this) {
            pumpThread = null
            stopping = false
        }
    }

    /** pump 线程：非阻塞轮询 pty 输出，写入共享缓冲并通知视图 */
    private fun pump(fd: Int) {
        val buf = ByteArray(4096)
        var eof = false
        while (!stopping && !eof) {
            val n = try {
                PtyBridge.readFd(fd, buf, 0, buf.size)
            } catch (e: Exception) {
                -1
            }
            when {
                n > 0 -> {
                    val data = if (n == buf.size) buf else buf.copyOf(n)
                    buffer.feed(data)
                    for (l in listeners) l.invoke()
                }
                n == -2 -> {
                    // EAGAIN：暂无输出，稍后重试
                    try {
                        Thread.sleep(30)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                else -> eof = true // EOF 或 fd 已关闭
            }
        }
        synchronized(this) {
            if (ptyFd == fd) {
                ptyFd = -1
                isRunning = false
                runCatching { PtyBridge.closeFd(fd) }
            }
        }
        if (!stopping && eof) {
            onShellDead?.invoke(0)
        }
    }

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
