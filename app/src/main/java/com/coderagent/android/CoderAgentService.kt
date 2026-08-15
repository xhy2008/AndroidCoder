package com.coderagent.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台保活服务：
 * - 前台通知（低打扰）防止系统回收进程
 * - PARTIAL_WAKE_LOCK 防止睡眠时被冻结
 * - 常驻一个容器 shell 会话进程，让 proroot 运行时与 rootfs 保持活跃
 * - START_STICKY：被系统杀死后尝试重建
 */
class CoderAgentService : Service() {

    companion object {
        private const val TAG = "CoderAgentService"
        private const val CHANNEL_ID = "container_keepalive_v2"
        private const val NOTIF_ID = 1001
        private const val ACTION_START = "com.coderagent.android.action.START"
        private const val ACTION_STOP = "com.coderagent.android.action.STOP"

        @Volatile
        private var running = false

        /** 同进程状态监听器：状态变化时即时回调（比系统广播可靠，无投递延迟/丢失） */
        private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        fun start(ctx: Context) {
            val i = Intent(ctx, CoderAgentService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CoderAgentService::class.java).setAction(ACTION_STOP))
        }

        fun isRunning(): Boolean = running

        fun addStateListener(l: () -> Unit) {
            stateListeners.add(l)
        }

        fun removeStateListener(l: () -> Unit) {
            stateListeners.remove(l)
        }

        private fun notifyStateChanged() {
            for (l in stateListeners) {
                runCatching { l.invoke() }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepAliveJob: Job? = null
    private var shellProc: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                notifyStateChanged()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 先拉起前台通知（用户感知"正在运行"的直观信号），
                // 再置运行状态并通知界面，避免指示先于通知变绿造成"通知延迟"错觉
                startForegroundCompat()
                running = true
                notifyStateChanged()
                acquireWakeLock()
                acquireWifiLock()
                startKeepAlive()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        keepAliveJob?.cancel()
        shellProc?.let {
            try {
                it.destroy()
                it.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        createChannel()
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.container_running_notif_title))
            .setContentText(getString(R.string.container_running_notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_LOW 通知状态栏无图标、无任何提示，用户感知为"通知延迟出现"。
        // 用 DEFAULT：状态栏可见图标、立即感知常驻，同时保持静音不打扰。
        // Android 13+ 上 setImportance 对已存在渠道无效，旧版本（LOW）渠道需删除后重建。
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null && existing.importance < NotificationManager.IMPORTANCE_DEFAULT) {
            nm.deleteNotificationChannel(CHANNEL_ID)
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_container),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CoderAgent::ContainerKeepAlive"
            )
        }
        wakeLock?.let {
            if (!it.isHeld) it.acquire()
        }
    }

    /** 获取 WiFi 锁：防止手机灭屏后自动断开 WiFi 导致容器断网 */
    private fun acquireWifiLock() {
        try {
            val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "CoderAgent::KeepWifi"
                )
            }
            wifiLock?.let {
                if (!it.isHeld) it.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取 WiFi 锁失败", e)
        }
    }

    /** 周期检查并维持一个常驻容器 shell 会话 */
    private fun startKeepAlive() {
        if (keepAliveJob != null) return
        keepAliveJob = scope.launch {
            while (isActive) {
                try {
                    if (ContainerRuntime.isInstalled(this@CoderAgentService) &&
                        ContainerRuntime.ensureNative(this@CoderAgentService)
                    ) {
                        val p = shellProc
                        if (p == null || !p.isAlive) {
                            shellProc?.destroyForcibly()
                            shellProc = spawnContainerShell()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "keep-alive 异常", e)
                }
                delay(15_000)
            }
        }
    }

    /**
     * 常驻 shell：进程保持存活即让 proroot 运行时驻留内存、
     * rootfs 保持热状态。stdin 保留用于将来扩展。
     */
    private fun spawnContainerShell(): Process? {
        return try {
            val launcher = java.io.File(applicationInfo.nativeLibraryDir, "libproroot.so")
            val rootfs = ContainerRuntime.rootfsDir(this).absolutePath
            val cmd = listOf(
                launcher.absolutePath,
                "-r", rootfs,
                "-0",
                "--link2symlink",
                "-w", "/root",
                "/bin/sh", "-i"
            )
            val pb = ProcessBuilder(cmd)
            pb.environment()["PROROOT_TMP_DIR"] = filesDir.absolutePath
            pb.environment()["HOME"] = "/root"
            pb.environment()["LANG"] = "C.UTF-8"
            pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            val proc = pb.start()
            // 喂一条空命令验证 shell 响应，不阻塞
            proc.outputStream.write("echo __alive__\n".toByteArray())
            proc.outputStream.flush()
            Log.i(TAG, "常驻容器 shell 已启动")
            proc
        } catch (e: Exception) {
            Log.e(TAG, "启动常驻容器 shell 失败", e)
            null
        }
    }
}
