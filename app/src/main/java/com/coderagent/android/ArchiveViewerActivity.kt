package com.coderagent.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.coderagent.android.databinding.ActivityArchiveViewerBinding
import com.coderagent.android.databinding.ItemFileEntryBinding
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * 压缩包浏览：容器内压缩包导出到本地后用 commons-compress 解析，
 * 以虚拟目录方式浏览 zip / tar / tar.gz / tar.bz2 / tar.xz 内容。
 */
class ArchiveViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArchiveViewerBinding
    private var archivePath = ""
    private var kind = KIND_UNSUPPORTED
    private var localArchive: File? = null
    private val all = mutableListOf<ArchEntry>()
    private var prefix = ""
    private lateinit var adapter: EntryAdapter
    private var pendingExport: File? = null

    data class ArchEntry(val fullPath: String, val isDir: Boolean, val size: Long)

    private val createDoc = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri -> doExport(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArchiveViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        archivePath = intent.getStringExtra("path") ?: ""
        binding.toolbar.title = archivePath.substringAfterLast('/')
        kind = kindOf(archivePath)
        if (kind == KIND_UNSUPPORTED) {
            binding.emptyView.text = getString(R.string.archive_unsupported)
            binding.emptyView.visibility = View.VISIBLE
            return
        }

        adapter = EntryAdapter(
            onClick = { e ->
                when {
                    e.name == ".." -> { prefix = parentOf(prefix); refresh() }
                    e.isDir -> { prefix = e.fullPath; refresh() }
                    else -> previewEntry(e.fullPath)
                }
            },
            onLongClick = { e -> if (e.name != ".." && !e.isDir) extractAndExport(e) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { parse() }
            if (entries == null) {
                binding.emptyView.text = getString(R.string.archive_load_err)
                binding.emptyView.visibility = View.VISIBLE
                return@launch
            }
            all.clear()
            all.addAll(entries)
            refresh()
        }
    }

    /** 导出到本地并解析全部条目；返回 null 表示失败 */
    private fun parse(): List<ArchEntry>? {
        return try {
            val tmp = ContainerRuntime.tmpDir(this)
            val tmpName = "ar-${UUID.randomUUID()}"
            val r = ContainerRuntime.exec(
                this,
                "cp ${ToolRegistry.shq(archivePath)} /hosttmp/$tmpName && echo OK || echo FAIL",
                "/root", timeoutSec = 300
            )
            if (!r.output.contains("OK")) return null
            val src = File(tmp, tmpName)
            val dst = File(cacheDir, tmpName + ".arch")
            src.copyTo(dst, overwrite = true)
            src.delete()
            localArchive = dst

            val out = mutableListOf<ArchEntry>()
            val stream = openArchiveStream(dst) ?: return null
            stream.use { s ->
                when (kind) {
                    KIND_ZIP -> {
                        val z = s as ZipArchiveInputStream
                        var e = z.nextEntry
                        while (e != null) {
                            out += ArchEntry(e.name, e.isDirectory || e.name.endsWith("/"), e.size)
                            e = z.nextEntry
                        }
                    }
                    else -> {
                        val t = s as TarArchiveInputStream
                        var e: TarArchiveEntry? = t.nextEntry
                        while (e != null) {
                            out += ArchEntry(e.name.removeSuffix("/"), e.isDirectory, e.size)
                            e = t.nextEntry
                        }
                    }
                }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun openArchiveStream(f: File): InputStream? {
        val base: InputStream = when (kind) {
            KIND_ZIP -> return ZipArchiveInputStream(BufferedInputStream(FileInputStream(f)))
            KIND_TAR -> return TarArchiveInputStream(BufferedInputStream(FileInputStream(f)))
            KIND_GZIP_TAR -> GzipCompressorInputStream(BufferedInputStream(FileInputStream(f)))
            KIND_BZIP_TAR -> BZip2CompressorInputStream(BufferedInputStream(FileInputStream(f)))
            KIND_XZ_TAR -> XZCompressorInputStream(BufferedInputStream(FileInputStream(f)))
            else -> return null
        }
        return TarArchiveInputStream(BufferedInputStream(base))
    }

    private fun refresh() {
        binding.toolbar.subtitle = if (prefix.isEmpty()) getString(R.string.archive_root) else "/$prefix"
        val visible = mutableListOf<Entry>()
        if (prefix.isNotEmpty()) visible.add(Entry("..", parentOf(prefix), true, 0))
        val seen = HashSet<String>()
        for (e in all) {
            if (prefix.isNotEmpty() && !e.fullPath.startsWith(prefix)) continue
            val rest = if (prefix.isEmpty()) e.fullPath else e.fullPath.removePrefix(prefix)
            if (rest.startsWith("/")) continue
            val first = rest.substringBefore('/')
            if (first.isBlank() || !seen.add(first)) continue
            val isDir = e.isDir || rest.contains('/')
            val childPrefix = if (prefix.isEmpty()) "" else "$prefix/"
            visible.add(
                Entry(
                    if (isDir) "$first/" else first,
                    childPrefix + first + (if (isDir) "/" else ""),
                    isDir,
                    if (isDir) 0 else e.size
                )
            )
        }
        visible.sortWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
        adapter.submitList(visible)
        binding.emptyView.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 提取压缩包内文件内容（文本预览） */
    private fun previewEntry(fullPath: String) {
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) { extractText(fullPath) }
            if (content == null) {
                Toast.makeText(this@ArchiveViewerActivity, R.string.archive_load_err, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (content == "BINARY") {
                AlertDialog.Builder(this@ArchiveViewerActivity)
                    .setTitle(fullPath.substringAfterLast('/'))
                    .setMessage("该条目为二进制文件，无法预览。可长按导出。")
                    .setPositiveButton("关闭", null)
                    .show()
                return@launch
            }
            AlertDialog.Builder(this@ArchiveViewerActivity)
                .setTitle(fullPath.substringAfterLast('/'))
                .setMessage(content.take(40000).ifBlank { "（空文件）" })
                .setPositiveButton("关闭", null)
                .setNeutralButton(getString(R.string.archive_extract)) { _, _ -> extractAndExportPath(fullPath) }
                .show()
        }
    }

    private fun extractText(fullPath: String): String? {
        val f = localArchive ?: return null
        return try {
            val stream = openArchiveStream(f) ?: return null
            stream.use { s ->
                when (kind) {
                    KIND_ZIP -> {
                        val z = s as ZipArchiveInputStream
                        var e = z.nextEntry
                        while (e != null && e.name != fullPath) e = z.nextEntry
                        if (e == null || e.isDirectory) return null
                        readLimited(z)
                    }
                    else -> {
                        val t = s as TarArchiveInputStream
                        var e = t.nextEntry
                        while (e != null && e.name.removeSuffix("/") != fullPath) e = t.nextEntry
                        if (e == null || e.isDirectory) return null
                        readLimited(t)
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readLimited(s: InputStream): String? {
        val bytes = ByteArray(200_000)
        var total = 0
        while (total < bytes.size) {
            val n = s.read(bytes, total, bytes.size - total)
            if (n < 0) break
            total += n
        }
        if (total == 0) return ""
        if (bytes.copyOfRange(0, total).contains(0.toByte())) return "BINARY"
        return String(bytes, 0, total, Charsets.UTF_8)
    }

    /** 长按文件：提取到本地并用 SAF 导出 */
    private fun extractAndExport(e: Entry) {
        if (pendingExport != null) {
            Toast.makeText(this, "正在导出，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) { extractToLocal(e.fullPath, e.name.substringBeforeLast('/').ifEmpty { e.name }) }
            if (local == null) {
                Toast.makeText(this@ArchiveViewerActivity, R.string.archive_load_err, Toast.LENGTH_SHORT).show()
                return@launch
            }
            pendingExport = local
            createDoc.launch(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_TITLE, e.name.substringBeforeLast('/').ifEmpty { "export" })
                }
            )
        }
    }

    private fun extractAndExportPath(fullPath: String) {
        val name = fullPath.substringAfterLast('/')
        lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) { extractToLocal(fullPath, name) }
            if (local == null) {
                Toast.makeText(this@ArchiveViewerActivity, R.string.archive_load_err, Toast.LENGTH_SHORT).show()
                return@launch
            }
            pendingExport = local
            createDoc.launch(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_TITLE, name.ifEmpty { "export" })
                }
            )
        }
    }

    private fun extractToLocal(fullPath: String, defaultName: String): File? {
        return try {
            val f = localArchive ?: return null
            val stream = openArchiveStream(f) ?: return null
            val dst = File(cacheDir, "ext-${UUID.randomUUID()}-${defaultName.take(40)}")
            stream.use { s ->
                when (kind) {
                    KIND_ZIP -> {
                        val z = s as ZipArchiveInputStream
                        var e = z.nextEntry
                        while (e != null && e.name != fullPath) e = z.nextEntry
                        if (e == null || e.isDirectory) return null
                        dst.outputStream().use { out -> z.copyTo(out) }
                    }
                    else -> {
                        val t = s as TarArchiveInputStream
                        var e = t.nextEntry
                        while (e != null && e.name.removeSuffix("/") != fullPath) e = t.nextEntry
                        if (e == null || e.isDirectory) return null
                        dst.outputStream().use { out -> t.copyTo(out) }
                    }
                }
            }
            dst
        } catch (e: Exception) {
            null
        }
    }

    private fun doExport(uri: Uri) {
        val local = pendingExport ?: return
        pendingExport = null
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out -> local.inputStream().use { it.copyTo(out) } } != null
                } catch (e: Exception) {
                    false
                } finally {
                    local.delete()
                }
            }
            Toast.makeText(this@ArchiveViewerActivity, if (ok) R.string.fb_export_ok else R.string.fb_export_fail, Toast.LENGTH_SHORT).show()
        }
    }

    private fun parentOf(p: String): String {
        if (p.isEmpty()) return ""
        val trimmed = p.removeSuffix("/")
        return trimmed.substringBeforeLast('/').ifEmpty { "" }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish(); return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        localArchive?.delete()
        super.onDestroy()
    }

    data class Entry(val name: String, val fullPath: String, val isDir: Boolean, val size: Long)

    class EntryAdapter(
        private val onClick: (Entry) -> Unit,
        private val onLongClick: (Entry) -> Unit
    ) : ListAdapter<Entry, EntryAdapter.VH>(DIFF) {

        class VH(val b: ItemFileEntryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemFileEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
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
                override fun areItemsTheSame(a: Entry, b: Entry) = a.fullPath == b.fullPath
                override fun areContentsTheSame(a: Entry, b: Entry) = a == b
            }
        }
    }

    companion object {
        private const val KIND_UNSUPPORTED = 0
        private const val KIND_ZIP = 1
        private const val KIND_TAR = 2
        private const val KIND_GZIP_TAR = 3
        private const val KIND_BZIP_TAR = 4
        private const val KIND_XZ_TAR = 5

        private fun kindOf(path: String): Int {
            val p = path.lowercase()
            return when {
                p.endsWith(".zip") -> KIND_ZIP
                p.endsWith(".tar") -> KIND_TAR
                p.endsWith(".tar.gz") || p.endsWith(".tgz") -> KIND_GZIP_TAR
                p.endsWith(".tar.bz2") || p.endsWith(".tbz2") -> KIND_BZIP_TAR
                p.endsWith(".tar.xz") || p.endsWith(".txz") -> KIND_XZ_TAR
                else -> KIND_UNSUPPORTED
            }
        }
    }
}
