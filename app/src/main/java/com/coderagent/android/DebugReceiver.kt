package com.coderagent.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 调试专用：接收 adb 广播执行容器命令，结果写入 files/dbg_result.txt。
 * 用于无 API key 阶段验证容器运行时，生产发布可移除。
 *
 * 触发示例：
 *   adb shell am broadcast -a com.coderagent.android.DEBUG_RUN \
 *       --es cmd "echo hello; uname -a" --el timeout 120
 */
class DebugReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val cmd: String
        val timeout: Long
        when (action) {
            ACTION_DEBUG_RUN -> {
                cmd = intent.getStringExtra("cmd") ?: "echo hello; uname -a"
                timeout = intent.getLongExtra("timeout", 120)
            }
            ACTION_DEBUG_BOOTSTRAP -> {
                cmd = BOOTSTRAP_SCRIPT
                timeout = 900
            }
            else -> return
        }
        val pending = goAsync()
        scope.launch {
            try {
                val r = ContainerRuntime.exec(context, cmd, "/root", timeoutSec = timeout)
                val out = "exit=${r.exitCode}\ntimedOut=${r.timedOut}\n--- stdout ---\n" +
                    r.stdout + "\n--- stderr ---\n" + r.stderr
                File(context.filesDir, "dbg_result.txt").writeText(out)
                Log.i(TAG, "debug run done, action=$action exit=${r.exitCode}")
            } catch (e: Exception) {
                Log.e(TAG, "debug run failed", e)
                File(context.filesDir, "dbg_result.txt").writeText("exception: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DebugReceiver"
        const val ACTION_DEBUG_RUN = "com.coderagent.android.DEBUG_RUN"
        const val ACTION_DEBUG_BOOTSTRAP = "com.coderagent.android.DEBUG_BOOTSTRAP"

        /** 与 ContainerInstaller.runBootstrap 相同的初始化脚本（强制 https + 多镜像回退 + 基础工具） */
        private const val BOOTSTRAP_SCRIPT = """
            export DEBIAN_FRONTEND=noninteractive
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            mkdir -p /root/workspace
            for m in mirrors.tuna.tsinghua.edu.cn mirrors.aliyun.com mirrors.ustc.edu.cn; do
                for f in /etc/apt/sources.list.d/debian.sources /etc/apt/sources.list; do
                    if [ -f ${'$'}f ]; then
                        sed -i "s|https\?://[^/]*/debian-security|https://${'$'}m/debian-security|g" ${'$'}f
                        sed -i "s|https\?://[^/]*/debian|https://${'$'}m/debian|g" ${'$'}f
                    fi
                done
                apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=60 -o Acquire::https::Verify-Peer=false -o Acquire::https::Verify-Host=false update > /tmp/apt-update.log 2>&1
                if [ ${'$'}? -eq 0 ] && ! grep -qE "Failed to fetch|SSL connection failed|Forbidden" /tmp/apt-update.log; then
                    break
                fi
                echo "[bootstrap] 镜像 ${'$'}m 更新失败，切换下一个镜像"
            done
            cat /tmp/apt-update.log
            apt-get -o Acquire::https::Verify-Peer=false -o Acquire::https::Verify-Host=false install -y --no-install-recommends ca-certificates curl git gawk
        """
    }
}
