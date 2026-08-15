package com.coderagent.android

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.coderagent.android.databinding.ActivityEditorBinding
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏文本编辑器：容器文件经 base64 通道读入，支持基础代码高亮与保存。
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private var path = ""
    private var editable = true
    private var highlightEnabled = true
    private lateinit var lang: Lang

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        path = intent.getStringExtra("path") ?: ""
        binding.toolbar.subtitle = path
        lang = Lang.fromPath(path)
        if (lang == Lang.PLAIN) highlightEnabled = false

        // 行号：关联编辑器 + 布局变化时同步高度/宽度
        binding.lineNumbers.editor = binding.editor
        binding.editor.viewTreeObserver.addOnGlobalLayoutListener {
            binding.lineNumbers.sync()
        }
        binding.editor.addTextChangedListener(watcher)
        load()
    }

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            // 行数变化时刷新行号
            binding.lineNumbers.sync()
            if (s != null && editable && highlightEnabled && s.length <= HIGHLIGHT_LIMIT) {
                Highlighter.highlight(s, lang)
            }
        }
    }

    private fun load() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val sizeR = ContainerRuntime.exec(this@EditorActivity, "wc -c < ${ToolRegistry.shq(path)} 2>/dev/null || printf 0", "/root", 30)
                    val size = sizeR.output.trim().toLongOrNull() ?: 0L
                    val tmp = ContainerRuntime.tmpDir(this@EditorActivity)
                    val b64 = File(tmp, "ed-${UUID.randomUUID()}.b64")
                    val r = ContainerRuntime.exec(
                        this@EditorActivity,
                        "base64 ${ToolRegistry.shq(path)} > /hosttmp/${b64.name} && echo OK || echo FAIL",
                        "/root", timeoutSec = 120
                    )
                    if (!r.output.contains("OK")) return@withContext Triple(-1L, "ERR", false)
                    val content = String(android.util.Base64.decode(b64.readText().trim(), android.util.Base64.NO_WRAP), Charsets.UTF_8)
                    b64.delete()
                    Triple(size, content, true)
                } catch (e: Exception) {
                    Triple(-1L, e.message ?: "ERR", false)
                }
            }
            if (result.third != true || result.second == "ERR") {
                Toast.makeText(this@EditorActivity, getString(R.string.editor_load_err), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            val size = result.first
            var content = result.second
            if (size > READ_ONLY_LIMIT) {
                editable = false
                binding.editor.isFocusable = false
                Toast.makeText(this@EditorActivity, getString(R.string.editor_big_file, size), Toast.LENGTH_LONG).show()
            } else if (content.contains('\u0000')) {
                editable = false
                binding.editor.isFocusable = false
                Toast.makeText(this@EditorActivity, getString(R.string.editor_binary), Toast.LENGTH_LONG).show()
            }
            binding.editor.setText(content)
            if (editable && highlightEnabled && content.length <= HIGHLIGHT_LIMIT) {
                Highlighter.highlight(binding.editor.text, lang)
            }
        }
    }

    private fun save() {
        if (!editable) return
        val content = binding.editor.text?.toString() ?: ""
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val tmp = ContainerRuntime.tmpDir(this@EditorActivity)
                    val b64 = File(tmp, "ed-${UUID.randomUUID()}.b64")
                    b64.writeText(android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
                    val dir = path.substringBeforeLast('/').ifEmpty { "/" }
                    val r = ContainerRuntime.exec(
                        this@EditorActivity,
                        "mkdir -p ${ToolRegistry.shq(dir)} && base64 -d /hosttmp/${b64.name} > ${ToolRegistry.shq(path)} && echo OK || echo FAIL",
                        "/root", timeoutSec = 120
                    )
                    b64.delete()
                    if (r.output.contains("OK")) getString(R.string.editor_saved) else getString(R.string.editor_save_fail)
                } catch (e: Exception) {
                    "${getString(R.string.editor_save_fail)}: ${e.message}"
                }
            }
            Toast.makeText(this@EditorActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_save)?.isVisible = editable
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish(); true
            }
            R.id.action_save -> {
                save(); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val HIGHLIGHT_LIMIT = 200_000
        private const val READ_ONLY_LIMIT = 4L * 1024 * 1024

        enum class Lang(val commentLine: Pattern?, val commentBlock: Pattern?, val keywords: Pattern) {
            PLAIN(null, null, Pattern.compile("(?!)")),
            SHELL(
                Pattern.compile("(?m)^\\s*#.*$"),
                null,
                Pattern.compile("\\b(?:if|then|else|elif|fi|for|while|do|done|case|esac|function|return|export|local|read|echo|exit|break|continue|unset|shift)\\b")
            ),
            PYTHON(
                Pattern.compile("(?m)^\\s*#.*$"),
                Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''"),
                Pattern.compile("\\b(?:def|class|return|import|from|if|elif|else|for|while|try|except|finally|with|as|pass|break|continue|lambda|yield|global|nonlocal|raise|assert|del|not|and|or|in|is|None|True|False|async|await|print)\\b")
            ),
            C_LIKE(
                Pattern.compile("(?m)//.*$"),
                Pattern.compile("/\\*[\\s\\S]*?\\*/"),
                Pattern.compile("\\b(?:if|else|for|while|do|switch|case|break|continue|return|class|interface|struct|enum|public|private|protected|static|final|void|int|long|float|double|char|boolean|var|val|fun|import|package|new|this|super|null|true|false|try|catch|finally|throw|extends|implements|def|function|const|let|export|async|await|of|in|typeof|instanceof|undefined|NaN|yield|var|using|namespace|override|open|data|sealed|object|companion|init|is|as|when|with)\\b")
            );

            companion object {
                fun fromPath(p: String): Lang {
                    val ext = p.substringAfterLast('.', "").lowercase()
                    return when (ext) {
                        "sh", "bash", "zsh", "fish" -> SHELL
                        "py" -> PYTHON
                        "js", "ts", "java", "kt", "kts", "c", "h", "cpp", "hpp", "cs", "go", "rs",
                        "swift", "dart", "php", "scala", "groovy" -> C_LIKE
                        else -> PLAIN
                    }
                }
            }
        }

        /** 基础代码高亮：注释/字符串/关键词/数字（前景色） */
        object Highlighter {
            // 深色主题下的高亮配色
            private val C_COMMENT = Color.parseColor("#9E9E9E")
            private val C_STRING = Color.parseColor("#81C784")
            private val C_KEYWORD = Color.parseColor("#CE93D8")
            private val C_NUMBER = Color.parseColor("#FFB74D")
            private val STRING_PATTERNS = listOf(
                Pattern.compile("\"(\\\\.|[^\"\\\\])*\""),
                Pattern.compile("'(\\\\.|[^'\\\\])*'")
            )
            private val NUMBER_PATTERN = Pattern.compile("\\b\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b")

            fun highlight(sp: Spannable, lang: Lang) {
                if (lang == Lang.PLAIN) return
                for (s in sp.getSpans(0, sp.length, ForegroundColorSpan::class.java)) sp.removeSpan(s)
                val covered = mutableListOf<IntRange>()
                val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

                lang.commentLine?.let { p ->
                    p.matcher(sp).run { while (find()) { sp.setSpan(ForegroundColorSpan(C_COMMENT), start(), end(), flags); covered += start() until end() } }
                }
                lang.commentBlock?.let { p ->
                    p.matcher(sp).run { while (find()) { sp.setSpan(ForegroundColorSpan(C_COMMENT), start(), end(), flags); covered += start() until end() } }
                }
                for (p in STRING_PATTERNS) {
                    p.matcher(sp).run {
                        while (find()) {
                            if (covered.any { it.contains(start()) }) continue
                            sp.setSpan(ForegroundColorSpan(C_STRING), start(), end(), flags)
                            covered += start() until end()
                        }
                    }
                }
                lang.keywords.matcher(sp).run {
                    while (find()) {
                        if (covered.any { it.contains(start()) }) continue
                        sp.setSpan(ForegroundColorSpan(C_KEYWORD), start(), end(), flags)
                    }
                }
                NUMBER_PATTERN.matcher(sp).run {
                    while (find()) {
                        if (covered.any { it.contains(start()) }) continue
                        sp.setSpan(ForegroundColorSpan(C_NUMBER), start(), end(), flags)
                    }
                }
            }
        }
    }
}
