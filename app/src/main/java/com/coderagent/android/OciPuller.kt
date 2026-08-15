package com.coderagent.android

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONArray
import org.json.JSONObject

/** 下载/解压进度 */
data class PullProgress(val phase: String, val fraction: Float)

/**
 * OCI 镜像拉取器（在线下载 rootfs 的 fallback）。
 * 支持多个镜像源自动回退：DaoCloud 加速 -> 阿里云 ACR -> Docker Hub 官方。
 * 标准流程：请求 manifest 拿 WWW-Authenticate -> 获取 Bearer token -> 拉取 arm64 清单 -> 下载并解压各层。
 */
object OciPuller {
    private const val TAG = "OciPuller"

    private val REGISTRIES = listOf(
        "https://docker.m.daocloud.io",
        "https://registry.cn-hangzhou.aliyuncs.com",
        "https://registry-1.docker.io"
    )

    private val INDEX_ACCEPT =
        "application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.index.v1+json"
    private val MANIFEST_ACCEPT =
        "application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 依次尝试各镜像源，直到某个成功 */
    suspend fun pull(
        image: String = "debian",
        tag: String = "stable-slim",
        destDir: File,
        onProgress: (PullProgress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        var lastErr: Exception? = null
        for (registry in REGISTRIES) {
            try {
                pullFrom(registry, image, tag, destDir, onProgress)
                return@withContext
            } catch (e: Exception) {
                lastErr = e
                Log.w(TAG, "镜像源 $registry 拉取失败: ${e.message}")
            }
        }
        throw IOException("所有镜像源均拉取失败: ${lastErr?.message}")
    }

    private fun pullFrom(
        registry: String,
        image: String,
        tag: String,
        destDir: File,
        onProgress: (PullProgress) -> Unit
    ) {
        val token = getToken(registry, image, tag)
        val index = getJson("$registry/v2/library/$image/manifests/$tag", token, INDEX_ACCEPT)
        val manifest = resolveManifest(index, registry, image, token)
        val layers = manifest.getJSONArray("layers")
        var total = 0L
        for (i in 0 until layers.length()) total += layers.getJSONObject(i).optLong("size", 0L)

        destDir.mkdirs()
        var done = 0L
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val mediaType = l.getString("mediaType")
            val blob = l.getString("digest")
            val prog: (String) -> Unit = { phase ->
                val f = if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
                onProgress(PullProgress(phase, f))
            }
            val tmp = File.createTempFile("layer", ".tar", destDir)
            try {
                downloadBlob(registry, image, blob, tmp, token) { n ->
                    done += n
                    prog("下载层 ${i + 1}/${layers.length()} (${done / 1024 / 1024} MB)")
                }
                prog("解压层 ${i + 1}/${layers.length()}")
                extractLayer(tmp, destDir, mediaType)
            } finally {
                tmp.delete()
            }
        }
        onProgress(PullProgress("rootfs 就绪", 1f))
    }

    /** 无 token 请求触发 401，从 WWW-Authenticate 解析 realm/service/scope 获取匿名 token */
    private fun getToken(registry: String, image: String, tag: String): String {
        val probe = Request.Builder()
            .url("$registry/v2/library/$image/manifests/$tag")
            .header("Accept", INDEX_ACCEPT)
            .get().build()
        val challenge: String
        client.newCall(probe).execute().use { resp ->
            if (resp.code != 401) {
                throw IOException("镜像源认证探测失败 (HTTP ${resp.code})")
            }
            challenge = resp.header("WWW-Authenticate")
                ?: throw IOException("响应缺少 WWW-Authenticate")
        }
        if (!challenge.startsWith("Bearer", ignoreCase = true)) {
            throw IOException("不支持的认证方式: $challenge")
        }
        val realm = Regex("""realm="([^"]+)"""").find(challenge)?.groupValues?.get(1)
            ?: throw IOException("WWW-Authenticate 缺少 realm")
        val service = Regex("""service="([^"]+)"""").find(challenge)?.groupValues?.get(1)
        val scope = Regex("""scope="([^"]+)"""").find(challenge)?.groupValues?.get(1)
            ?: "repository:library/$image:pull"
        val params = mutableListOf("scope=${java.net.URLEncoder.encode(scope, "UTF-8")}")
        service?.let { params.add(0, "service=${java.net.URLEncoder.encode(it, "UTF-8")}") }
        val url = "$realm?${params.joinToString("&")}"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("token 获取失败: ${resp.code}")
            val body = JSONObject(resp.body!!.string())
            return body.getString("access_token")
        }
    }

    private fun getJson(url: String, token: String, accept: String): JSONObject {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", accept)
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("registry 请求失败 $url: ${resp.code}")
            val text = resp.body!!.string()
            if (text.isEmpty()) return JSONObject()
            return JSONObject(text)
        }
    }

    /** index/list -> 选 arm64 平台 manifest digest；若本身就是单 manifest 则直接用 */
    private fun resolveManifest(index: JSONObject, registry: String, image: String, token: String): JSONObject {
        val arr = index.optJSONArray("manifests")
        if (arr == null || arr.length() == 0) {
            return index
        }
        var digest: String? = null
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val p = m.optJSONObject("platform")
            if (p != null && "arm64" == p.optString("architecture") && "linux" == p.optString("os")) {
                digest = m.optString("digest")
                break
            }
        }
        if (digest == null) throw IOException("镜像 $image 不支持 arm64")
        return getJson("$registry/v2/library/$image/manifests/$digest", token, MANIFEST_ACCEPT)
    }

    private fun downloadBlob(
        registry: String,
        image: String,
        blob: String,
        dest: File,
        token: String,
        onBytes: (Long) -> Unit
    ) {
        val req = Request.Builder()
            .url("$registry/v2/library/$image/blobs/$blob")
            .header("Authorization", "Bearer $token")
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("blob 下载失败: ${resp.code}")
            val src = resp.body!!.byteStream().buffered()
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(256 * 1024)
                var n: Int
                while (src.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    onBytes(n.toLong())
                }
            }
        }
    }

    private fun extractLayer(layer: File, destDir: File, mediaType: String) {
        val raw = BufferedInputStream(layer.inputStream())
        val input = when {
            mediaType.contains("gzip") -> GZIPInputStream(raw)
            mediaType.contains("zstd") -> throw IOException("不支持的层压缩格式 zstd（请更换镜像 tag）")
            else -> raw
        }
        extractTarStream(input, destDir)
    }

    /** 通用 tar 解压（供在线下载与内置 assets 解压共用） */
    fun extractTarStream(input: InputStream, destDir: File) {
        TarArchiveInputStream(input).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.startsWith(".wh.")) {
                    val target = destDir.resolve(name.removePrefix(".wh."))
                    target.deleteRecursively()
                } else if (name.endsWith("/.wh..wh..opq") || name.endsWith("/.wh..wh.aufs")) {
                    // opaque 目录标记，忽略
                } else {
                    val target = safeResolve(destDir, name)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            try {
                                Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(entry.linkName))
                            } catch (e: Exception) {
                                // 极少数设备不允许符号链接时退化为文本占位
                                target.writeText(entry.linkName)
                            }
                        }
                        entry.isLink -> {
                            target.parentFile?.mkdirs()
                            val hardSrc = safeResolve(destDir, entry.linkName)
                            if (hardSrc.exists()) {
                                try {
                                    Files.createLink(target.toPath(), hardSrc.toPath())
                                } catch (e: Exception) {
                                    hardSrc.copyTo(target, overwrite = true)
                                }
                            }
                        }
                        else -> {
                            target.parentFile?.mkdirs()
                            copyEntryData(tar, target)
                            val mode = entry.mode and 0x1FF
                            val exec = (mode and 0x49) != 0
                            try {
                                target.setExecutable(exec, false)
                                target.setReadable((mode and 0x124) != 0, false)
                                target.setWritable((mode and 0x92) != 0, false)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    private fun copyEntryData(tar: TarArchiveInputStream, target: File) {
        FileOutputStream(target).use { out ->
            val buf = ByteArray(128 * 1024)
            var n: Int
            while (tar.read(buf).also { n = it } != -1) out.write(buf, 0, n)
        }
    }

    private fun safeResolve(base: File, name: String): File {
        val cleaned = name.removePrefix("./")
        val target = File(base, cleaned)
        val canonical = target.canonicalPath
        val baseCanonical = base.canonicalPath
        if (!canonical.startsWith(baseCanonical + File.separator) && canonical != baseCanonical) {
            throw IOException("tar 条目越界: $name")
        }
        return target
    }
}
