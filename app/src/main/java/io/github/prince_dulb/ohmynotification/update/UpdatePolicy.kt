package io.github.prince_dulb.ohmynotification.update

import java.net.URI

data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int, val candidate: Boolean = false) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other,
        ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch, { !it.candidate })

    companion object {
        fun parse(value: String): ReleaseVersion? {
            val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(-rc\\d+)?$").matchEntire(value) ?: return null
            val numbers = match.groupValues.slice(1..3).map { it.toIntOrNull() ?: return null }
            return ReleaseVersion(numbers[0], numbers[1], numbers[2], match.groupValues[4].isNotEmpty())
        }
    }
}

internal object UpdatePolicy {
    const val RELEASES_URL = "https://github.com/Scathiard/OhMyNotification/releases"
    const val API_URL = "https://api.github.com/repos/Scathiard/OhMyNotification/releases/latest"
    private val repositories = setOf("Scathiard/OhMyNotification", "prince-dulb/OhMyNotification")
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    fun requireAssetUrl(url: String, tag: String, filename: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null)
        require(repositories.any { uri.rawPath == "/$it/releases/download/$tag/$filename" })
        require(uri.query == null && uri.fragment == null)
    }

    fun checksum(text: String, filename: String): String {
        val match = Regex("^([a-fA-F0-9]{64})[ \\t]+\\*?([^\\r\\n]+)$").matchEntire(text.trim())
            ?: error("摘要文件格式不正确")
        require(match.groupValues[2] == filename) { "摘要文件对应的 APK 名称不一致" }
        return match.groupValues[1].lowercase(java.util.Locale.ROOT)
    }

    fun verifyIdentity(
        expectedPackage: String, actualPackage: String, installedCode: Long, candidateCode: Long,
        installedSigners: Set<String>, candidateSigners: Set<String>,
    ) {
        require(actualPackage == expectedPackage) { "APK 包名不匹配" }
        require(candidateCode > installedCode) { "APK 版本号没有高于当前版本" }
        require(installedSigners.isNotEmpty() && installedSigners == candidateSigners) { "APK 签名证书不匹配" }
    }
}
