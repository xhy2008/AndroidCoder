package com.coderagent.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.coderagent.android.databinding.ActivitySetupBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 首次配置 / 设置页：
 * 配置 OpenAI 兼容 API，并负责容器安装（下载 Debian rootfs + 初始化）。
 * SettingsActivity 以 from_settings 模式复用本页。
 */
open class SetupActivity : AppCompatActivity() {

    protected lateinit var binding: ActivitySetupBinding
    protected var fromSettings: Boolean = false
    private var installJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fromSettings = intent.getBooleanExtra(SettingsActivity.EXTRA_FROM_SETTINGS, false)
        setTitle(if (fromSettings) R.string.settings_title else R.string.setup_title)

        // 预填已有配置
        val cfg = Config.load(this)
        binding.etBaseUrl.setText(cfg.baseUrl)
        binding.etApiKey.setText(cfg.apiKey)
        binding.etModel.setText(cfg.model)
        binding.etMaxIter.setText(cfg.maxIterations.toString())
        when (cfg.reasoningEffort) {
            "low" -> binding.rbEffortLow.isChecked = true
            "high" -> binding.rbEffortHigh.isChecked = true
            "max" -> binding.rbEffortMax.isChecked = true
            else -> binding.rbEffortAuto.isChecked = true
        }

        updateContainerStatus()
        binding.btnSave.setOnClickListener { saveAndContinue() }
        binding.btnInstall.setOnClickListener { confirmInstall() }
        binding.btnReset.setOnClickListener { confirmReset() }
    }

    private fun updateContainerStatus() {
        binding.tvContainerStatus.text = if (ContainerRuntime.isInstalled(this)) {
            getString(R.string.container_status, getString(R.string.status_installed))
        } else {
            getString(R.string.container_status, getString(R.string.status_not_installed))
        }
    }

    private fun collectConfig(): AgentConfig {
        val base = binding.etBaseUrl.text?.toString()?.trim().orEmpty()
        val key = binding.etApiKey.text?.toString()?.trim().orEmpty()
        val model = binding.etModel.text?.toString()?.trim().orEmpty()
        val maxIter = binding.etMaxIter.text?.toString()?.trim()?.toIntOrNull() ?: 200
        val effort = when {
            binding.rbEffortLow.isChecked -> "low"
            binding.rbEffortHigh.isChecked -> "high"
            binding.rbEffortMax.isChecked -> "max"
            else -> "auto"
        }
        return AgentConfig(
            baseUrl = base,
            apiKey = key,
            model = model,
            maxIterations = maxIter,
            reasoningEffort = effort
        )
    }

    private fun saveAndContinue() {
        val cfg = collectConfig()
        if (cfg.baseUrl.isBlank() || cfg.apiKey.isBlank() || cfg.model.isBlank()) {
            Toast.makeText(this, "请填写 Base URL、API Key 与模型", Toast.LENGTH_SHORT).show()
            return
        }
        Config.save(this, cfg)
        if (ContainerRuntime.isInstalled(this)) {
            CoderAgentService.start(this)
            gotoMain()
        } else {
            binding.btnInstall.visibility = android.view.View.VISIBLE
            Toast.makeText(this, "配置已保存。容器尚未安装，请点击下方按钮安装", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmInstall() {
        if (ContainerRuntime.isInstalled(this)) {
            AlertDialog.Builder(this)
                .setTitle("重新安装容器")
                .setMessage("将删除现有 rootfs 并重新下载安装（当前工作区文件会被清空）。确定继续？")
                .setPositiveButton("重新安装") { _, _ ->
                    val rootfs = ContainerRuntime.rootfsDir(this)
                    if (rootfs.exists()) rootfs.deleteRecursively()
                    install()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            install()
        }
    }

    private fun install() {
        if (installJob?.isActive == true) return
        binding.btnInstall.isEnabled = false
        binding.btnSave.isEnabled = false
        binding.btnReset.isEnabled = false
        binding.progress.visibility = android.view.View.VISIBLE
        binding.progress.max = 100
        appendLog("开始安装 Debian 容器…")

        installJob = lifecycleScope.launch {
            try {
                ContainerInstaller.install(this@SetupActivity) { phase, frac ->
                    runOnUiThread {
                        binding.tvContainerStatus.text = phase
                        binding.progress.isIndeterminate = frac <= 0f
                        if (frac > 0f) binding.progress.progress = (frac * 100).toInt()
                        appendLog(phase)
                    }
                }
                runOnUiThread {
                    appendLog("✅ 容器安装完成")
                    updateContainerStatus()
                    CoderAgentService.start(this@SetupActivity)
                    gotoMain()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("❌ 安装失败：${e.message}")
                    updateContainerStatus()
                    Toast.makeText(this@SetupActivity, "安装失败，请查看日志", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    binding.progress.visibility = android.view.View.GONE
                    binding.btnInstall.isEnabled = true
                    binding.btnSave.isEnabled = true
                    binding.btnReset.isEnabled = true
                }
            }
        }
    }

    /** 彻底重置容器：二次确认后删除 rootfs 与安装状态（API 配置保留） */
    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_confirm)
            .setPositiveButton(R.string.reset_confirm_btn) { _, _ -> doReset() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doReset() {
        CoderAgentService.stop(this)
        val rootfs = ContainerRuntime.rootfsDir(this)
        if (rootfs.exists()) rootfs.deleteRecursively()
        updateContainerStatus()
        binding.btnInstall.visibility = android.view.View.VISIBLE
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_LONG).show()
    }

    private fun appendLog(line: String) {
        binding.tvLog.append(line + "\n")
        val lines = binding.tvLog.text.toString().split("\n")
        if (lines.size > 120) {
            binding.tvLog.text = lines.takeLast(100).joinToString("\n")
        }
    }

    protected fun gotoMain() {
        if (fromSettings) {
            finish()
        } else {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onDestroy() {
        installJob?.cancel()
        super.onDestroy()
    }
}
