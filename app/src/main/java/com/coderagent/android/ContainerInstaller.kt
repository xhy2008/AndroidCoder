package com.coderagent.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * 一次性容器安装流程：
 * 1. 准备 proroot 原生库
 * 2. rootfs 来源：优先解压 APK 内置层（assets/rootfs 下的 tar.gz 层文件），缺失时回退在线 OCI 拉取
 * 3. 写入 resolv.conf / hosts
 * 4. 容器内运行 bootstrap：apt 换国内镜像源 + 创建 workspace + 安装基础工具（可选开发工具）
 */
object ContainerInstaller {
    private const val TAG = "ContainerInstaller"

    suspend fun install(
        ctx: Context,
        onProgress: (phase: String, fraction: Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        onProgress("准备 proroot 运行时", 0f)
        if (!ContainerRuntime.ensureNative(ctx)) {
            throw IOException("proroot 原生库准备失败：APK 缺少 native 库或复制失败")
        }

        val rootfs = ContainerRuntime.rootfsDir(ctx)
        // 清理半安装残留（rootfs 存在但未完整安装时）
        if (rootfs.exists() && !ContainerRuntime.isInstalled(ctx)) {
            rootfs.deleteRecursively()
        }
        if (ContainerRuntime.isInstalled(ctx)) {
            onProgress("rootfs 已就绪，初始化基础工具", 0.85f)
        } else if (assetsRootfsExists(ctx)) {
            onProgress("解压内置 Debian rootfs", 0f)
            extractAssets(ctx, rootfs, onProgress)
        } else {
            onProgress("下载 Debian rootfs（多镜像源回退）", 0f)
            OciPuller.pull(
                image = "debian",
                tag = "stable-slim",
                destDir = rootfs,
                onProgress = { p -> onProgress(p.phase, p.fraction) }
            )
        }

        onProgress("配置容器网络", 0.88f)
        setupNetConfig(ctx)

        onProgress("初始化容器（apt 安装基础工具，可能需要几分钟）", 0.9f)
        runBootstrap(ctx)

        onProgress("安装完成", 1f)
    }

    /** APK 是否内置了 rootfs 层 */
    private fun assetsRootfsExists(ctx: Context): Boolean {
        return try {
            val names = ctx.assets.list("rootfs") ?: emptyArray()
            names.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /** 解压内置的 rootfs 层（按文件名排序；层文件为 gzip 压缩的 tar） */
    private fun extractAssets(ctx: Context, rootfs: File, onProgress: (String, Float) -> Unit) {
        val names = (ctx.assets.list("rootfs") ?: emptyArray()).sorted()
        if (names.isEmpty()) throw IOException("assets/rootfs 中没有可用的 rootfs 层")
        rootfs.mkdirs()
        names.forEachIndexed { i, name ->
            onProgress("解压内置 rootfs ${i + 1}/${names.size}", i.toFloat() / names.size)
            ctx.assets.open("rootfs/$name").use { ins ->
                val input = when {
                    name.endsWith(".tar.xz") -> XZCompressorInputStream(ins)
                    else -> GZIPInputStream(ins)
                }
                OciPuller.extractTarStream(input, rootfs)
            }
        }
    }

    private fun setupNetConfig(ctx: Context) {
        val rootfs = ContainerRuntime.rootfsDir(ctx)
        val resolv = File(rootfs, "etc/resolv.conf")
        var content: String? = null
        try {
            val sys = File("/system/etc/resolv.conf")
            if (sys.exists()) content = sys.readText()
        } catch (_: Exception) {
        }
        if (content == null || !content.contains("nameserver")) {
            // 国内网络下 8.8.8.8/8.8.4.4 常被污染或不可达，改用国内公共 DNS
            content = "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 114.114.114.114\n"
        }
        resolv.writeText(content)
        File(rootfs, "etc/hosts").writeText(
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
        )
    }

    /**
     * 预置 CA 证书：读取 Android 系统 CA（/system/etc/security/cacerts，Android 14+ 为 DER 二进制，
     * 早期为 PEM），统一用 CertificateFactory 解析后导出标准 PEM，写入容器 /etc/ssl/certs
     * 并生成 ca-certificates.crt bundle。否则 https 源会报 certificate verify failed。
     */
    private fun installCACerts(ctx: Context) {
        val src = File("/system/etc/security/cacerts")
        if (!src.isDirectory) return
        val certsDir = File(ContainerRuntime.rootfsDir(ctx), "etc/ssl/certs")
        certsDir.mkdirs()
        val bundle = StringBuilder()
        try {
            val factory = java.security.cert.CertificateFactory.getInstance("X.509")
            src.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".0")) {
                    try {
                        f.inputStream().use { ins ->
                            val cert = factory.generateCertificate(ins)
                            val pem = "-----BEGIN CERTIFICATE-----\n" +
                                android.util.Base64.encodeToString(
                                    cert.encoded, android.util.Base64.NO_WRAP
                                ) + "\n-----END CERTIFICATE-----\n"
                            val dst = File(certsDir, f.name)
                            if (!dst.exists()) dst.writeText(pem)
                            bundle.append(pem)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (bundle.isNotEmpty()) {
            File(certsDir, "ca-certificates.crt").writeText(bundle.toString())
        }
    }

    private fun runBootstrap(ctx: Context) {
        installCACerts(ctx)
        val script = """
            export DEBIAN_FRONTEND=noninteractive
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            mkdir -p /root/workspace
            # 强制 https + 多镜像回退：清华 → 阿里云 → USTC。
            # 明文 http 常被运营商劫持/镜像站拒发（403），https 配合预置 CA 证书最稳。
            for m in mirrors.tuna.tsinghua.edu.cn mirrors.aliyun.com mirrors.ustc.edu.cn; do
                for f in /etc/apt/sources.list.d/debian.sources /etc/apt/sources.list; do
                    if [ -f ${'$'}f ]; then
                        sed -i "s|https\?://[^/]*/debian-security|https://${'$'}m/debian-security|g" ${'$'}f
                        sed -i "s|https\?://[^/]*/debian|https://${'$'}m/debian|g" ${'$'}f
                    fi
                done
                # 引导阶段跳过 https 证书验证：先装上 ca-certificates 包，装完后容器内即拥有
                # Debian 完整 CA 集，后续 https 验证自动恢复正常。
                apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=60 -o Acquire::https::Verify-Peer=false -o Acquire::https::Verify-Host=false update > /tmp/apt-update.log 2>&1
                if [ ${'$'}? -eq 0 ] && ! grep -qE "Failed to fetch|SSL connection failed|Forbidden" /tmp/apt-update.log; then
                    break
                fi
                echo "[bootstrap] 镜像 ${'$'}m 更新失败，切换下一个镜像"
            done
            cat /tmp/apt-update.log
            apt-get -o Acquire::https::Verify-Peer=false -o Acquire::https::Verify-Host=false install -y --no-install-recommends ca-certificates curl git gawk
        """.trimIndent()
        val r = ContainerRuntime.exec(ctx, script, "/root", timeoutSec = 900)
        if (r.exitCode != 0) {
            Log.e(TAG, "bootstrap 失败: exit=${r.exitCode}\n${r.stderr}\n${r.stdout}")
            throw IOException(
                "容器初始化失败（exit=${r.exitCode}）:\n" +
                    (r.stderr.trim().takeLast(1500).ifBlank { r.stdout.trim().takeLast(1500) })
            )
        }
        // 兜底：确保工作区存在
        File(ContainerRuntime.rootfsDir(ctx), "root/workspace").mkdirs()
    }
}
