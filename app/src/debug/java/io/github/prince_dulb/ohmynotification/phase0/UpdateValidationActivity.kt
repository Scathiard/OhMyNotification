package io.github.prince_dulb.ohmynotification.phase0

import android.os.Build
import android.os.Bundle
import io.github.prince_dulb.ohmynotification.MainActivity
import io.github.prince_dulb.ohmynotification.update.AndroidUpdateDownloads
import io.github.prince_dulb.ohmynotification.update.AppUpdater
import io.github.prince_dulb.ohmynotification.update.UpdateDownloads
import io.github.prince_dulb.ohmynotification.update.UpdatePolicy
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Shell-only emulator harness. Production Activity UI and installer callbacks are unchanged. */
class UpdateValidationActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator-only update fixture" }
        super.onCreate(savedInstanceState)
    }

    override fun createUpdater(): AppUpdater {
        val realDownloads = AndroidUpdateDownloads(applicationContext)
        fun fixture(): JSONObject {
            val file = File(filesDir, "update-validation-fixture.json")
            require(file.length() in 1..8192) { "Missing bounded update fixture" }
            return JSONObject(file.readText())
        }
        val transport = object : UpdateDownloads by realDownloads {
            override fun enqueue(url: String, version: String): Long {
                val path = fixture().getString("download")
                require(path in setOf("candidate.apk", "baseline.apk", "missing.apk", "interrupted.apk", "slow.apk"))
                return realDownloads.enqueue("http://127.0.0.1:18765/$path", version)
            }
        }
        return AppUpdater(
            this,
            downloads = transport,
            preferences = getSharedPreferences("omn-update-validation", MODE_PRIVATE),
            directory = File(filesDir, "updates/validation"),
            readText = { url, limit ->
                val data = fixture()
                val version = data.getString("version")
                val name = "OhMyNotification-$version-release.apk"
                val prefix = "${UpdatePolicy.RELEASES_URL}/download/v$version/"
                val text = when (url) {
                    UpdatePolicy.API_URL -> JSONObject().put("draft", false).put("prerelease", false)
                        .put("tag_name", "v$version").put("assets", JSONArray()
                            .put(JSONObject().put("name", name).put("size", data.getLong("size"))
                                .put("browser_download_url", "$prefix$name"))
                            .put(JSONObject().put("name", "$name.sha256")
                                .put("browser_download_url", "$prefix$name.sha256"))).toString()
                    "$prefix$name.sha256" -> "${data.getString("sha")}  $name"
                    else -> error("Unexpected fixture metadata request")
                }
                require(text.toByteArray().size <= limit)
                text
            },
        )
    }
}
