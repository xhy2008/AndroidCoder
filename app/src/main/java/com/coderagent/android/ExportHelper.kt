package com.coderagent.android

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 将容器内文件导出到手机存储（SAF Uri）：
 * 容器 cp 到 tmp（bind mount）→ 宿主流式写入 Uri，支持任意大小。
 */
object ExportHelper {

    suspend fun export(ctx: Context, containerPath: String, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val tmp = ContainerRuntime.tmpDir(ctx)
                val name = "exp-${UUID.randomUUID()}"
                val r = ContainerRuntime.exec(
                    ctx,
                    "cp ${ToolRegistry.shq(containerPath)} /hosttmp/$name && echo OK || echo FAIL",
                    "/root", timeoutSec = 300
                )
                if (!r.output.contains("OK")) return@withContext false
                val src = File(tmp, name)
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    } ?: return@withContext false
                } finally {
                    src.delete()
                }
                true
            } catch (e: Exception) {
                false
            }
        }
}
