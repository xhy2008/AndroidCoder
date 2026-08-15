package com.coderagent.android

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.coderagent.android.databinding.ActivityFileBrowserBinding
import com.coderagent.android.databinding.ItemFileEntryBinding
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 容器文件浏览器：浏览/编辑/新建/删除/重命名/复制剪切粘贴/压缩/导出 容器内文件。
 * - 单条目长按：复制/剪切/粘贴/编辑/导出/压缩/重命名/删除
 * - 工具栏"多选"进入多选模式：长按弹出 复制/移动/压缩/删除/导出 五项菜单，
 *   底部操作栏亦可直接执行
 * - 全部操作经 proroot 在容器内执行；压缩与目录导出在宿主侧用 zip 打包
 */
class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding
    private var currentDir = ContainerRuntime.WORKSPACE_DEFAULT
    private var loading = false
    private lateinit var adapter: EntryAdapter

    // 复制/剪切剪贴板（Windows 风格）
    private var clipboardPaths: List<String> = emptyList()
    private var clipboardMove = false

    // 多选状态
    private var selectionMode = false
    private val selectedNames = mutableSetOf<String>()

    // 导出回调状态
    private var pendingExportPath: String? = null
    private var pendingExportIsDir = false
    private var pendingExportMulti: List<String>? = null

    // 列表空白区域长按检测（RecyclerView 自身不派发长按）
    private val emptyAreaDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                showEmptyAreaMenu()
            }
        })
    }

    private val createDoc = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri ->
            pendingExportMulti?.let { paths ->
                pendingExportMulti = null
                exportSelectionAsZip(paths, uri)
            } ?: pendingExportPath?.let { path ->
                val isDir = pendingExportIsDir
                pendingExportPath = null
                pendingExportIsDir = false
                if (isDir) exportDir(path, uri) else exportFile(path, uri)
            }
        }
    }

    data class Entry(val name: String, val isDir: Boolean, val size: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (!ContainerRuntime.isInstalled(this)) {
            binding.emptyView.text = getString(R.string.fb_not_installed)
            binding.emptyView.visibility = View.VISIBLE
            binding.pathInput.isEnabled = false
            binding.btnGo.isEnabled = false
            return
        }

        adapter = EntryAdapter(
            onClick = { e ->
                when {
                    selectionMode -> if (e.name != "..") toggleSelect(e.name)
                    e.name == ".." -> goTo(parentOf(currentDir))
                    e.isDir -> goTo(dirJoin(currentDir, e.name))
                    else -> openFile(dirJoin(currentDir, e.name))
                }
            },
            onLongClick = { e ->
                if (e.name == "..") return@EntryAdapter
                if (selectionMode) {
                    if (selectedNames.isNotEmpty()) showSelectionMenu()
                } else {
                    showEntryMenu(e)
                }
            },
            isSelected = { e -> selectionMode && e.name in selectedNames }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recycler.addItemDecoration(SpacingDecoration(8))
        // 列表空白区域长按：粘贴（剪贴板为空时置灰）。RecyclerView 自身不派发长按，用 GestureDetector 检测
        binding.recycler.setOnTouchListener { _, event -> emptyAreaDetector.onTouchEvent(event); false }
        binding.emptyView.setOnLongClickListener { showEmptyAreaMenu(); true }

        // 多选操作栏
        binding.btnSelCopy.setOnClickListener { copySelection() }
        binding.btnSelMove.setOnClickListener { moveSelection() }
        binding.btnSelCompress.setOnClickListener { compressSelection() }
        binding.btnSelDelete.setOnClickListener { deleteSelection() }
        binding.btnSelExport.setOnClickListener { exportSelection() }
        binding.btnSelExit.setOnClickListener { exitSelection() }

        binding.btnGo.setOnClickListener { goTo(binding.pathInput.text?.toString()?.trim().orEmpty()) }
        binding.pathInput.setOnEditorActionListener { _, _, _ -> goTo(binding.pathInput.text?.toString()?.trim().orEmpty()); true }

        goTo(currentDir)
    }

    // ---------- 目录加载 ----------

    private fun goTo(path: String) {
        if (path.isBlank() || loading) return
        loading = true
        exitSelection()
        binding.pathInput.setText(path)
        binding.toolbar.subtitle = path
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                val r = ContainerRuntime.exec(
                    this@FileBrowserActivity, listDirScript(path), "/root", timeoutSec = 30
                )
                if (r.exitCode != 0 || r.stdout.startsWith("__ERR__")) {
                    getString(R.string.fb_load_err, path)
                } else {
                    r.stdout
                }
            }
            loading = false
            if (out.startsWith(getString(R.string.fb_load_err, path))) {
                Toast.makeText(this@FileBrowserActivity, out, Toast.LENGTH_SHORT).show()
                return@launch
            }
            currentDir = path
            val list = mutableListOf<Entry>()
            if (path != "/") list.add(Entry("..", true, 0))
            list.addAll(parse(out))
            adapter.submitList(list)
            binding.emptyView.visibility = if (list.size <= 1) View.VISIBLE else View.GONE
        }
    }

    private fun listDirScript(path: String): String = """
        cd ${ToolRegistry.shq(path)} 2>/dev/null || { printf '__ERR__\n'; exit 1; }
        for e in * .[!.]*; do
            [ "${'$'}e" = "*" ] && [ ! -e "${'$'}e" ] && continue
            [ "${'$'}e" = ".[!.]*" ] && [ ! -e "${'$'}e" ] && continue
            if [ -d "${'$'}e" ]; then printf 'd\t%s\n' "${'$'}e"
            elif [ -f "${'$'}e" ]; then printf 'f\t%s\t%s\n' "${'$'}(stat -c %s "${'$'}e" 2>/dev/null || printf 0)" "${'$'}e"
            else printf 'o\t%s\n' "${'$'}e"; fi
        done
    """.trimIndent()

    private fun parse(raw: String): List<Entry> {
        val out = mutableListOf<Entry>()
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val p = line.split("\t")
            when {
                p.size >= 2 && p[0] == "d" -> out.add(Entry(p[1], true, 0))
                p.size >= 3 && p[0] == "f" -> out.add(Entry(p[2], false, p[1].toLongOrNull() ?: 0))
                p.size >= 2 && p[0] == "o" -> out.add(Entry(p[1], false, 0))
            }
        }
        return out.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    private fun dirJoin(base: String, name: String): String =
        if (base == "/") "/$name" else "$base/$name"

    private fun parentOf(path: String): String =
        if (path == "/" || !path.contains("/")) "/" else path.substringBeforeLast('/').ifEmpty { "/" }

    // ---------- 文件操作 ----------

    /** 按文件类型分发：文本→编辑器，图片/音视频→媒体查看，压缩包→压缩包浏览，其他→编辑器只读预览 */
    private fun openFile(path: String) {
        val ext = path.substringAfterLast('.', "").lowercase()
        val intent = when {
            ext in TEXT_EXTS -> Intent(this, EditorActivity::class.java)
            ext in IMAGE_EXTS || ext in AUDIO_EXTS || ext in VIDEO_EXTS -> Intent(this, MediaViewerActivity::class.java)
            ext in ARCHIVE_EXTS -> Intent(this, ArchiveViewerActivity::class.java)
            // 其他类型（含二进制）交给编辑器只读预览
            else -> Intent(this, EditorActivity::class.java)
        }
        startActivity(intent.putExtra("path", path))
    }

    private fun exportEntry(path: String, isDir: Boolean) {
        pendingExportPath = path
        pendingExportIsDir = isDir
        createDoc.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_TITLE,
                    path.substringAfterLast('/').ifEmpty { "export" } + if (isDir) ".zip" else ""
                )
            }
        )
    }

    private fun exportFile(path: String, uri: android.net.Uri) {
        lifecycleScope.launch {
            val ok = ExportHelper.export(this@FileBrowserActivity, path, uri)
            Toast.makeText(this@FileBrowserActivity, if (ok) R.string.fb_export_ok else R.string.fb_export_fail, Toast.LENGTH_SHORT).show()
        }
    }

    /** 导出目录：打包为 zip 后写入 SAF Uri */
    private fun exportDir(path: String, uri: android.net.Uri) {
        exportSelectionAsZip(listOf(path), uri)
    }

    /** 多选导出：选中项打包为单个 zip 写入 SAF Uri */
    private fun exportSelectionAsZip(paths: List<String>, uri: android.net.Uri) {
        loading = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val zipFile = stageZip(paths) ?: return@withContext false
                    try {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            zipFile.inputStream().use { it.copyTo(out) }
                        } ?: return@withContext false
                    } finally {
                        zipFile.delete()
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            loading = false
            Toast.makeText(this@FileBrowserActivity, if (ok) R.string.fb_export_ok else R.string.fb_export_fail, Toast.LENGTH_SHORT).show()
        }
    }

    private fun editFile(path: String, initial: String) {
        val input = EditText(this)
        input.setText(initial)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.minLines = 8
        val scroll = android.widget.ScrollView(this)
        scroll.addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 500))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_edit) + "  " + path)
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val content = input.text?.toString() ?: ""
                saveFile(path, content)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveFile(path: String, content: String) {
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val tmp = ContainerRuntime.tmpDir(this@FileBrowserActivity)
                    val b64 = File(tmp, "fb-${UUID.randomUUID()}.b64")
                    b64.writeText(android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
                    val dir = path.substringBeforeLast('/').ifEmpty { "/" }
                    val r = ContainerRuntime.exec(
                        this@FileBrowserActivity,
                        "mkdir -p ${ToolRegistry.shq(dir)} && base64 -d /hosttmp/${b64.name} > ${ToolRegistry.shq(path)} && echo OK || echo FAIL",
                        "/root", timeoutSec = 60
                    )
                    b64.delete()
                    if (r.output.contains("OK")) "已保存" else "保存失败"
                } catch (e: Exception) {
                    "保存失败: ${e.message}"
                }
            }
            Toast.makeText(this@FileBrowserActivity, msg, Toast.LENGTH_SHORT).show()
            if (msg == "已保存") goTo(currentDir)
        }
    }

    /** 单条目长按菜单（文件与目录通用）：复制/剪切 + 编辑/导出/压缩/重命名/删除 */
    private fun showEntryMenu(e: Entry) {
        val path = dirJoin(currentDir, e.name)
        val items = mutableListOf<String>()
        items.add(getString(R.string.fb_copy))
        items.add(getString(R.string.fb_cut))
        if (!e.isDir) items.add(getString(R.string.fb_edit))
        items.add(getString(R.string.fb_export))
        if (e.isDir) items.add(getString(R.string.fb_compress))
        items.add(getString(R.string.fb_rename))
        items.add(getString(R.string.fb_delete))
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    getString(R.string.fb_copy) -> copyToClipboard(listOf(path))
                    getString(R.string.fb_cut) -> cutToClipboard(listOf(path))
                    getString(R.string.fb_edit) -> {
                        loading = true
                        lifecycleScope.launch {
                            val c = withContext(Dispatchers.IO) {
                                ContainerRuntime.exec(this@FileBrowserActivity, "cat ${ToolRegistry.shq(path)} 2>/dev/null | head -c 20480", "/root", 30).stdout
                            }
                            loading = false
                            editFile(path, c)
                        }
                    }
                    getString(R.string.fb_export) -> exportEntry(path, e.isDir)
                    getString(R.string.fb_compress) ->
                        compressEntries(listOf(path), dirJoin(path.substringBeforeLast('/'), "${e.name}.zip"))
                    getString(R.string.fb_rename) -> renameEntry(path)
                    getString(R.string.fb_delete) -> deleteEntry(path)
                }
            }
            .show()
    }

    // ---------- 复制/剪切/粘贴（剪贴板，Windows 风格） ----------

    private fun copyToClipboard(paths: List<String>) {
        clipboardPaths = paths
        clipboardMove = false
        Toast.makeText(this, getString(R.string.fb_copied, paths.size), Toast.LENGTH_SHORT).show()
    }

    private fun cutToClipboard(paths: List<String>) {
        clipboardPaths = paths
        clipboardMove = true
        Toast.makeText(this, getString(R.string.fb_cut_ok, paths.size), Toast.LENGTH_SHORT).show()
    }

    private fun pasteClipboard() {
        if (clipboardPaths.isEmpty()) {
            Toast.makeText(this, R.string.fb_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val ops = clipboardPaths.joinToString(" && ") { p ->
            val cmd = if (clipboardMove) "mv" else "cp -r"
            "$cmd ${ToolRegistry.shq(p)} ${ToolRegistry.shq(dirJoin(currentDir, p.substringAfterLast('/')))}"
        }
        val wasMove = clipboardMove
        runContainerAction("$ops && echo OK || echo FAIL", getString(R.string.fb_paste_ok), timeoutSec = 300) {
            if (wasMove) {
                clipboardPaths = emptyList()
                clipboardMove = false
            }
        }
    }

    /** 列表空白区域长按菜单：粘贴（剪贴板为空时置灰不可点） */
    private fun showEmptyAreaMenu() {
        val hasClipboard = clipboardPaths.isNotEmpty()
        val items = listOf(getString(R.string.fb_paste))
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun areAllItemsEnabled() = hasClipboard
            override fun isEnabled(position: Int) = hasClipboard
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val tv = v.findViewById<android.widget.TextView>(android.R.id.text1)
                tv?.setTextColor(
                    if (hasClipboard) 0xFFF5F5F5.toInt() else 0xFF666666.toInt()
                )
                return v
            }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.file_browser_title))
            .setAdapter(adapter) { _, which ->
                if (which == 0) pasteClipboard()
            }
            .show()
    }

    // ---------- 压缩（宿主侧 zip 打包） ----------

    /** 压缩选中项为 zip；paths 为容器内绝对路径，destZipPath 为容器内目标 zip 路径 */
    private fun compressEntries(paths: List<String>, destZipPath: String) {
        loading = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val zipFile = stageZip(paths) ?: return@withContext false
                    try {
                        val dir = destZipPath.substringBeforeLast('/').ifEmpty { "/" }
                        val r = ContainerRuntime.exec(
                            this@FileBrowserActivity,
                            "mkdir -p ${ToolRegistry.shq(dir)} && cp /hosttmp/${zipFile.name} ${ToolRegistry.shq(destZipPath)} && echo OK || echo FAIL",
                            "/root", timeoutSec = 300
                        )
                        r.output.contains("OK")
                    } finally {
                        zipFile.delete()
                    }
                } catch (e: Exception) {
                    false
                }
            }
            loading = false
            Toast.makeText(this@FileBrowserActivity, if (ok) R.string.fb_compress_ok else R.string.fb_compress_fail, Toast.LENGTH_SHORT).show()
            if (ok) goTo(currentDir)
        }
    }

    /** 容器 cp 到 tmp（bind mount）→ 宿主侧 zip 打包，返回宿主 tmp 中的 zip 文件（用完需 delete） */
    private suspend fun stageZip(paths: List<String>): File? = withContext(Dispatchers.IO) {
        try {
            val tmp = ContainerRuntime.tmpDir(this@FileBrowserActivity)
            val stage = "stg-${UUID.randomUUID()}"
            val out = "out-${UUID.randomUUID()}.zip"
            val copies = paths.joinToString(" ") { ToolRegistry.shq(it) }
            val r = ContainerRuntime.exec(
                this@FileBrowserActivity,
                "rm -rf /hosttmp/$stage && mkdir -p /hosttmp/$stage && cp -r $copies /hosttmp/$stage/ && echo OK || echo FAIL",
                "/root", timeoutSec = 300
            )
            if (!r.output.contains("OK")) return@withContext null
            val stageDir = File(tmp, stage)
            val zipFile = File(tmp, out)
            try {
                ZipOutputStream(zipFile.outputStream()).use { zos ->
                    stageDir.listFiles()?.forEach { f -> addZipEntry(zos, f, f.name) }
                }
            } finally {
                stageDir.deleteRecursively()
            }
            if (zipFile.exists() && zipFile.length() > 0) zipFile else {
                zipFile.delete()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun addZipEntry(zos: ZipOutputStream, f: File, entryName: String) {
        if (f.isDirectory) {
            zos.putNextEntry(ZipEntry("$entryName/"))
            zos.closeEntry()
            f.listFiles()?.forEach { addZipEntry(zos, it, "$entryName/${it.name}") }
        } else {
            zos.putNextEntry(ZipEntry(entryName))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    // ---------- 多选 ----------

    private fun selectedPaths(): List<String> =
        selectedNames.sorted().map { dirJoin(currentDir, it) }

    private fun toggleSelect(name: String) {
        if (!selectedNames.add(name)) selectedNames.remove(name)
        adapter.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        binding.selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.selectionCount.text = getString(R.string.fb_selected_count, selectedNames.size)
    }

    private fun exitSelection() {
        if (!selectionMode && selectedNames.isEmpty()) return
        selectionMode = false
        selectedNames.clear()
        adapter.notifyDataSetChanged()
        updateSelectionBar()
    }

    /** 进入多选模式（工具栏"多选"入口） */
    private fun enterSelection() {
        selectionMode = true
        adapter.notifyDataSetChanged()
        updateSelectionBar()
        Toast.makeText(this, R.string.fb_multi_select, Toast.LENGTH_SHORT).show()
    }

    /** 多选长按菜单：只有 复制/移动/压缩/删除/导出到手机 五项 */
    private fun showSelectionMenu() {
        val items = listOf(
            getString(R.string.fb_copy),
            getString(R.string.fb_move),
            getString(R.string.fb_compress),
            getString(R.string.fb_delete),
            getString(R.string.fb_export)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_selected_count, selectedNames.size))
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    getString(R.string.fb_copy) -> copySelection()
                    getString(R.string.fb_move) -> moveSelection()
                    getString(R.string.fb_compress) -> compressSelection()
                    getString(R.string.fb_delete) -> deleteSelection()
                    getString(R.string.fb_export) -> exportSelection()
                }
            }
            .show()
    }

    private fun copySelection() {
        if (selectedNames.isEmpty()) return
        copyToClipboard(selectedPaths())
    }

    /** 多选"移动"：询问目标目录后 mv */
    private fun moveSelection() {
        if (selectedNames.isEmpty()) return
        val input = EditText(this)
        input.hint = getString(R.string.fb_dest_hint)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_dest_dir))
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val dest = input.text?.toString()?.trim().orEmpty()
                if (dest.isBlank()) return@setPositiveButton
                val ops = selectedPaths().joinToString(" && ") {
                    "mv ${ToolRegistry.shq(it)} ${ToolRegistry.shq(dest.trimEnd('/'))}/"
                }
                runContainerAction("$ops && echo OK || echo FAIL", getString(R.string.fb_moved), timeoutSec = 300) {
                    exitSelection()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 多选"压缩"：弹框询问压缩包名称 */
    private fun compressSelection() {
        if (selectedNames.isEmpty()) return
        val input = EditText(this)
        input.hint = getString(R.string.fb_zip_name_hint)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_compress))
            .setMessage(getString(R.string.fb_zip_name))
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank() || name.contains("/")) return@setPositiveButton
                val zipPath = dirJoin(currentDir, if (name.endsWith(".zip")) name else "$name.zip")
                compressEntries(selectedPaths(), zipPath)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelection() {
        if (selectedNames.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_delete))
            .setMessage(getString(R.string.fb_confirm_delete, getString(R.string.fb_selected_count, selectedNames.size)))
            .setPositiveButton("删除") { _, _ ->
                val ops = selectedPaths().joinToString(" && ") { "rm -rf -- ${ToolRegistry.shq(it)}" }
                runContainerAction("$ops && echo OK || echo FAIL", "已删除", timeoutSec = 300) {
                    exitSelection()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportSelection() {
        if (selectedNames.isEmpty()) return
        pendingExportMulti = selectedPaths()
        createDoc.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_TITLE, "selection.zip")
            }
        )
    }

    private fun renameEntry(path: String) {
        val input = EditText(this)
        input.setText(path.substringAfterLast('/'))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_rename))
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isBlank()) return@setPositiveButton
                val newPath = dirJoin(path.substringBeforeLast('/'), newName)
                runContainerAction("mv ${ToolRegistry.shq(path)} ${ToolRegistry.shq(newPath)} && echo OK || echo FAIL", "重命名完成")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteEntry(path: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_delete))
            .setMessage(getString(R.string.fb_confirm_delete, path))
            .setPositiveButton("删除") { _, _ ->
                runContainerAction("rm -rf -- ${ToolRegistry.shq(path)} && echo OK || echo FAIL", "已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createEntry(isDir: Boolean) {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(if (isDir) getString(R.string.fb_new_dir) else getString(R.string.fb_new_file))
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank() || name.contains("/")) return@setPositiveButton
                val path = dirJoin(currentDir, name)
                val op = if (isDir) "mkdir -p ${ToolRegistry.shq(path)}" else "touch ${ToolRegistry.shq(path)}"
                runContainerAction("$op && echo OK || echo FAIL", if (isDir) "目录已创建" else "文件已创建")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runContainerAction(
        cmd: String,
        successMsg: String,
        timeoutSec: Long = 30,
        onSuccess: (() -> Unit)? = null
    ) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                ContainerRuntime.exec(this@FileBrowserActivity, cmd, "/root", timeoutSec).output.contains("OK")
            }
            Toast.makeText(this@FileBrowserActivity, if (ok) successMsg else "操作失败", Toast.LENGTH_SHORT).show()
            if (ok) {
                onSuccess?.invoke()
                goTo(currentDir)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(android.view.Menu.NONE, 1, 0, getString(R.string.fb_up))
        menu.add(android.view.Menu.NONE, 2, 0, getString(R.string.fb_new_file))
        menu.add(android.view.Menu.NONE, 3, 0, getString(R.string.fb_new_dir))
        menu.add(android.view.Menu.NONE, 4, 0, getString(R.string.fb_refresh))
        menu.add(android.view.Menu.NONE, 5, 0, getString(R.string.fb_multi_select))
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish(); true
            }
            1 -> {
                val parent = currentDir.substringBeforeLast('/').ifEmpty { "/" }
                if (parent.isNotEmpty()) goTo(parent); true
            }
            2 -> { createEntry(false); true }
            3 -> { createEntry(true); true }
            4 -> { goTo(currentDir); true }
            5 -> { enterSelection(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---------- Adapter ----------

    class EntryAdapter(
        private val onClick: (Entry) -> Unit,
        private val onLongClick: (Entry) -> Unit,
        private val isSelected: (Entry) -> Boolean
    ) : ListAdapter<Entry, EntryAdapter.VH>(DIFF) {

        class VH(val b: ItemFileEntryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemFileEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = getItem(position)
            val b = holder.b
            b.name.text = e.name
            if (e.isDir) {
                b.icon.setImageResource(R.drawable.ic_folder)
                b.sub.text = "目录"
                b.size.text = ""
            } else {
                b.icon.setImageResource(R.drawable.ic_file)
                b.sub.text = "文件"
                b.size.text = format(e.size)
            }
            b.root.setBackgroundResource(
                if (isSelected(e)) R.drawable.bg_file_entry_selected else R.drawable.bg_file_entry
            )
            b.root.setOnClickListener { onClick(e) }
            b.root.setOnLongClickListener { onLongClick(e); true }
        }

        private fun format(n: Long): String = when {
            n < 1024 -> "$n B"
            n < 1048576 -> String.format("%.1f KB", n / 1024.0)
            n < 1073741824L -> String.format("%.1f MB", n / 1048576.0)
            else -> String.format("%.2f GB", n / 1073741824.0)
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<Entry>() {
                override fun areItemsTheSame(a: Entry, b: Entry) = a.name == b.name
                override fun areContentsTheSame(a: Entry, b: Entry) = a == b
            }
        }
    }

    class SpacingDecoration(private val px: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State
        ) {
            outRect.bottom = px
        }
    }

    companion object {
        private val TEXT_EXTS = setOf(
            "txt", "md", "json", "py", "js", "ts", "java", "kt", "kts", "c", "h", "cpp", "hpp",
            "cs", "go", "rs", "swift", "dart", "sh", "bash", "zsh", "xml", "yml", "yaml", "ini",
            "conf", "log", "html", "htm", "css", "sql", "toml", "csv", "properties", "env",
            "gitignore", "dockerfile", "gradle", "proto", "rb", "php", "pl", "lua", "scala",
            "groovy", "svelte", "vue", "rst", "tex", "cfg", "bat", "ps1"
        )
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
        private val AUDIO_EXTS = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus")
        private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "ts", "flv")
        private val ARCHIVE_EXTS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "tbz2", "txz")
    }
}
