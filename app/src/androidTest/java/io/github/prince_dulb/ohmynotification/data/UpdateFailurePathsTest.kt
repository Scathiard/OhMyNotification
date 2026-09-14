package io.github.prince_dulb.ohmynotification.data

import android.app.DownloadManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.prince_dulb.ohmynotification.update.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real updater/preferences/files/package parser; fake transport never accesses the network. */
@RunWith(AndroidJUnit4::class)
class UpdateFailurePathsTest {
    @Test fun constructionAndResumeNeverContactGithub() = runBlocking {
        withHarness { h ->
            h.updater.refresh()
            assertEquals(0, h.networkCalls)
            h.releaseVersion = "0.0.0"
            h.updater.check()
            assertEquals(1, h.networkCalls)
            assertEquals(UpdatePhase.IDLE, h.updater.state.value.phase)
        }
    }

    @Test fun failedDownloadCannotKeepAnOldDownloadingStateAndBlockNewChecks() = runBlocking {
        withHarness { h ->
            h.updater.check()
            h.updater.download()
            assertEquals(UpdatePhase.DOWNLOADING, h.updater.state.value.phase)
            val old = h.backend.nextId
            h.backend.current = h.backend.current!!.copy(status = DownloadManager.STATUS_FAILED, reason = 1008)
            h.updater.check()
            assertEquals(listOf(old), h.backend.removed)
            assertEquals(4, h.networkCalls)
            assertEquals(UpdatePhase.AVAILABLE, h.updater.state.value.phase)
            assertEquals(-1L, h.preferences.getLong("download-id", -1))
        }
    }

    @Test fun missingDownloadAndOrphanCopiesAreRecoverableWithoutDeletingOtherFiles() = runBlocking {
        withHarness { h ->
            h.updater.check()
            h.updater.download()
            val active = File(h.directory, "verified-${h.backend.nextId}.apk").apply { writeText("synthetic active") }
            val orphan = File(h.directory, "verified-1.apk").apply { writeText("synthetic obsolete") }
            val partial = File(h.directory, "verifying-2.part").apply { writeText("synthetic partial") }
            val unrelated = File(h.directory, "keep.txt").apply { writeText("unrelated") }
            h.backend.current = null
            h.updater.check()
            assertFalse(active.exists())
            assertFalse(orphan.exists())
            assertFalse(partial.exists())
            assertTrue(unrelated.exists())
            assertEquals(UpdatePhase.AVAILABLE, h.updater.state.value.phase)
        }
    }

    @Test fun retryDiscardsOnlyOldOwnedDownloadAndEnqueuesOnce() = runBlocking {
        withHarness { h ->
            h.updater.check(); h.updater.download()
            val old = h.backend.nextId
            h.backend.current = h.backend.current!!.copy(status = DownloadManager.STATUS_FAILED)
            h.updater.refresh()
            assertEquals(UpdatePhase.ERROR, h.updater.state.value.phase)
            h.updater.download()
            assertEquals(listOf(old), h.backend.removed)
            assertEquals(old + 1, h.preferences.getLong("download-id", -1))
            assertEquals(2, h.backend.enqueues)
            assertEquals(UpdatePhase.DOWNLOADING, h.updater.state.value.phase)
        }
    }

    @Test fun wrongDigestInvalidApkWrongPackageAndOldVersionNeverProduceInstallIntent() = runBlocking {
        withHarness { h ->
            suspend fun reject(expected: String) {
                h.updater.check(); h.updater.download()
                h.backend.current = h.backend.current!!.copy(status = DownloadManager.STATUS_SUCCESSFUL)
                val failure = runCatching { h.updater.verifiedInstallIntent() }.exceptionOrNull()
                assertNotNull("Expected rejection: $expected", failure)
                assertTrue("Unexpected failure: ${failure!!.message}", failure.message.orEmpty().contains(expected))
                assertEquals(UpdatePhase.ERROR, h.updater.state.value.phase)
                assertTrue(h.directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
                h.backend.current = null // Explicitly simulate a discarded system download before the next check.
            }
            h.digest = "00".repeat(32)
            reject("SHA-256")
            h.digest = sha(ByteArrayInputStream(h.bytes))
            reject("无法验证 APK")
            h.useApk(File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir))
            reject("包名")
            h.useApk(File(h.context.applicationInfo.sourceDir))
            reject("版本号")
        }
    }

    @Test fun verificationFailureSurvivesRefreshAndNewInstanceUntilExplicitRetry() = runBlocking {
        withHarness { h ->
            h.digest = "00".repeat(32)
            h.updater.check(); h.updater.download()
            val rejectedId = h.backend.nextId
            h.backend.current = h.backend.current!!.copy(status = DownloadManager.STATUS_SUCCESSFUL)
            assertNotNull(runCatching { h.updater.verifiedInstallIntent() }.exceptionOrNull())
            val message = h.updater.state.value.message
            assertEquals(UpdatePhase.ERROR, h.updater.state.value.phase)
            h.updater.refresh()
            assertEquals(UpdatePhase.ERROR, h.updater.state.value.phase)
            assertEquals(message, h.updater.state.value.message)
            val recreated = AppUpdater(h.context, h.backend, h.preferences,
                { _, _ -> error("Local refresh must not contact network") }, h.directory)
            recreated.refresh()
            assertEquals(UpdatePhase.ERROR, recreated.state.value.phase)
            assertEquals(message, recreated.state.value.message)
            recreated.download()
            assertEquals(listOf(rejectedId), h.backend.removed)
            assertEquals(UpdatePhase.DOWNLOADING, recreated.state.value.phase)
            assertEquals(2, h.backend.enqueues)
            h.backend.current = h.backend.current!!.copy(status = DownloadManager.STATUS_SUCCESSFUL)
            recreated.refresh()
            assertEquals(UpdatePhase.READY, recreated.state.value.phase)
        }
    }

    @Test fun oversizedSystemDownloadIsRemovedOnTheNextLocalRefresh() = runBlocking {
        withHarness { h ->
            h.updater.check(); h.updater.download()
            h.backend.current = h.backend.current!!.copy(total = UpdatePolicy.MAX_APK_BYTES + 1)
            h.updater.refresh()
            assertEquals(UpdatePhase.ERROR, h.updater.state.value.phase)
            assertEquals(listOf(h.backend.nextId), h.backend.removed)
            assertEquals(-1L, h.preferences.getLong("download-id", -1))
        }
    }

    private suspend fun withHarness(block: suspend (Harness) -> Unit) {
        val h = Harness()
        try { block(h) } finally {
            // Dedicated synthetic preference/file names; never touch omn-updates or user records.
            h.context.deleteSharedPreferences(h.preferenceName)
            h.directory.listFiles().orEmpty().forEach { check(it.isFile && it.delete()) }
            check(h.directory.delete())
        }
    }

    private class Harness {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "synthetic-update-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val directory = File(context.cacheDir, preferenceName).apply { check(mkdir()) }
        val backend = FakeDownloads()
        var networkCalls = 0
        var releaseVersion = "999.0.0"
        val bytes = "synthetic non-apk".toByteArray()
        var size = bytes.size.toLong()
        var digest = sha(ByteArrayInputStream(bytes))
        init { backend.opener = { ByteArrayInputStream(bytes) } }
        fun useApk(file: File) {
            size = file.length(); digest = sha(file.inputStream()); backend.opener = file::inputStream
        }
        val updater = AppUpdater(context, backend, preferences, { url, _ ->
            networkCalls++
            val name = "OhMyNotification-$releaseVersion-release.apk"
            if (url == UpdatePolicy.API_URL) JSONObject().put("draft", false).put("prerelease", false)
                .put("tag_name", "v$releaseVersion").put("assets", JSONArray(listOf(name, "$name.sha256").map {
                    JSONObject().put("name", it).put("size", size)
                        .put("browser_download_url", "${UpdatePolicy.RELEASES_URL}/download/v$releaseVersion/$it")
                })).toString()
            else "$digest  $name"
        }, directory)
    }

    private class FakeDownloads : UpdateDownloads {
        var nextId = 41L
        var current: DownloadSnapshot? = null
        var enqueues = 0
        val removed = mutableListOf<Long>()
        lateinit var opener: () -> InputStream
        override fun enqueue(url: String, version: String): Long {
            enqueues++
            current = DownloadSnapshot(DownloadManager.STATUS_PENDING, 0, -1, 0)
            return ++nextId
        }
        override fun snapshot(id: Long): DownloadSnapshot? = current
        override fun open(id: Long): InputStream = opener()
        override fun remove(id: Long) { removed += id; current = null }
    }

    companion object {
        private fun sha(input: InputStream): String = input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32768)
            while (true) { val count = it.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
