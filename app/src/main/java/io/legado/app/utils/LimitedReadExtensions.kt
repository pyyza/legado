package io.legado.app.utils

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 高级标题导入用的限长读取工具。
 *
 * 与 [readBytes] 的区别：这里强制限制最大字节数，避免用户误选超大文件时把整份内容
 * 读进内存；超限直接抛 [IOException]，由调用方提示。
 */

/** 读取 Uri 内容，最多 [maxBytes] 字节。 */
@Throws(Exception::class)
fun Uri.readBytes(context: Context, maxBytes: Long): ByteArray {
    if (isContentScheme()) {
        return context.contentResolver.openInputStream(this)?.use {
            it.readBytesLimited(maxBytes)
        } ?: throw IOException("打开文件失败\n$this")
    }
    val path = RealPathUtil.getPath(context, this)
    if (path.isNullOrEmpty()) {
        throw IOException("获取文件真实地址失败\n${this.path}")
    }
    val file = File(path)
    require(file.length() <= maxBytes) { "Lottie JSON exceeds the size limit" }
    return file.readBytes()
}

/** 限长读取流；累计超过 [maxBytes] 立即抛 [IOException]。 */
@Throws(IOException::class)
fun InputStream.readBytesLimited(maxBytes: Long): ByteArray {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    val out = ByteArrayOutputStream()
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw IOException("Lottie JSON exceeds the size limit")
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
