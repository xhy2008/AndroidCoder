package com.coderagent.android

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** 一次命令执行的结果 */
data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
) {
    val output: String get() = if (stdout.isNotBlank()) stdout else stderr
}

/**
 * 基于 proroot（LD_PRELOAD + 二进制补丁，零 ptrace 开销）的根less Linux 容器运行时。
 *
 * 目录布局：
 *   files/rootfs  —— Debian rootfs（从 Docker Hub OCI 镜像解压）
 *   files/native  —— proroot 的 5 个 .so（从 APK 原生库复制，供直接 exec）
 *   files/tmp     —— 宿主机临时文件，经 bind mount 暴露给容器（大文件传输通道）
 */
object ContainerRuntime {
    private const val TAG = "ContainerRuntime"

    const val ROOTFS = "rootfs"
    const val NATIVE = "native"
    const val TMP = "tmp"
    const val HOST_TMP_GUEST = "/hosttmp"
    const val WORKSPACE_DEFAULT = "/root/workspace"

    private const val LAUNCHER = "libproroot.so"
    private val NATIVE_LIBS = listOf(
        "libproroot.so",
        "libproroot-runtime.so",
        "libproroot-bridge.so",
        "libproroot-linker.so",
        "libproroot-stub-loader.so"
    )

    fun rootfsDir(ctx: Context): File = File(ctx.filesDir, ROOTFS)
    fun tmpDir(ctx: Context): File = File(ctx.filesDir, TMP)

    fun isInstalled(ctx: Context): Boolean =
        File(rootfsDir(ctx), "etc/os-release").exists() &&
            File(rootfsDir(ctx), "bin/sh").exists()

    /**
     * 确认 proroot 原生库就绪。
     *
     * launcher 必须从 APK 的 nativeLibraryDir 执行：Android 10+ 禁止应用
     * （untrusted_app）从私有数据目录 exec 二进制（SELinux 无 execute 权限），
     * 而 nativeLibraryDir（apk_data_file）允许 execute。
     * rootfs 只需 read —— proroot 在内存中读取并补丁 ELF，无需 exec rootfs 文件。
     */
    fun ensureNative(ctx: Context): Boolean {
        val src = ctx.applicationInfo.nativeLibraryDir
        val missing = NATIVE_LIBS.filter { !File(src, it).exists() }
        if (missing.isNotEmpty()) Log.e(TAG, "nativeLibraryDir 缺少 proroot 原生库: $missing")
        return missing.isEmpty()
    }

    private fun launcherFile(ctx: Context): File =
        File(ctx.applicationInfo.nativeLibraryDir, LAUNCHER)

    /**
     * 在容器内同步执行 shell 命令。
     * @param cwd 容器内工作目录
     * @param timeoutSec 超时（秒），<=0 表示不限制
     * @param bindMounts 额外的 host:guest bind 挂载
     */
    fun exec(
        ctx: Context,
        command: String,
        cwd: String = "/root",
        timeoutSec: Long = 120,
        env: Map<String, String> = emptyMap(),
        bindMounts: List<Pair<String, String>> = emptyList()
    ): ExecResult {
        val launcher = launcherFile(ctx)
        if (!launcher.exists()) {
            Log.e(TAG, "proroot launcher 不存在，请先调用 ensureNative")
            return ExecResult(127, "", "proroot launcher not found")
        }
        val cmd = mutableListOf(
            launcher.absolutePath,
            "-r", rootfsDir(ctx).absolutePath,
            "-0",
            "--link2symlink"
        )
        val tmp = tmpDir(ctx)
        if (!tmp.exists()) tmp.mkdirs()
        cmd += listOf("-b", "${tmp.absolutePath}:$HOST_TMP_GUEST")
        for ((h, g) in bindMounts) cmd += listOf("-b", "$h:$g")
        cmd += listOf("-w", cwd, "/bin/sh", "-c", command)

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(false)
        val pbEnv = pb.environment()
        pbEnv["PROROOT_TMP_DIR"] = ctx.filesDir.absolutePath
        pbEnv["HOME"] = "/root"
        pbEnv["LANG"] = "C.UTF-8"
        pbEnv["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        pbEnv.putAll(env)

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "启动 proroot 失败", e)
            return ExecResult(127, "", "failed to start proroot: ${e.message}")
        }

        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val outThread = Thread {
            proc.inputStream.bufferedReader().use { r -> r.forEachLine { stdoutBuf.appendLine(it) } }
        }
        val errThread = Thread {
            proc.errorStream.bufferedReader().use { r -> r.forEachLine { stderrBuf.appendLine(it) } }
        }
        outThread.start()
        errThread.start()

        var timedOut = false
        val finished = if (timeoutSec > 0) {
            proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        } else {
            proc.waitFor(); true
        }
        val exit = if (finished) {
            proc.exitValue()
        } else {
            timedOut = true
            proc.destroy()
            proc.waitFor(3, TimeUnit.SECONDS)
            proc.destroyForcibly()
            -1
        }
        outThread.join(3000)
        errThread.join(3000)
        return ExecResult(exit, stdoutBuf.toString(), stderrBuf.toString(), timedOut)
    }

    /** 可取消的 exec（用于 run_command 工具，支持停止按钮） */
    suspend fun execSuspend(
        ctx: Context,
        command: String,
        cwd: String = "/root",
        timeoutSec: Long = 120,
        bindMounts: List<Pair<String, String>> = emptyList()
    ): ExecResult = suspendCancellableCoroutine { cont ->
        val launcher = launcherFile(ctx)
        if (!launcher.exists()) {
            cont.resume(ExecResult(127, "", "proroot launcher not found"))
            return@suspendCancellableCoroutine
        }
        val cmd = mutableListOf(
            launcher.absolutePath,
            "-r", rootfsDir(ctx).absolutePath,
            "-0",
            "--link2symlink"
        )
        val tmp = tmpDir(ctx)
        if (!tmp.exists()) tmp.mkdirs()
        cmd += listOf("-b", "${tmp.absolutePath}:$HOST_TMP_GUEST")
        for ((h, g) in bindMounts) cmd += listOf("-b", "$h:$g")
        cmd += listOf("-w", cwd, "/bin/sh", "-c", command)

        val proc = try {
            ProcessBuilder(cmd).apply {
                redirectErrorStream(false)
                environment()["PROROOT_TMP_DIR"] = ctx.filesDir.absolutePath
                environment()["HOME"] = "/root"
                environment()["LANG"] = "C.UTF-8"
                environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            }.start()
        } catch (e: Exception) {
            cont.resume(ExecResult(127, "", "failed to start proroot: ${e.message}"))
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            try {
                proc.destroy()
                proc.waitFor(3, TimeUnit.SECONDS)
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
        }

        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val worker = Thread {
            try {
                proc.inputStream.bufferedReader().use { r -> r.forEachLine { stdoutBuf.appendLine(it) } }
            } catch (_: Exception) {
            }
        }
        val worker2 = Thread {
            try {
                proc.errorStream.bufferedReader().use { r -> r.forEachLine { stderrBuf.appendLine(it) } }
            } catch (_: Exception) {
            }
        }
        worker.start()
        worker2.start()
        Thread {
            var timedOut = false
            val finished = if (timeoutSec > 0) {
                try { proc.waitFor(timeoutSec, TimeUnit.SECONDS) } catch (_: Exception) { true }
            } else {
                try { proc.waitFor(); true } catch (_: Exception) { true }
            }
            val exit = if (finished) {
                try { proc.exitValue() } catch (_: Exception) { -1 }
            } else {
                timedOut = true
                try { proc.destroy(); proc.waitFor(3, TimeUnit.SECONDS); proc.destroyForcibly() } catch (_: Exception) {}
                -1
            }
            worker.join(3000)
            worker2.join(3000)
            if (cont.isActive) {
                cont.resume(ExecResult(exit, stdoutBuf.toString(), stderrBuf.toString(), timedOut))
            }
        }.start()
    }

    /**
     * 流式 exec：stdout/stderr 逐行实时回调（回调在读取线程，调用方自行切线程）。
     * 仍返回完整 ExecResult（含累积输出），供最终结果展示。
     */
    suspend fun execStreaming(
        ctx: Context,
        command: String,
        cwd: String = "/root",
        timeoutSec: Long = 120,
        bindMounts: List<Pair<String, String>> = emptyList(),
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit = {}
    ): ExecResult = suspendCancellableCoroutine { cont ->
        val launcher = launcherFile(ctx)
        if (!launcher.exists()) {
            cont.resume(ExecResult(127, "", "proroot launcher not found"))
            return@suspendCancellableCoroutine
        }
        val cmd = mutableListOf(
            launcher.absolutePath,
            "-r", rootfsDir(ctx).absolutePath,
            "-0",
            "--link2symlink"
        )
        val tmp = tmpDir(ctx)
        if (!tmp.exists()) tmp.mkdirs()
        cmd += listOf("-b", "${tmp.absolutePath}:$HOST_TMP_GUEST")
        for ((h, g) in bindMounts) cmd += listOf("-b", "$h:$g")
        cmd += listOf("-w", cwd, "/bin/sh", "-c", command)

        val proc = try {
            ProcessBuilder(cmd).apply {
                redirectErrorStream(false)
                environment()["PROROOT_TMP_DIR"] = ctx.filesDir.absolutePath
                environment()["HOME"] = "/root"
                environment()["LANG"] = "C.UTF-8"
                environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            }.start()
        } catch (e: Exception) {
            cont.resume(ExecResult(127, "", "failed to start proroot: ${e.message}"))
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            try {
                proc.destroy()
                proc.waitFor(3, TimeUnit.SECONDS)
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
        }

        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val worker = Thread {
            try {
                proc.inputStream.bufferedReader().use { r ->
                    r.forEachLine { line ->
                        stdoutBuf.appendLine(line)
                        runCatching { onStdout(line) }
                    }
                }
            } catch (_: Exception) {
            }
        }
        val worker2 = Thread {
            try {
                proc.errorStream.bufferedReader().use { r ->
                    r.forEachLine { line ->
                        stderrBuf.appendLine(line)
                        runCatching { onStderr(line) }
                    }
                }
            } catch (_: Exception) {
            }
        }
        worker.start()
        worker2.start()
        Thread {
            var timedOut = false
            val finished = if (timeoutSec > 0) {
                try { proc.waitFor(timeoutSec, TimeUnit.SECONDS) } catch (_: Exception) { true }
            } else {
                try { proc.waitFor(); true } catch (_: Exception) { true }
            }
            val exit = if (finished) {
                try { proc.exitValue() } catch (_: Exception) { -1 }
            } else {
                timedOut = true
                try { proc.destroy(); proc.waitFor(3, TimeUnit.SECONDS); proc.destroyForcibly() } catch (_: Exception) {}
                -1
            }
            worker.join(3000)
            worker2.join(3000)
            if (cont.isActive) {
                cont.resume(ExecResult(exit, stdoutBuf.toString(), stderrBuf.toString(), timedOut))
            }
        }.start()
    }
}
