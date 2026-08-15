#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <stdlib.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>

/*
 * PTY 桥接（参照 Termux ShellTermSession / termux-core 的实现思路）：
 * - openpty 创建伪终端对
 * - fork 子进程：setsid + TIOCSCTTY + dup2 到 0/1/2 后 exec 命令
 * - 父进程持有 master fd，Java 侧通过 readFd/writeFd/resize 操作
 * 子进程的 stdin/stdout/stderr 都是真实 tty，因此容器内 shell 获得
 * 交互模式、job control 与程序自身回显（Termux 同款体验）。
 */

JNIEXPORT jint JNICALL
Java_com_coderagent_android_PtyBridge_openSession(JNIEnv *env, jclass clazz, jstring cmd) {
    int master, slave;
    char name[256];
    if (openpty(&master, &slave, name, NULL, NULL) < 0) {
        return -1;
    }
    // master 设为非阻塞：Java 侧 pump 线程 30ms 轮询，
    // 关闭 fd 时能立即退出，避免阻塞 read 无法被 close 中断导致 join 卡住
    int flags = fcntl(master, F_GETFL);
    if (flags >= 0) {
        fcntl(master, F_SETFL, flags | O_NONBLOCK);
    }

    const char *cmdStr = (*env)->GetStringUTFChars(env, cmd, NULL);
    if (cmdStr == NULL) {
        close(master);
        close(slave);
        return -1;
    }

    pid_t pid = fork();
    if (pid == 0) {
        /* 子进程 */
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        close(master);
        execl("/system/bin/sh", "sh", "-c", cmdStr, (char *)NULL);
        _exit(127);
    }

    close(slave);
    (*env)->ReleaseStringUTFChars(env, cmd, cmdStr);

    if (pid < 0) {
        close(master);
        return -1;
    }
    return master;
}

JNIEXPORT jint JNICALL
Java_com_coderagent_android_PtyBridge_readFd(JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint off, jint len) {
    jbyte *p = (*env)->GetByteArrayElements(env, buf, NULL);
    if (p == NULL) return -1;
    ssize_t n = read(fd, p + off, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, buf, p, 0);
    if (n < 0) {
        // 非阻塞下无数据可读返回 -2（EAGAIN），调用方应稍后重试；
        // 其余错误返回 -1（fd 已关闭等）
        if (errno == EAGAIN || errno == EWOULDBLOCK) return -2;
        return -1;
    }
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_coderagent_android_PtyBridge_writeFd(JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint off, jint len) {
    jbyte *p = (*env)->GetByteArrayElements(env, buf, NULL);
    if (p == NULL) return -1;
    ssize_t n = write(fd, p + off, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, buf, p, 0);
    return (jint)n;
}

JNIEXPORT void JNICALL
Java_com_coderagent_android_PtyBridge_closeFd(JNIEnv *env, jclass clazz, jint fd) {
    close(fd);
}

JNIEXPORT void JNICALL
Java_com_coderagent_android_PtyBridge_resize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}
