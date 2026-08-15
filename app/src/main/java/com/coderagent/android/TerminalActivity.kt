package com.coderagent.android

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.coderagent.android.databinding.ActivityTerminalBinding
import java.io.File

/**
 * 容器终端（Termux UI 移植）：
 * - PTY 会话由进程级单例 TerminalSession 持有，退出界面后命令继续后台运行、
 *   缓冲持续累积；重新打开自动恢复屏幕内容与当前目录
 * - 点击终端弹软键盘直接输入（TerminalView 实现 InputConnection），
 *   底部 ExtraKeys 提供 CTRL/ALT/ESC/TAB/方向键（sticky 修饰键组合）
 * - 只渲染可见区，海量输出不卡 UI；右上角菜单：清屏 / 重启 / 结束会话
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private val uiHandler = Handler(Looper.getMainLooper())

    /** 会话输出 → 视图刷新（pump 线程回调，postInvalidate 线程安全） */
    private val sessionListener: () -> Unit = { binding.terminal.refresh() }

    /** 调试：adb 广播转储当前终端缓冲（adb shell am broadcast -a ...DEBUG_DUMP） */
    private val dumpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val dump = binding.terminal.dumpBuffer()
                File(context.filesDir, "dbg_terminal.txt").writeText(dump)
                Log.i(TAG, "terminal dumped ${dump.length} chars")
            } catch (e: Exception) {
                Log.e(TAG, "dump failed", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ContextCompat.registerReceiver(
            this, dumpReceiver,
            IntentFilter(ACTION_DEBUG_DUMP),
            ContextCompat.RECEIVER_EXPORTED
        )

        if (!ContainerRuntime.isInstalled(this)) {
            binding.terminal.feed(getString(R.string.terminal_not_installed) + "\n")
            return
        }

        // 终端输入接线（全部走会话）
        binding.terminal.writeToPty = { s -> TerminalSession.write(s) }
        binding.terminal.onTap = { binding.terminal.showKeyboard() }
        binding.extraKeys.onSend = { s -> TerminalSession.write(s) }
        binding.extraKeys.onModifier = { ctrl, alt ->
            binding.terminal.extraCtrl = ctrl
            binding.terminal.extraAlt = alt
        }
        // 终端尺寸变化同步给 pty（SIGWINCH）
        binding.terminal.onSize = { r, c -> TerminalSession.resize(r, c) }
        // 选择模式：显示/隐藏复制按钮，复制选中文本到剪贴板
        binding.terminal.onSelectionChanged = { active ->
            binding.btnCopy.visibility = if (active) View.VISIBLE else View.GONE
        }
        binding.btnCopy.setOnClickListener {
            val text = binding.terminal.getSelectionText()
            if (text.isNotBlank()) {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
                Toast.makeText(this, "已复制 ${text.length} 字符", Toast.LENGTH_SHORT).show()
            }
            binding.terminal.clearSelection()
        }

        // 绑定共享会话缓冲（若之前会话仍在运行，直接恢复屏幕内容与目录）
        binding.terminal.bindBuffer(TerminalSession.buffer)
        TerminalSession.onShellDead = { code ->
            uiHandler.post {
                binding.terminal.feed(
                    String.format(getString(R.string.terminal_shell_dead), code) + "\n"
                )
            }
        }
        if (!TerminalSession.isRunning) {
            TerminalSession.start(this)
        }
        TerminalSession.attach(sessionListener)
        binding.terminal.refresh()

        // 外部传入的初始命令（如文件浏览器"终端查看"）
        intent.getStringExtra("init_cmd")?.let { cmd ->
            binding.terminal.postDelayed({ TerminalSession.write(cmd + "\r") }, 500)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_CLEAR, Menu.NONE, getString(R.string.terminal_clear))
        menu.add(Menu.NONE, MENU_RESTART, Menu.NONE, getString(R.string.terminal_restart))
        menu.add(Menu.NONE, MENU_STOP, Menu.NONE, getString(R.string.terminal_stop))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // 返回主界面：会话继续后台运行
                finish(); true
            }
            MENU_CLEAR -> {
                TerminalSession.clearScreen()
                Toast.makeText(this, getString(R.string.terminal_cleared), Toast.LENGTH_SHORT).show()
                true
            }
            MENU_RESTART -> {
                binding.terminal.feed("\n--- 重启 shell ---\n")
                TerminalSession.stop()
                TerminalSession.start(this)
                true
            }
            MENU_STOP -> {
                // 结束会话：终止后台命令并关闭
                TerminalSession.stop()
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(dumpReceiver) }
        TerminalSession.detach(sessionListener)
        TerminalSession.onShellDead = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TerminalActivity"
        private const val ACTION_DEBUG_DUMP = "com.coderagent.android.DEBUG_DUMP"
        private const val MENU_CLEAR = 1
        private const val MENU_RESTART = 2
        private const val MENU_STOP = 3
    }
}
