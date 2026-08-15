package com.coderagent.android

/**
 * PTY 桥接（JNI，见 src/main/cpp/pty.c）：
 * openSession 创建伪终端并 fork+exec 命令，返回 master fd；
 * 之后通过 readFd/writeFd/resize/closeFd 操作会话。
 */
object PtyBridge {
    init {
        System.loadLibrary("coderagent_pty")
    }

    external fun openSession(cmd: String): Int
    external fun readFd(fd: Int, buf: ByteArray, off: Int, len: Int): Int
    external fun writeFd(fd: Int, buf: ByteArray, off: Int, len: Int): Int
    external fun closeFd(fd: Int)
    external fun resize(fd: Int, rows: Int, cols: Int)
}
