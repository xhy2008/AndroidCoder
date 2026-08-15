package com.coderagent.android

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 对话中的条目 */
sealed class ChatItem {
    data class User(val text: String) : ChatItem()
    data class Assistant(var text: String) : ChatItem()
    data class ToolCallItem(
        val name: String,
        val args: String,
        var result: String = "",
        var done: Boolean = false,
        /** 折叠/展开：执行完成后默认折叠，点击展开查看完整输出 */
        var expanded: Boolean = false,
        /** 执行成功与否（用于文件工具的成功/失败图标） */
        var ok: Boolean = true
    ) : ChatItem()

    /** 思考过程卡：流式期间展开显示，思考结束后折叠保留 */
    data class Thinking(var text: String, var expanded: Boolean) : ChatItem()

    /** 气泡后的次要信息行（token 统计） */
    data class Meta(val text: String) : ChatItem()
}

class ChatAdapter(private val items: MutableList<ChatItem>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_TOOL = 2
        private const val TYPE_THINKING = 3
        private const val TYPE_META = 4
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ChatItem.User -> TYPE_USER
        is ChatItem.Assistant -> TYPE_ASSISTANT
        is ChatItem.ToolCallItem -> TYPE_TOOL
        is ChatItem.Thinking -> TYPE_THINKING
        is ChatItem.Meta -> TYPE_META
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> MessageHolder(inflater.inflate(R.layout.item_message, parent, false), true)
            TYPE_ASSISTANT -> MessageHolder(inflater.inflate(R.layout.item_message, parent, false), false)
            TYPE_TOOL -> ToolHolder(inflater.inflate(R.layout.item_tool_call, parent, false), this)
            TYPE_THINKING -> ThinkingHolder(inflater.inflate(R.layout.item_thinking, parent, false), this)
            else -> MetaHolder(inflater.inflate(R.layout.item_message, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ChatItem.User -> (holder as MessageHolder).bind(item.text, true)
            is ChatItem.Assistant -> (holder as MessageHolder).bind(item.text, false)
            is ChatItem.ToolCallItem -> (holder as ToolHolder).bind(item)
            is ChatItem.Thinking -> (holder as ThinkingHolder).bind(item)
            is ChatItem.Meta -> (holder as MetaHolder).bind(item.text)
        }
    }

    override fun getItemCount(): Int = items.size

    class MessageHolder(v: View, private val isUser: Boolean) : RecyclerView.ViewHolder(v) {
        private val root: LinearLayout = v.findViewById(R.id.root)
        private val hsv: HorizontalScrollView = v.findViewById(R.id.hsv_text)
        private val tv: TextView = v.findViewById(R.id.tv_text)

        init {
            if (isUser) {
                val maxW = (tv.resources.displayMetrics.density * 300).toInt()
                tv.maxWidth = maxW
            }
        }

        fun bind(text: String, user: Boolean) {
            // AI 气泡按 markdown 渲染；用户气泡保持纯文本
            tv.text = if (user) text else Markdown.get(tv.context).toMarkdown(text)
            // markwon 表格用字符画线框，列对齐依赖等宽字体；含表格的消息整段用等宽
            val hasTable = !user && containsTable(text)
            tv.typeface = if (hasTable) Typeface.MONOSPACE else null

            // HorizontalScrollView 用 UNSPECIFIED 测量 child（一律不换行），
            // 因此只在含表格时把 tv 挂到 HSV 下（表格不换行+横向滚动），
            // 普通文本直接挂在 root 下保持正常换行。
            val current = tv.parent
            if (hasTable && current !== hsv) {
                (current as? ViewGroup)?.removeView(tv)
                hsv.addView(
                    tv,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            } else if (!hasTable && current !== root) {
                (current as? ViewGroup)?.removeView(tv)
                root.addView(
                    tv,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            hsv.visibility = if (hasTable) View.VISIBLE else View.GONE

            val density = tv.resources.displayMetrics.density
            if (user) {
                // 用户气泡自适应宽度
                val lp = tv.layoutParams
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                (lp as ViewGroup.MarginLayoutParams).marginEnd = 0
                tv.layoutParams = lp
                root.gravity = Gravity.END
                // 用户气泡保留布局默认：textIsSelectable、lineSpacing
            } else {
                // AI 气泡铺满宽度（markwon 表格需要足够宽度渲染列）
                if (hasTable) {
                    val hl = hsv.layoutParams
                    hl.width = ViewGroup.LayoutParams.MATCH_PARENT
                    (hl as ViewGroup.MarginLayoutParams).marginEnd = (density * 40).toInt()
                    hsv.layoutParams = hl
                } else {
                    val lp = tv.layoutParams
                    (lp as ViewGroup.MarginLayoutParams).marginEnd = (density * 40).toInt()
                    tv.layoutParams = lp
                }
                root.gravity = Gravity.START
                // 表格修正：selectable 与 lineSpacing 会导致 markwon 表格错位，AI 气泡关闭
                tv.setTextIsSelectable(false)
                tv.setLineSpacing(0f, 1f)
            }
            tv.setBackgroundResource(
                if (user) R.drawable.bg_bubble_user else R.drawable.bg_bubble_assistant
            )
            tv.setTextColor(
                if (user) Color.WHITE else tv.resources.getColor(R.color.text_primary)
            )
        }

        /** 检测 markdown 中是否含表格（存在 `| --- |` 分隔行） */
        private fun containsTable(md: String): Boolean =
            md.lines().drop(1).any { line ->
                val t = line.trim()
                t.startsWith("|") && t.endsWith("|") &&
                    t.replace(Regex("[|:\\s\\-]"), "").isEmpty()
            }
    }

    class ToolHolder(
        v: View,
        private val adapter: RecyclerView.Adapter<*>
    ) : RecyclerView.ViewHolder(v) {
        private val tvName: TextView = v.findViewById(R.id.tv_tool_name)
        private val tvArgs: TextView = v.findViewById(R.id.tv_tool_args)
        private val tvResult: TextView = v.findViewById(R.id.tv_tool_result)

        /** 文件/检索类工具：仅显示关键参数 + 成功/失败图标，单行滚动，无需折叠 */
        private val SIMPLE_TOOLS = setOf(
            "read_file", "write_file", "edit_file", "delete_file", "list_dir",
            "glob", "grep"
        )

        fun bind(item: ChatItem.ToolCallItem) {
            if (item.name in SIMPLE_TOOLS) {
                bindSimpleTool(item)
                return
            }
            val cmd = runCatching { org.json.JSONObject(item.args).optString("command") }
                .getOrNull()?.takeIf { it.isNotBlank() }.orEmpty()
            // 标题：run_command 直接显示命令本身；其他工具显示名称
            val prefix = if (cmd.isNotBlank()) "❯ $cmd" else "🔧 ${item.name}"
            val indicator = when {
                !item.done -> "…"
                item.expanded -> "▾"
                else -> "▸"
            }
            tvName.text = "$prefix  $indicator"
            if (cmd.isNotBlank()) {
                // 长命令单行 + marquee 自动滚动显示完整内容
                tvName.setSingleLine(true)
                tvName.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                tvName.marqueeRepeatLimit = -1
                tvName.isSelected = true
            } else {
                tvName.setSingleLine(false)
                tvName.ellipsize = null
                tvName.maxLines = 2
                tvName.isSelected = false
            }

            // 参数区：run_command 时命令已在标题，隐藏；其他工具显示参数
            if (cmd.isNotBlank()) {
                tvArgs.visibility = View.GONE
            } else {
                tvArgs.visibility = View.VISIBLE
                tvArgs.maxLines = 2
                tvArgs.text = try {
                    org.json.JSONObject(item.args).toString()
                } catch (e: Exception) {
                    item.args
                }
            }

            // 结果区：执行中实时显示已输出的 stdout；无输出时隐藏；完成后默认折叠，点击展开完整输出
            if (!item.done) {
                if (item.result.isBlank()) {
                    tvResult.visibility = View.GONE
                } else {
                    tvResult.visibility = View.VISIBLE
                    tvResult.maxLines = 8
                    tvResult.text = item.result
                }
            } else if (item.expanded) {
                tvResult.visibility = View.VISIBLE
                tvResult.maxLines = Int.MAX_VALUE
                tvResult.text = item.result
            } else {
                tvResult.visibility = View.GONE
            }

            itemView.setOnClickListener {
                if (item.done) {
                    item.expanded = !item.expanded
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) adapter.notifyItemChanged(pos)
                }
            }
        }

        /** 简单工具卡：`✓|✗ 工具名 关键参数`，单行 marquee 滚动，无折叠 */
        private fun bindSimpleTool(item: ChatItem.ToolCallItem) {
            val arg = simpleToolArg(item.name, item.args)
            val ok = item.ok
            tvName.text = "${if (ok) "✓" else "✗"} ${item.name} $arg"
            tvName.setTextColor(
                if (ok) android.graphics.Color.parseColor("#4CAF50")
                else android.graphics.Color.parseColor("#F44336")
            )
            tvName.setSingleLine(true)
            tvName.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            tvName.marqueeRepeatLimit = -1
            tvName.isSelected = true
            tvArgs.visibility = View.GONE
            tvResult.visibility = View.GONE
            itemView.setOnClickListener(null)
        }

        /** 从工具参数提取简单工具显示的关键参数：文件类取路径，检索类取 pattern */
        private fun simpleToolArg(name: String, argsJson: String): String {
            val args = runCatching { org.json.JSONObject(argsJson) }.getOrNull() ?: return ""
            return when (name) {
                "glob", "grep" -> args.optString("pattern")
                "read_file", "write_file", "edit_file", "list_dir" -> args.optString("path")
                "delete_file" -> {
                    val arr = args.optJSONArray("paths")
                    if (arr != null && arr.length() > 0) {
                        (0 until arr.length()).joinToString(", ") { arr.optString(it) }
                    } else {
                        args.optString("path")
                    }
                }
                else -> ""
            }
        }
    }

    class ThinkingHolder(
        v: View,
        private val adapter: RecyclerView.Adapter<*>
    ) : RecyclerView.ViewHolder(v) {
        private val tvTitle: TextView = v.findViewById(R.id.tv_think_title)
        private val tvBody: TextView = v.findViewById(R.id.tv_think_body)

        fun bind(item: ChatItem.Thinking) {
            tvTitle.text = "🤔 思考过程  ${if (item.expanded) "▾" else "▸"}"
            tvBody.visibility = if (item.expanded) View.VISIBLE else View.GONE
            if (item.expanded) {
                tvBody.text = if (item.text.isBlank()) "（思考中…）" else item.text
            }
            itemView.setOnClickListener {
                item.expanded = !item.expanded
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) adapter.notifyItemChanged(pos)
            }
        }
    }

    class MetaHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val root: LinearLayout = v.findViewById(R.id.root)
        private val tv: TextView = v.findViewById(R.id.tv_text)

        init {
            val maxW = (tv.resources.displayMetrics.density * 320).toInt()
            tv.maxWidth = maxW
        }

        fun bind(text: String) {
            tv.text = text
            tv.setTextColor(tv.resources.getColor(R.color.text_secondary))
            tv.setBackgroundResource(0)
            tv.textSize = 12f
            root.gravity = Gravity.START
        }
    }
}
