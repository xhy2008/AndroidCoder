package com.coderagent.android

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 工具定义（OpenAI function calling schema） */
data class ToolDef(val name: String, val description: String, val parameters: JSONObject)

/**
 * Agent 工具集。约定与桌面 Coding Agent 一致：
 * read_file / write_file / edit_file / delete_file / list_dir / glob / grep / run_command。
 * 所有文件操作作用于容器内路径（默认工作区为 /root/workspace）。
 */
object ToolRegistry {
    private const val TAG = "ToolRegistry"
    private const val MAX_OUTPUT = 30000

    val tools: List<ToolDef> = listOf(
        ToolDef(
            "run_command",
            "在容器内执行一条 shell 命令并返回 stdout/stderr 与退出码。命令以 root 身份运行在 Debian 容器中。用于运行代码、安装包（apt）、编译、测试等。注意：每条命令是独立的 shell 会话，环境变量不跨调用保留。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("command", JSONObject().put("type", "string").put("description", "要执行的 shell 命令"))
                    .put("cwd", JSONObject().put("type", "string").put("description", "工作目录（容器内绝对路径），默认工作区"))
                    .put("timeout", JSONObject().put("type", "integer").put("description", "超时秒数，默认 120，最大 600"))
                )
                .put("required", JSONArray().put("command"))
        ),
        ToolDef(
            "read_file",
            "读取文件内容并返回带行号的文本（与桌面工具 Read 一致）。支持 offset/limit 分段读取大文件。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("path", JSONObject().put("type", "string").put("description", "文件绝对路径"))
                    .put("offset", JSONObject().put("type", "integer").put("description", "起始行号（从 1 开始），默认 1"))
                    .put("limit", JSONObject().put("type", "integer").put("description", "最多读取行数，默认 200"))
                )
                .put("required", JSONArray().put("path"))
        ),
        ToolDef(
            "write_file",
            "创建或覆盖写入一个文件（父目录不存在时自动创建）。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("path", JSONObject().put("type", "string").put("description", "文件绝对路径"))
                    .put("content", JSONObject().put("type", "string").put("description", "完整文件内容"))
                )
                .put("required", JSONArray().put("path").put("content"))
        ),
        ToolDef(
            "edit_file",
            "在文件中精确替换第一个匹配的文本片段（与桌面工具 SearchReplace 一致）。修改前请先 read_file 确认内容。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("path", JSONObject().put("type", "string").put("description", "文件绝对路径"))
                    .put("old_str", JSONObject().put("type", "string").put("description", "要查找并替换的原文（必须完全匹配且唯一可定位）"))
                    .put("new_str", JSONObject().put("type", "string").put("description", "替换后的新文本"))
                )
                .put("required", JSONArray().put("path").put("old_str").put("new_str"))
        ),
        ToolDef(
            "delete_file",
            "删除一个或多个文件/目录（目录递归删除）。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("paths", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "要删除的路径列表"))
                )
                .put("required", JSONArray().put("paths"))
        ),
        ToolDef(
            "list_dir",
            "列出目录内容（含大小、权限、符号链接信息，与桌面工具 LS 一致）。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("path", JSONObject().put("type", "string").put("description", "目录绝对路径，默认工作区"))
                )
                .put("required", JSONArray())
        ),
        ToolDef(
            "glob",
            "按文件名模式查找文件（支持 * ? [] 通配，与桌面工具 Glob 一致）。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("pattern", JSONObject().put("type", "string").put("description", "文件名模式，例如 *.py 或 **/build/*.jar"))
                    .put("path", JSONObject().put("type", "string").put("description", "搜索起始目录，默认工作区"))
                )
                .put("required", JSONArray().put("pattern"))
        ),
        ToolDef(
            "grep",
            "在文件/目录中搜索文本内容（支持正则、递归、上下文、计数，与桌面工具 Grep 一致）。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("pattern", JSONObject().put("type", "string").put("description", "搜索的正则表达式"))
                    .put("path", JSONObject().put("type", "string").put("description", "文件或目录路径，默认工作区"))
                    .put("glob", JSONObject().put("type", "string").put("description", "仅当 path 为目录时生效，过滤文件，如 *.kt"))
                    .put("case_sensitive", JSONObject().put("type", "boolean").put("description", "是否区分大小写，默认 true"))
                    .put("context", JSONObject().put("type", "integer").put("description", "匹配行上下文行数，默认 0"))
                    .put("output_mode", JSONObject().put("type", "string").put("enum", JSONArray().put("content").put("files_with_matches").put("count")).put("description", "输出模式，默认 content"))
                    .put("head_limit", JSONObject().put("type", "integer").put("description", "最大返回行数/文件数，默认 200"))
                )
                .put("required", JSONArray().put("pattern"))
        )
    )

    fun toOpenAiArray(): JSONArray = JSONArray().apply {
        for (t in tools) {
            put(
                JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("parameters", t.parameters)
                    )
            )
        }
    }

    /** 执行工具，返回给模型的文本结果 */
    suspend fun execute(ctx: Context, name: String, args: JSONObject, workspace: String): String =
        withContext(Dispatchers.IO) {
            kotlin.coroutines.coroutineContext.ensureActive()
            try {
                when (name) {
                    "run_command" -> runCommand(ctx, args, workspace)
                    "read_file" -> readFile(ctx, args, workspace)
                    "write_file" -> writeFile(ctx, args, workspace)
                    "edit_file" -> editFile(ctx, args, workspace)
                    "delete_file" -> deleteFile(ctx, args)
                    "list_dir" -> listDir(ctx, args, workspace)
                    "glob" -> glob(ctx, args, workspace)
                    "grep" -> grep(ctx, args, workspace)
                    else -> "__ERR__: 未知工具 $name"
                }
            } catch (e: Exception) {
                Log.e(TAG, "工具 $name 执行异常", e)
                "__ERR__: ${name} 执行异常: ${e.message}"
            }
        }

    // ---------- 工具实现 ----------

    /**
     * 流式执行：run_command 实时回调 stdout/stderr 行（onDelta 在工作线程，调用方自行切线程），
     * 其他工具一次性返回。返回最终结果文本。
     */
    suspend fun executeStreaming(
        ctx: Context,
        name: String,
        args: JSONObject,
        workspace: String,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        kotlin.coroutines.coroutineContext.ensureActive()
        try {
            if (name == "run_command") {
                runCommandStreaming(ctx, args, workspace, onDelta)
            } else {
                execute(ctx, name, args, workspace)
            }
        } catch (e: Exception) {
            Log.e(TAG, "工具 $name 执行异常", e)
            "__ERR__: ${name} 执行异常: ${e.message}"
        }
    }

    private suspend fun runCommandStreaming(
        ctx: Context,
        args: JSONObject,
        workspace: String,
        onDelta: (String) -> Unit
    ): String {
        val command = args.getString("command")
        val cwd = args.optString("cwd").ifBlank { workspace }
        val timeout = args.optInt("timeout", 120).coerceIn(1, 600)
        val sb = StringBuilder()
        var limited = false
        val lock = Any()
        val emit = { line: String ->
            synchronized(lock) {
                if (!limited) {
                    sb.appendLine(line)
                    Log.d("ToolStream", "emit cmd=${command.take(60)} line=${line.length}")
                    // 带上换行符，保证 UI 工具卡逐行显示
                    onDelta(line + "\n")
                    if (sb.length > MAX_OUTPUT) limited = true
                }
            }
        }
        val r = ContainerRuntime.execStreaming(
            ctx, command, cwd, timeout.toLong(),
            onStdout = { emit(it) },
            onStderr = { emit(it) }
        )
        val out = StringBuilder()
        out.appendLine("__exit__: ${r.exitCode}").appendLine("__timed_out__: ${r.timedOut}")
        if (sb.isNotEmpty()) {
            out.appendLine("[stdout]")
            out.appendLine(truncate(sb.toString()))
        }
        return out.toString()
    }

    private suspend fun runCommand(ctx: Context, args: JSONObject, workspace: String): String {
        val command = args.getString("command")
        val cwd = args.optString("cwd").ifBlank { workspace }
        val timeout = args.optInt("timeout", 120).coerceIn(1, 600)
        val r = ContainerRuntime.execSuspend(ctx, command, cwd, timeout.toLong())
        val sb = StringBuilder()
        sb.appendLine("__exit__: ${r.exitCode}").appendLine("__timed_out__: ${r.timedOut}")
        if (r.stdout.isNotBlank()) {
            sb.appendLine("[stdout]")
            sb.appendLine(truncate(r.stdout))
        }
        if (r.stderr.isNotBlank()) {
            sb.appendLine("[stderr]")
            sb.appendLine(truncate(r.stderr))
        }
        return sb.toString()
    }

    private suspend fun readFile(ctx: Context, args: JSONObject, workspace: String): String {
        val path = resolve(args.getString("path"), workspace)
        val offset = args.optInt("offset", 1).coerceAtLeast(1)
        val limit = args.optInt("limit", 200).coerceIn(1, 10000)
        val endLine = offset + limit - 1
        val script = """
            f=${shq(path)}
            if [ ! -e "${'$'}f" ]; then echo "__ERR__: 文件不存在: ${'$'}f"; exit 1; fi
            if [ -d "${'$'}f" ]; then echo "__ERR__: 这是一个目录，请使用 list_dir"; exit 1; fi
            total=${'$'}(wc -l < "${'$'}f" 2>/dev/null | tr -d ' ')
            awk -v s=$offset -v e=$endLine 'NR>=s && NR<=e { line=${'$'}0; if (length(line)>2000) line=substr(line,1,2000); printf "%d→%s\n", NR, line }' "${'$'}f"
            printf '\n__total_lines__: %s\n' "${'$'}total"
            if [ "$endLine" -lt "${'$'}total" ]; then printf '__truncated__: true（已显示 %s~%s 行，共 %s 行，可用 offset/limit 继续）\n' $offset $endLine "${'$'}total"; fi
        """.trimIndent()
        val r = ContainerRuntime.execSuspend(ctx, script, workspace, 60)
        if (r.exitCode != 0) return r.stdout.ifBlank { r.stderr }.takeIf { it.isNotBlank() } ?: "读取失败 exit=${r.exitCode}"
        return r.stdout
    }

    private suspend fun writeFile(ctx: Context, args: JSONObject, workspace: String): String {
        val path = resolve(args.getString("path"), workspace)
        val content = args.getString("content")
        val tmp = ContainerRuntime.tmpDir(ctx)
        val b64 = File(tmp, "w-${UUID.randomUUID()}.b64")
        b64.writeText(Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        val script = """
            mkdir -p ${shq(path.substringBeforeLast('/', "/"))} 2>/dev/null
            if ! base64 -d /hosttmp/${b64.name} > ${shq(path)}; then echo "__ERR__: 写入失败"; exit 1; fi
            echo "OK: 已写入 ${'$'}(wc -c < ${shq(path)}) 字节"
        """.trimIndent()
        return try {
            val r = ContainerRuntime.execSuspend(ctx, script, workspace, 60)
            r.output
        } finally {
            b64.delete()
        }
    }

    private suspend fun editFile(ctx: Context, args: JSONObject, workspace: String): String {
        val path = resolve(args.getString("path"), workspace)
        val oldStr = args.getString("old_str")
        val newStr = args.getString("new_str")
        if (oldStr.isEmpty()) return "__ERR__: old_str 不能为空"
        val tmp = ContainerRuntime.tmpDir(ctx)
        val inFile = File(tmp, "e-${UUID.randomUUID()}.b64")
        val outFile = File(tmp, "o-${UUID.randomUUID()}.b64")
        try {
            val read = ContainerRuntime.execSuspend(ctx, "base64 ${shq(path)} > /hosttmp/${inFile.name} 2>/dev/null && echo OK || echo FAIL", workspace, 60)
            if (!read.output.contains("OK")) return "__ERR__: 无法读取文件 $path（不存在？）"
            val current = String(Base64.decode(inFile.readText().trim(), Base64.NO_WRAP), Charsets.UTF_8)
            val idx = current.indexOf(oldStr)
            if (idx < 0) {
                return "__ERR__: 在文件中未找到 old_str 的精确匹配。请先 read_file 确认内容后重试（edit_file 只替换第一处完全相同的文本）"
            }
            val updated = current.replaceRange(idx, idx + oldStr.length, newStr)
            outFile.writeText(Base64.encodeToString(updated.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            val w = ContainerRuntime.execSuspend(ctx, "base64 -d /hosttmp/${outFile.name} > ${shq(path)} && echo OK || echo FAIL", workspace, 60)
            if (!w.output.contains("OK")) return "__ERR__: 写入失败"
            return "OK: 已替换第 1 处匹配（${oldStr.length} → ${newStr.length} 字符）"
        } finally {
            inFile.delete()
            outFile.delete()
        }
    }

    private suspend fun deleteFile(ctx: Context, args: JSONObject): String {
        val paths = args.getJSONArray("paths")
        val sb = StringBuilder()
        for (i in 0 until paths.length()) {
            val p = paths.getString(i)
            val r = ContainerRuntime.execSuspend(ctx, "rm -rf -- ${shq(p)} && echo deleted || echo fail", "/root", 60)
            sb.appendLine("${r.output.trim()} : $p")
        }
        return sb.toString()
    }

    private suspend fun listDir(ctx: Context, args: JSONObject, workspace: String): String {
        val path = resolve(args.optString("path").ifBlank { workspace }, workspace)
        val script = """
            if [ ! -d ${shq(path)} ]; then echo "__ERR__: 目录不存在: ${shq(path)}"; exit 1; fi
            ls -la ${shq(path)}
        """.trimIndent()
        val r = ContainerRuntime.execSuspend(ctx, script, workspace, 30)
        return r.output
    }

    private suspend fun glob(ctx: Context, args: JSONObject, workspace: String): String {
        val pattern = args.getString("pattern")
        val path = resolve(args.optString("path").ifBlank { workspace }, workspace)
        val script = """
            if [ ! -d ${shq(path)} ]; then echo "__ERR__: 目录不存在: ${shq(path)}"; exit 1; fi
            find ${shq(path)} -name ${shq(pattern)} -print 2>/dev/null | head -n 500
        """.trimIndent()
        val r = ContainerRuntime.execSuspend(ctx, script, workspace, 60)
        val out = r.output
        return out.ifBlank { "（无匹配文件）" }
    }

    private suspend fun grep(ctx: Context, args: JSONObject, workspace: String): String {
        val pattern = args.getString("pattern")
        val path = resolve(args.optString("path").ifBlank { workspace }, workspace)
        val glob = args.optString("glob").ifBlank { null }
        val caseSensitive = args.optBoolean("case_sensitive", true)
        val context = args.optInt("context", 0).coerceIn(0, 10)
        val mode = args.optString("output_mode", "content")
        val headLimit = args.optInt("head_limit", 200).coerceIn(1, 1000)

        val sb = StringBuilder("grep -r ")
        if (!caseSensitive) sb.append("-i ")
        if (context > 0) sb.append("-C ").append(context).append(" ")
        if (glob != null) sb.append("--include=").append(shq(glob)).append(" ")
        when (mode) {
            "files_with_matches" -> sb.append("-l ")
            "count" -> sb.append("-c ")
            else -> sb.append("-n ")
        }
        sb.append("-e ").append(shq(pattern)).append(" ").append(shq(path))
        if (mode == "count") sb.append(" | head -n 500") else sb.append(" | head -n ").append(headLimit)
        val r = ContainerRuntime.execSuspend(ctx, sb.toString(), workspace, 60)
        val out = r.output
        return if (out.isBlank()) "（无匹配）" else truncate(out, 40000)
    }

    // ---------- 工具函数 ----------

    private fun resolve(path: String, workspace: String): String =
        if (path.startsWith("/")) path else "$workspace/$path"

    private fun truncate(s: String, max: Int = MAX_OUTPUT): String =
        if (s.length > max) s.take(max) + "\n…（输出过长已截断，共 ${s.length} 字符）" else s

    /** shell 单引号转义 */
    fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
