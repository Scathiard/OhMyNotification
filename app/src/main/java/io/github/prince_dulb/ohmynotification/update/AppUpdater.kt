package io.github.prince_dulb.ohmynotification.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.SharedPreferences
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, ERROR }
data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val message: String = "只在你点击时检查 GitHub 正式版本")

/** No scheduler or application startup network. All network starts from explicit UI actions. */
class AppUpdater internal constructor(
    context: Context,
    private val downloads: UpdateDownloads = AndroidUpdateDownloads(context.applicationContext),
    private val preferences: SharedPreferences = context.getSharedPreferences("omn-updates", Context.MODE_PRIVATE),
    private val readText: (String, Int) -> String = ::getGithubText,
    private val directory: File = File(context.filesDir, "updates"),
) {
    private val context = context.applicationContext
    private val mutex = updateOperationMutex
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()

    val currentVersion: String get() = installedPackage().versionName.orEmpty()

    suspend fun check() = operation {
        cleanObsoleteVerificationFiles()
        if (preferences.getLong("download-id", -1) > 0) {
            val refreshed = runCatching { refreshLocked() }.isSuccess
            if (!refreshed) {
                discardDownloadLocked()
                mutableState.value = UpdateState()
            }
            if (mutableState.value.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.READY)) return@operation
        }
        mutableState.value = UpdateState(UpdatePhase.CHECKING, "正在检查 GitHub…")
        val json = JSONObject(readText(UpdatePolicy.API_URL, 1_000_000))
        require(!json.getBoolean("draft") && !json.getBoolean("prerelease")) { "只接受正式 Release" }
        val tag = json.getString("tag_name")
        val version = ReleaseVersion.parse(tag) ?: error("无法识别发布版本")
        require(!version.candidate) { "不接受候选版更新" }
        val installed = ReleaseVersion.parse(currentVersion) ?: error("无法识别当前版本")
        if (version <= installed) {
            mutableState.value = UpdateState(UpdatePhase.IDLE, "当前已是最新版本（$currentVersion）")
            return@operation
        }
        val name = "OhMyNotification-${tag.removePrefix("v")}-release.apk"
        val assets = json.getJSONArray("assets")
        fun asset(filename: String): JSONObject {
            val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name") == filename }
            require(matches.size == 1) { "正式版本缺少唯一的 $filename 附件" }
            return matches.single()
        }
        val apk = asset(name)
        val checksum = asset("$name.sha256")
        val apkUrl = apk.getString("browser_download_url")
        val checksumUrl = checksum.getString("browser_download_url")
        UpdatePolicy.requireAssetUrl(apkUrl, tag, name)
        UpdatePolicy.requireAssetUrl(checksumUrl, tag, "$name.sha256")
        val size = apk.getLong("size")
        require(size in 1..UpdatePolicy.MAX_APK_BYTES) { "APK 大小超出允许范围" }
        val sha = UpdatePolicy.checksum(readText(checksumUrl, 2048), name)
        val metadata = JSONObject().put("version", tag.removePrefix("v")).put("url", apkUrl)
            .put("sha", sha).put("size", size).put("name", name).put("tag", tag)
        require(preferences.edit().putString("release", metadata.toString()).commit()) { "无法保存更新信息" }
        mutableState.value = UpdateState(UpdatePhase.AVAILABLE, "发现 ${tag.removePrefix("v")}，约 ${size / 1024 / 1024} MB")
    }

    suspend fun download() = operation {
        val oldId = preferences.getLong("download-id", -1)
        if (oldId > 0) {
            val retryFailed = mutableState.value.phase == UpdatePhase.ERROR
            val refreshed = runCatching { refreshLocked() }.isSuccess
            if (!retryFailed && refreshed && mutableState.value.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.READY)) return@operation
            discardDownloadLocked()
        }
        val metadata = metadata()
        val url = metadata.getString("url")
        val filename = metadata.getString("name")
        UpdatePolicy.requireAssetUrl(url, metadata.getString("tag"), filename)
        val id = downloads.enqueue(url, metadata.getString("version"))
        if (!preferences.edit().putLong("download-id", id).commit()) {
            downloads.remove(id)
            error("无法保存下载状态，请重试")
        }
        refreshLocked()
    }

    /** Only a local system query; called on return to the UI or download completion, never polled. */
    suspend fun refresh() = operation { refreshLocked() }

    private fun refreshLocked() {
        val id = preferences.getLong("download-id", -1)
        if (id <= 0) return
        val metadata = metadata()
        if (ReleaseVersion.parse(metadata.getString("version"))!! <= ReleaseVersion.parse(currentVersion)!!) {
            discardDownloadLocked()
            require(preferences.edit().remove("release").commit()) { "无法清理旧版本信息" }
            mutableState.value = UpdateState(UpdatePhase.IDLE, "当前版本 $currentVersion")
            return
        }
        if (preferences.getLong("rejected-download-id", -1) == id) {
            mutableState.value = UpdateState(UpdatePhase.ERROR,
                preferences.getString("verification-error", null) ?: "更新验证失败，请重新下载")
            return
        }
        val download = downloads.snapshot(id) ?: error("下载记录不存在，请重新检查更新")
        run {
            if (download.bytes > UpdatePolicy.MAX_APK_BYTES || download.total > UpdatePolicy.MAX_APK_BYTES) {
                discardDownloadLocked()
                error("下载文件超出允许大小，已停止下载")
            }
            when (download.status) {
                DownloadManager.STATUS_SUCCESSFUL -> mutableState.value = UpdateState(UpdatePhase.READY, "下载完成，安装前将核对摘要和签名")
                DownloadManager.STATUS_FAILED -> error("下载失败（系统原因 ${download.reason}），可重新下载")
                else -> {
                    val bytes = download.bytes
                    mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, "系统正在下载或等待网络，已下载 ${bytes / 1024} KB；可手动刷新")
                }
            }
        }
    }

    /** Always revalidate, including after returning from unknown-source settings. */
    suspend fun verifiedInstallIntent(): Intent = withContext(Dispatchers.IO) {
        mutex.withLock {
            var verifyingId = -1L
            try {
                val metadata = metadata()
                val id = preferences.getLong("download-id", -1)
                require(id > 0) { "请先下载更新" }
                refreshLocked()
                require(mutableState.value.phase == UpdatePhase.READY) { "下载尚未完成" }
                verifyingId = id
                require(directory.isDirectory || directory.mkdirs()) { "无法创建更新验证目录" }
                val staging = File(directory, "verifying-$id.part")
                val apkFile = File(directory, "verified-$id.apk")
                try {
                downloads.open(id).use { input ->
                    UpdateFileVerifier.copyVerified(input, staging, metadata.getLong("size"), metadata.getString("sha"))
                }
                val candidate = context.packageManager.getPackageArchiveInfo(staging.path,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                    ?: error("无法验证 APK")
                val installed = installedPackage()
                UpdatePolicy.verifyIdentity(context.packageName, candidate.packageName,
                    installed.longVersionCode, candidate.longVersionCode, signers(installed), signers(candidate))
                require(candidate.versionName == metadata.getString("version")) { "APK 版本名与 Release 不一致" }
                java.nio.file.Files.move(staging.toPath(), apkFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apkFile)
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = android.content.ClipData.newRawUri("OMN update", uri) }
                } finally {
                    // An installer may still be reading the previous verified file. Only remove
                    // this attempt's private staging copy; publish the verified APK atomically.
                    if (staging.exists()) staging.delete()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val message = failure.message ?: "更新验证失败"
                if (verifyingId > 0) {
                    // DownloadManager SUCCESSFUL means transfer completed, not APK verified.
                    // Persist rejection so onResume/new Activity cannot revive this file as READY.
                    val saved = preferences.edit().putLong("rejected-download-id", verifyingId)
                        .putString("verification-error", message).commit()
                    if (!saved) {
                        // A failed preference write must not leave a rejected transfer reusable.
                        downloads.remove(verifyingId)
                    }
                }
                mutableState.value = UpdateState(UpdatePhase.ERROR, message)
                throw failure
            }
        }
    }

    private fun installedPackage(): PackageInfo = context.packageManager.getPackageInfo(context.packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))

    private fun signers(info: PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners
        ?.mapTo(hashSetOf()) { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.orEmpty()

    private fun metadata() = JSONObject(preferences.getString("release", null) ?: error("请先检查更新"))

    /** Only our recorded download and private verification copy; never shared user files. */
    private fun discardDownloadLocked() {
        val id = preferences.getLong("download-id", -1)
        if (id > 0) {
            downloads.remove(id)
            val verified = File(directory, "verified-$id.apk")
            if (verified.exists()) require(verified.delete()) { "无法清理旧更新文件，请稍后重试" }
        }
        require(preferences.edit().remove("download-id").remove("rejected-download-id")
            .remove("verification-error").commit()) { "无法重置下载状态" }
    }

    private fun cleanObsoleteVerificationFiles() {
        val active = "verified-${preferences.getLong("download-id", -1)}.apk"
        directory.listFiles()?.filter { it.name.matches(Regex("verified-[0-9]+\\.apk|verifying-[0-9]+\\.part")) && it.name != active }
            ?.forEach { file ->
                require(file.canonicalFile.parentFile == directory.canonicalFile) { "更新缓存路径异常" }
                require(file.isFile && file.delete()) { "无法清理旧更新文件" }
            }
    }

    private suspend fun operation(block: () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { block() } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = UpdateState(UpdatePhase.ERROR, failure.message ?: "GitHub 不可用，请稍后重试或使用浏览器")
            }
        }
    }

}

// Activity recreation must not let two updater instances mutate the same download/preferences.
private val updateOperationMutex = Mutex()

    private fun getGithubText(initialUrl: String, limit: Int): String {
        var url = URL(initialUrl)
        repeat(6) {
            require(url.protocol == "https" && (url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))) { "拒绝非 GitHub 下载地址" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "OhMyNotification-Android")
                setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "text/plain")
            }
            try {
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("下载重定向无效"))
                } else {
                    require(code == 200) { "GitHub 请求失败（HTTP $code），可用浏览器打开 Releases" }
                    return connection.inputStream.use { input ->
                        val bytes = input.readNBytes(limit + 1)
                        require(bytes.size <= limit) { "更新响应过大" }
                        String(bytes, Charsets.UTF_8)
                    }
                }
            } finally { connection.disconnect() }
        }
        error("下载重定向次数过多")
    }
private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
