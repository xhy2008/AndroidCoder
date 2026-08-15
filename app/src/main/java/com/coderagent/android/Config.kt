package com.coderagent.android

import android.content.Context
import android.content.SharedPreferences

/** Agent 配置（OpenAI 兼容 API）。工作区固定为 /root/workspace，不可配置。 */
data class AgentConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val maxIterations: Int,
    /** 推理强度：auto（不传，官方自动）/ low / high / max */
    val reasoningEffort: String
) {
    val workspace: String get() = ContainerRuntime.WORKSPACE_DEFAULT
}

object Config {
    private const val PREFS = "agent_config"
    private const val K_BASE = "base_url"
    private const val K_KEY = "api_key"
    private const val K_MODEL = "model"
    private const val K_MAX = "max_iterations"
    private const val K_EFFORT = "reasoning_effort"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): AgentConfig {
        val p = prefs(ctx)
        return AgentConfig(
            baseUrl = p.getString(K_BASE, "https://api.deepseek.com/v1")!!,
            apiKey = p.getString(K_KEY, "")!!,
            model = p.getString(K_MODEL, "deepseek-v4-flash")!!,
            maxIterations = p.getInt(K_MAX, 200),
            reasoningEffort = p.getString(K_EFFORT, "auto")!!
        )
    }

    fun save(ctx: Context, cfg: AgentConfig) {
        prefs(ctx).edit()
            .putString(K_BASE, cfg.baseUrl.trim().trimEnd('/'))
            .putString(K_KEY, cfg.apiKey.trim())
            .putString(K_MODEL, cfg.model.trim())
            .putInt(K_MAX, cfg.maxIterations.coerceIn(1, 1000))
            .putString(K_EFFORT, cfg.reasoningEffort)
            .apply()
    }

    fun isConfigured(ctx: Context): Boolean {
        val c = load(ctx)
        return c.apiKey.isNotBlank() && c.baseUrl.startsWith("http")
    }
}
