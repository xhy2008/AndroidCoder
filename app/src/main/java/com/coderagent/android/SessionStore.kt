package com.coderagent.android

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话（上下文）持久化。
 *
 * 每个会话一个 JSON 文件（files/sessions/<id>.json），内容含 id/title/updatedAt 与完整的
 * ChatItem 列表。任务完成或打断后调用 [save] 落盘，切换会话时 [load] 恢复继续对话。
 */
object SessionStore {

    data class SessionMeta(val id: String, val title: String, val updatedAt: Long)

    fun dir(ctx: Context): File = File(ctx.filesDir, "sessions")

    /** 保存会话到文件，并刷新元信息 */
    fun save(ctx: Context, id: String, title: String, items: List<ChatItem>) {
        val d = dir(ctx)
        d.mkdirs()
        val json = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("updatedAt", System.currentTimeMillis())
            .put("items", JSONArray().apply { for (it in items) put(it.toJson()) })
        File(d, "$id.json").writeText(json.toString())
    }

    /** 加载会话消息列表，损坏或不存在返回 null */
    fun load(ctx: Context, id: String): List<ChatItem>? = runCatching {
        val json = JSONObject(File(dir(ctx), "$id.json").readText())
        val arr = json.getJSONArray("items")
        (0 until arr.length()).map { arr.getJSONObject(it).toChatItem() }
    }.getOrNull()

    /** 会话列表（按更新时间倒序） */
    fun list(ctx: Context): List<SessionMeta> =
        dir(ctx).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { f ->
                runCatching {
                    val j = JSONObject(f.readText())
                    SessionMeta(
                        j.optString("id"),
                        j.optString("title").ifBlank { "未命名对话" },
                        j.optLong("updatedAt")
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
    }

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    // ---------- ChatItem 编解码 ----------

    private fun ChatItem.toJson(): JSONObject = when (this) {
        is ChatItem.User -> JSONObject().put("type", "user").put("text", text)
        is ChatItem.Assistant -> JSONObject().put("type", "assistant").put("text", text)
        is ChatItem.ToolCallItem -> JSONObject()
            .put("type", "tool")
            .put("name", name)
            .put("args", args)
            .put("result", result)
            .put("done", done)
            .put("expanded", expanded)
            .put("ok", ok)
        is ChatItem.Thinking -> JSONObject().put("type", "thinking").put("text", text).put("expanded", expanded)
        is ChatItem.Meta -> JSONObject().put("type", "meta").put("text", text)
    }

    private fun JSONObject.toChatItem(): ChatItem = when (optString("type")) {
        "user" -> ChatItem.User(optString("text"))
        "assistant" -> ChatItem.Assistant(optString("text"))
        "tool" -> ChatItem.ToolCallItem(
            optString("name"), optString("args"), optString("result"),
            optBoolean("done"), optBoolean("expanded"), optBoolean("ok")
        )
        "thinking" -> ChatItem.Thinking(optString("text"), optBoolean("expanded"))
        else -> ChatItem.Meta(optString("text"))
    }

    /** 会话更新时间格式化（仅日期+时间） */
    fun formatTime(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}
