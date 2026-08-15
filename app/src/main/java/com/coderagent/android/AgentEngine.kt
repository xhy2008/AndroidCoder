package com.coderagent.android

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 模型发起的工具调用 */
data class ToolCall(val id: String, val name: String, val arguments: String)

/** 单次请求的 token 用量（deepseek 缓存命中计 prompt_cache_hit_tokens） */
data class Usage(val inputTokens: Int, val outputTokens: Int, val cacheHitTokens: Int)

/** Agent 运行事件，驱动 UI 实时刷新（流式增量） */
sealed class AgentEvent {
    /** 思考过程增量（deepseek reasoning_content，逐字追加） */
    data class ReasoningDelta(val text: String) : AgentEvent()

    /** 回答正文增量（逐字追加） */
    data class TextDelta(val text: String) : AgentEvent()

    /** 最终回答流式结束（text 为最终正文） */
    data class TextDone(val text: String) : AgentEvent()

    data class ToolStart(val name: String, val argsSummary: String) : AgentEvent()

    /** 工具执行中的实时输出增量（run_command 的 stdout/stderr 行） */
    data class ToolDelta(val text: String) : AgentEvent()

    data class ToolResult(val name: String, val result: String) : AgentEvent()

    /** 本轮（整次对话）汇总统计 */
    data class RoundStats(val totalInput: Int, val totalOutput: Int, val cacheHit: Int, val elapsedMs: Long) : AgentEvent()

    data class Error(val message: String) : AgentEvent()
    data class LimitReached(val text: String) : AgentEvent()
}

/**
 * Agent 引擎：OpenAI 兼容 chat/completions，SSE 流式输出 + 函数调用循环。
 * 请求体前缀（system prompt + 历史消息 + tools 定义 + 固定 temperature）保持稳定，
 * 以命中 deepseek 的自动前缀缓存；usage 统计随最后一个 chunk 返回。
 */
object AgentEngine {
    private const val TAG = "AgentEngine"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // 流式：read 超时交给 callTimeout 兜底
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentCall: Call? = null

    /** 中断当前请求（停止按钮） */
    fun cancelCurrent() {
        currentCall?.cancel()
    }

    fun systemPrompt(cfg: AgentConfig): String = """
你是Android-Coder,一位话少、图省事的资深AI工程师。无论输出还是思考，都保持极致简洁。
你有一个运行在手机上的debian容器环境，有root权限。
【思考】
- 思考过程用简短的逻辑表达，不用完整句子复述问题。
- 先读代码、弄清真实流程，再想方案；想清楚了立刻动手，不多想。
【代码】
- 只写最小可行代码。取舍顺序：不需要就砍掉（YAGNI）>复用现有代码 > 标准库 > 原生平台能力 > 已有依赖 > 一行代码 > 最小实现。
- 永不省略：信任边界的输入校验、防数据丢失的处理、安全、无障碍、以及用户明确要求的内容。
- 用户需求过度设计？指出更简替代。
- 非平凡逻辑必须留一个可运行的自检（assert 自检或一个小测试），不引入框架。
- 修 bug 修根因，不修症状：在共享函数里加一道防护，胜过在每个调用处打补丁。
【回复】
- 完成任务后写简单说明：完成了什么、跳过了什么。
- 不写长篇、不堆客套、不写设计说明，除非用户询问。
- 去掉其实/基本上/我们来看一下之类的废话；用短词；技术术语保持准确。
【例外——绝不含糊】
- 安全警告、不可逆/破坏性操作、任何说少了会误导的场景，必须完整明说。
【工作提示】
- 查看代码前先用 glob 定位文件，再用 grep 检索内容，最后按行范围读取。
- 修改前先读取相关部分理解上下文；写完后可用 grep 或 run_command 验证。
- 工具输出可能被截断，请避免让命令产生大量输出或一次读取超大文件。
【长期记忆】
- 用文件工具维护/root/MEMORY.md 作为长期记忆。
- 对话中出现值得长期保留的信息（用户偏好、项目约定、关键决策、踩坑经历）时，更新 MEMORY.md；
- 处理相关任务前先读取它，如果发现过时内容请删除。
    """.trimIndent()

    /** 完整 agent 循环（可取消：外层协程取消即停止） */
    suspend fun run(
        ctx: Context,
        cfg: AgentConfig,
        userTask: String,
        onEvent: (AgentEvent) -> Unit
    ) {
        val messages = mutableListOf<JSONObject>()
        messages += msg("system", systemPrompt(cfg))
        messages += msg("user", userTask)

        val startMs = SystemClock.elapsedRealtime()
        var totalIn = 0
        var totalOut = 0
        var totalCacheHit = 0

        try {
            for (round in 0 until cfg.maxIterations) {
                currentCoroutineContext().ensureActive()

                val reasoning = StringBuilder()
                val content = StringBuilder()
                // 流式 tool_calls 按 index 累积：[id, name, arguments 增量拼接]
                val toolCalls = mutableMapOf<Int, Array<String>>()

                val usage = chatStreaming(ctx, cfg, messages) { delta ->
                    val dr = delta.optStr("reasoning_content")
                    val dc = delta.optStr("content")
                    if (dr.isNotEmpty()) {
                        reasoning.append(dr)
                        onEvent(AgentEvent.ReasoningDelta(dr))
                    }
                    if (dc.isNotEmpty()) {
                        content.append(dc)
                        onEvent(AgentEvent.TextDelta(dc))
                    }
                    val tcs = delta.optJSONArray("tool_calls")
                    if (tcs != null) {
                        for (i in 0 until tcs.length()) {
                            val tc = tcs.getJSONObject(i)
                            val idx = tc.optInt("index")
                            val e = toolCalls.getOrPut(idx) { arrayOf("", "", "") }
                            // id 只在首个 chunk 出现，后续增量 chunk 无 id（为空串），不可覆盖已攒的 id
                            val id = tc.optStr("id")
                            if (id.isNotEmpty()) e[0] = id
                            val fn = tc.optJSONObject("function")
                            if (fn != null) {
                                val n = fn.optStr("name")
                                if (n.isNotEmpty()) e[1] += n
                                e[2] += fn.optStr("arguments")
                            }
                        }
                    }
                }
                totalIn += usage.inputTokens
                totalOut += usage.outputTokens
                totalCacheHit += usage.cacheHitTokens

                val calls = toolCalls.toSortedMap().map { (idx, e) ->
                    // id 缺失时用带 index 的兜底值，确保并行工具调用 id 唯一
                    ToolCall(
                        e[0].ifBlank { "call_${round}_$idx" },
                        e[1],
                        e[2].ifBlank { "{}" }
                    )
                }
                // 思考模式下 reasoning_content 必须原样回传，否则后续请求被 API 拒绝
                val reasoningText = reasoning.toString().ifBlank { null }
                messages += msg(
                    "assistant", content.toString(), calls.ifEmpty { null },
                    reasoningContent = reasoningText
                )

                // 每轮流结束：封口当前正文气泡（与后续工具调用按时间顺序交替排列）
                if (content.isNotEmpty()) onEvent(AgentEvent.TextDone(content.toString()))

                if (calls.isEmpty()) break

                for (tc in calls) {
                    currentCoroutineContext().ensureActive()
                    onEvent(AgentEvent.ToolStart(tc.name, tc.arguments))
                    val args = try {
                        JSONObject(tc.arguments)
                    } catch (e: Exception) {
                        JSONObject()
                    }
                    // 流式执行：run_command 实时推送 stdout/stderr 行
                    val result = ToolRegistry.executeStreaming(ctx, tc.name, args, cfg.workspace) { delta ->
                        android.util.Log.d("ToolStream", "agent toolDelta len=${delta.length}")
                        onEvent(AgentEvent.ToolDelta(delta))
                    }
                    onEvent(AgentEvent.ToolResult(tc.name, result))
                    messages += msg("tool", result, toolCallId = tc.id, name = tc.name)
                }
            }
            onEvent(
                AgentEvent.RoundStats(
                    totalIn, totalOut, totalCacheHit,
                    SystemClock.elapsedRealtime() - startMs
                )
            )
        } catch (ce: CancellationException) {
            onEvent(AgentEvent.Error("已停止执行"))
            throw ce
        } catch (e: Exception) {
            if (currentCall?.isCanceled() == true || e.message?.contains("Canceled") == true) {
                onEvent(AgentEvent.Error("已停止执行"))
            } else {
                Log.e(TAG, "agent 循环异常", e)
                onEvent(AgentEvent.Error("Agent 运行出错: ${e.message}"))
            }
        }
    }

    /**
     * SSE 流式请求，逐 chunk 回调 delta；返回该请求的 usage。
     * 命中前缀缓存时 deepseek 返回 prompt_cache_hit_tokens。
     */
    private suspend fun chatStreaming(
        ctx: Context,
        cfg: AgentConfig,
        messages: List<JSONObject>,
        onDelta: (JSONObject) -> Unit
    ): Usage = withContext(Dispatchers.IO) {
        val url = "${cfg.baseUrl}/chat/completions"
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", JSONArray().apply { for (m in messages) put(m) })
            .put("tools", ToolRegistry.toOpenAiArray())
            .put("tool_choice", "auto")
            .put("temperature", 0)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
        // 推理强度：auto 时不传（官方按任务自动调整，复杂 agent 请求自动 max）
        if (cfg.reasoningEffort in setOf("low", "high", "max")) {
            body.put(
                "thinking", JSONObject()
                    .put("type", "enabled")
                    .put("reasoning_effort", cfg.reasoningEffort)
            )
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val call = client.newCall(req)
        currentCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException(
                        "API 请求失败 (HTTP ${resp.code}): ${resp.body?.string().orEmpty().take(800)}"
                    )
                }
                val source = resp.body?.source()
                    ?: throw IOException("响应体为空")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val root = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    // usage：stream_options.include_usage 时随最后一个 chunk 返回
                    val usage = root.optJSONObject("usage")
                    if (usage != null) {
                        // 各家缓存字段名不同：DeepSeek 用 prompt_cache_hit_tokens，
                        // OpenAI 风格（如 Agnes）用 prompt_tokens_details.cached_tokens，优先兼容后者
                        val details = usage.optJSONObject("prompt_tokens_details")
                        val cached = if (details != null) {
                            details.optInt("cached_tokens", 0)
                        } else {
                            usage.optInt("prompt_cache_hit_tokens", 0)
                        }
                        return@withContext Usage(
                            inputTokens = usage.optInt("prompt_tokens", 0),
                            outputTokens = usage.optInt("completion_tokens", 0),
                            cacheHitTokens = cached
                        )
                    }
                    val choices = root.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val delta = choices.getJSONObject(0).optJSONObject("delta")
                        if (delta != null) onDelta(delta)
                    }
                }
                Usage(0, 0, 0)
            }
        } finally {
            if (currentCall === call) currentCall = null
        }
    }

    /** JSONObject 取值：键存在但值为 null 时返回空串（避免 optString 返回 "null"） */
    private fun JSONObject.optStr(key: String): String =
        if (isNull(key)) "" else optString(key, "")

    private fun msg(
        role: String,
        content: String,
        toolCalls: List<ToolCall>? = null,
        toolCallId: String? = null,
        name: String? = null,
        reasoningContent: String? = null
    ): JSONObject {
        val m = JSONObject().put("role", role)
        if (role == "tool") {
            m.put("tool_call_id", toolCallId)
            m.put("content", content)
            if (!name.isNullOrBlank()) m.put("name", name)
            return m
        }
        m.put("content", content)
        // 思考模式：assistant 消息需回传 reasoning_content
        if (!reasoningContent.isNullOrBlank()) m.put("reasoning_content", reasoningContent)
        if (!toolCalls.isNullOrEmpty()) {
            m.put(
                "tool_calls", JSONArray().apply {
                    for (tc in toolCalls) {
                        put(
                            JSONObject()
                                .put("id", tc.id)
                                .put("type", "function")
                                .put(
                                    "function", JSONObject()
                                        .put("name", tc.name)
                                        .put("arguments", tc.arguments)
                                )
                        )
                    }
                }
            )
        }
        return m
    }
}
