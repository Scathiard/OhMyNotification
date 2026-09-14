package io.github.prince_dulb.ohmynotification.update

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal object UpdateFileVerifier {
    /** Copy to a private staging file and hash in one pass. Never accept partial input. */
    fun copyVerified(input: InputStream, staging: File, expectedSize: Long, expectedSha: String) {
        require(expectedSize in 1..UpdatePolicy.MAX_APK_BYTES) { "APK 大小超出允许范围" }
        require(expectedSha.matches(Regex("[0-9a-f]{64}"))) { "摘要格式无效" }
        var complete = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            staging.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) throw java.io.IOException("下载流未返回数据")
                    total += count
                    require(total <= expectedSize) { "下载文件超出声明大小" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                require(total == expectedSize) { "下载文件大小不匹配" }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == expectedSha) { "SHA-256 摘要不匹配，已拒绝安装" }
            complete = true
        } finally { if (!complete && staging.exists()) staging.delete() }
    }
}
