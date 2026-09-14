package io.github.prince_dulb.ohmynotification.data

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.prince_dulb.ohmynotification.MainActivity
import io.github.prince_dulb.ohmynotification.capture.OmnNotificationListenerService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Authorized non-SDK experiment ONLY. Never launches or sends a real source's action. */
@RunWith(AndroidJUnit4::class)
class HiddenIntentRouteProbeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    // One approved non-SDK entry. No hidden-API exemptions, fallback or bypass libraries.
    private fun extract(action: PendingIntent): Intent? =
        PendingIntent::class.java.getMethod("getIntent").invoke(action) as? Intent

    @Test fun syntheticPublicRouteSurvivesReboot() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val source = "io.github.prince_dulb.ohmynotification.sourcefixture"
        val phase = arguments.getString("durableRoutePhase") ?: error("Specify capture or replay")
        val prefs = context.getSharedPreferences("synthetic_durable_route", android.content.Context.MODE_PRIVATE)
        val boot = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
        fun properties(file: String) = java.util.Properties().apply {
            val fd = instrumentation.uiAutomation.executeShellCommand("run-as $source cat files/$file")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { load(it) }
        }
        @Suppress("DEPRECATION")
        val sourceUpdated = context.packageManager.getPackageInfo(source, 0).lastUpdateTime
        if (phase == "capture") {
            val action = requireNotNull(listener().activeNotifications.single {
                it.packageName == source && it.tag == "OMN_TEST_ONLY_NOTIFICATION"
            }.notification.contentIntent)
            assertEquals(source, action.creatorPackage)
            assertNotEquals(Process.myUid(), action.creatorUid)
            val original: Intent
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.GET_INTENT_SENDER_INTENT")
            try { original = requireNotNull(extract(action)) }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            val token = properties("durable-route-source.properties").getProperty("target_token")
            assertTrue(token.startsWith("OMN_SYNTHETIC_REBOOT_"))
            assertEquals(token, original.getStringExtra("omn.synthetic.target_token"))
            assertEquals(source, original.component?.packageName)
            assertEquals("io.github.prince_dulb.ohmynotification.testsource.ControlledRebootTarget", original.component?.className)
            assertEquals(setOf("omn.synthetic.case_id", "omn.synthetic.target_token"), original.extras?.keySet())
            val encoded = original.toUri(Intent.URI_INTENT_SCHEME)
            val restored = Intent.parseUri(encoded, Intent.URI_INTENT_SCHEME)
            assertEquals(original.data, restored.data)
            assertEquals(original.component, restored.component)
            assertEquals("action", restored.getStringExtra("omn.synthetic.case_id"))
            assertEquals(token, restored.getStringExtra("omn.synthetic.target_token"))
            assertTrue(prefs.edit().putString("uri", encoded).putString("token", token)
                .putInt("boot", boot).putLong("sourceUpdated", sourceUpdated).commit())
            report(Bundle().apply { putBoolean("durableSourceExactSnapshotSaved", true) })
            return
        }
        check(phase == "replay")
        assertTrue("Complete reboot required", prefs.contains("boot") && boot > prefs.getInt("boot", -1))
        assertEquals(prefs.getLong("sourceUpdated", -1), sourceUpdated)
        assertTrue("Original notification must be absent", listener().activeNotifications.none {
            it.packageName == source && it.tag == "OMN_TEST_ONLY_NOTIFICATION"
        })
        assertEquals(prefs.getString("token", null), properties("durable-route-source.properties").getProperty("target_token"))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission("android.permission.GET_INTENT_SENDER_INTENT"))
        val restored = Intent.parseUri(requireNotNull(prefs.getString("uri", null)), Intent.URI_INTENT_SCHEME)
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val sentAt = System.currentTimeMillis()
        context.startActivity(restored.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var receipt = java.util.Properties()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            receipt = properties("controlled-target-result.properties")
            if ((receipt.getProperty("opened_at_epoch_millis")?.toLongOrNull() ?: 0L) >= sentAt) break
            Thread.sleep(100)
        }
        assertEquals("OPENED", receipt.getProperty("status"))
        assertEquals("action", receipt.getProperty("case_id"))
        assertEquals(prefs.getString("token", null), receipt.getProperty("target_token"))
        assertTrue(receipt.getProperty("opened_at_epoch_millis").toLong() >= sentAt)
        report(Bundle().apply {
            putBoolean("durablePublicTargetRebootVerified", true)
            putBoolean("replayWithoutShellPermission", true)
            putLong("openedAtEpochMillis", receipt.getProperty("opened_at_epoch_millis").toLong())
        })
    }

    @Test fun syntheticForeignIntentExtractionWithShellIdentity() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator-only experiment" }
        val sourcePackage = "io.github.prince_dulb.ohmynotification.sourcefixture"
        val sample = listener().activeNotifications.single {
            it.packageName == sourcePackage && it.tag == "OMN_TEST_ONLY_NOTIFICATION"
        }
        val action = requireNotNull(sample.notification.contentIntent)
        assertEquals(sourcePackage, action.creatorPackage)
        assertNotEquals(Process.myUid(), action.creatorUid)
        var ordinaryDenied = false
        try { extract(action) } catch (failure: Exception) {
            ordinaryDenied = generateSequence<Throwable>(failure) { it.cause }.any { it is SecurityException }
        }
        assertTrue("Ordinary identity negative control must be denied", ordinaryDenied)
        val original: Intent
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.GET_INTENT_SENDER_INTENT")
        try {
            original = requireNotNull(extract(action))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        assertEquals(sourcePackage, original.component?.packageName)
        assertEquals("action", original.getStringExtra("omn.synthetic.case_id"))
        assertEquals("OMN_SYNTHETIC_TARGET_001", original.getStringExtra("omn.synthetic.target_token"))
        val restored = Intent.parseUri(original.toUri(Intent.URI_INTENT_SCHEME), Intent.URI_INTENT_SCHEME)
        assertEquals(original.component, restored.component)
        assertEquals(original.getStringExtra("omn.synthetic.target_token"),
            restored.getStringExtra("omn.synthetic.target_token"))
        var privateTargetDenied = false
        try { context.startActivity(restored.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: SecurityException) { privateTargetDenied = true }
        assertTrue("Extracted private target must not inherit issuer execution identity", privateTargetDenied)
        report(Bundle().apply {
            putBoolean("ordinaryExtractionDenied", ordinaryDenied)
            putBoolean("shellExtractionExact", true)
            putBoolean("privateTargetReplayDenied", privateTargetDenied)
            putBoolean("rebootVerified", false)
        })
    }

    @Test fun syntheticIntentRoundTripReportsUnsupportedExtras() {
        assertEquals("Run against target SDK 36", 36, context.applicationInfo.targetSdkVersion)
        assertFalse("Use the installed non-debuggable candidate",
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val target = Intent(context, MainActivity::class.java)
            .setAction("${context.packageName}.ROUTE_PROBE_${UUID.randomUUID()}")
            .setData(Uri.parse("https://example.invalid/omn-synthetic-route"))
            .putExtra("syntheticString", "original")
            .putExtra("syntheticLong", 8_000_000_001L)
            .putExtra("syntheticBoolean", true)
            .putExtra("syntheticNested", Bundle().apply { putString("value", "nested") })
            .putExtra("syntheticArray", intArrayOf(1, 2, 3))
        val action = PendingIntent.getActivity(context, 9041, target, PendingIntent.FLAG_IMMUTABLE)
        try {
            val extracted = try { extract(action) } catch (failure: Exception) {
                report(failureDetails(failure))
                fail("Approved entry unavailable: ${failure.javaClass.simpleName}")
                return
            }
            assertNotNull("Approved entry returned no Intent", extracted)
            val encoded = extracted!!.toUri(Intent.URI_INTENT_SCHEME)
            val restored = Intent.parseUri(encoded, Intent.URI_INTENT_SCHEME)
            assertTrue("Primitive string did not round-trip", restored.getStringExtra("syntheticString") == "original")
            assertTrue("Primitive long did not round-trip", restored.getLongExtra("syntheticLong", 0) == 8_000_000_001L)
            assertTrue("Primitive boolean did not round-trip", restored.getBooleanExtra("syntheticBoolean", false))
            assertTrue("Route identity did not round-trip", restored.component == target.component && restored.data == target.data)
            // Intentionally prove that blindly saving toUri is NOT a lossless Intent snapshot.
            assertTrue("Nested Bundle not present before serialization", extracted.hasExtra("syntheticNested"))
            assertFalse("Review changed platform serialization behavior", restored.hasExtra("syntheticNested"))
            assertFalse("Review changed platform serialization behavior", restored.hasExtra("syntheticArray"))
            val savedSnapshot = encoded
            PendingIntent.getActivity(context, 9041, Intent(target).putExtra("syntheticString", "replacement"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            assertTrue("Independent string snapshot changed after issuer update",
                Intent.parseUri(savedSnapshot, Intent.URI_INTENT_SCHEME).getStringExtra("syntheticString") == "original")
            report(Bundle().apply {
                putBoolean("approvedNonSdkEntryWorks", true)
                putBoolean("primitiveRouteRoundTrip", true)
                putBoolean("nestedAndArrayExtrasLost", true)
                putBoolean("savedStringUnaffectedByIssuerUpdate", true)
            })
        } finally { action.cancel() }
    }

    @Test fun inspectOrSaveSourceCandidatesWithoutLaunching() {
        if (arguments.getString("probePhase") == "verify") return
        val source = arguments.getString("sourcePackage") ?: return
        val save = arguments.getString("probePhase") == "save"
        val snapshots = listener().activeNotifications.filter { it.packageName == source }.take(32)
        val result = Bundle().apply {
            putInt("sourceSampleCount", snapshots.size)
            listOf("withContentIntent", "extractedIntentCount", "lostExtraKeys", "changedPrimitiveExtraValues",
                "scalarExtrasWithRouteText", "scalarExtrasWithBvId", "exportedActivityTargets",
                "nonExportedActivityTargets", "unresolvedActivityTargets", "extractionOrInspectionFailures")
                .forEach { putInt(it, 0) }
        }
        val candidates = JSONArray()
        for (snapshot in snapshots) {
            val pending = snapshot.notification.contentIntent ?: continue
            result.increment("withContentIntent")
            if (pending.isActivity) result.increment("activityPendingIntentCount")
            try {
                val original = extract(pending) ?: continue
                result.increment("extractedIntentCount")
                val uri = original.toUri(Intent.URI_INTENT_SCHEME)
                val restored = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                val originalExtras = original.extras
                val restoredExtras = restored.extras
                val keys = originalExtras?.keySet().orEmpty()
                val missing = keys - restoredExtras?.keySet().orEmpty()
                for (key in keys) {
                    val value = originalExtras?.get(key)
                    if (value is String || value is Number || value is Boolean || value is Char) {
                        if (value != restoredExtras?.get(key)) result.increment("changedPrimitiveExtraValues")
                    }
                    if (value is String) {
                        val bounded = value.take(65_536)
                        if (Regex("(?i)https?://|bilibili://").containsMatchIn(bounded)) result.increment("scalarExtrasWithRouteText")
                        if (Regex("\\bBV[0-9A-Za-z]{10}\\b").containsMatchIn(bounded)) result.increment("scalarExtrasWithBvId")
                    }
                }
                result.putInt("totalExtraKeys", result.getInt("totalExtraKeys") + keys.size)
                result.putInt("lostExtraKeys", result.getInt("lostExtraKeys") + missing.size)
                if (missing.isNotEmpty()) result.increment("candidatesWithLostExtras")
                if (original.clipData != null) result.increment("candidatesWithClipData")
                if (original.data != null) result.increment("candidatesWithDataUri")
                if (original.component?.packageName == source) result.increment("componentMatchesSource")
                if (original.component != restored.component || original.data != restored.data ||
                    original.action != restored.action) result.increment("routeIdentityChanged")
                val component = restored.component
                if (component != null) {
                    try {
                        val info = context.packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
                        if (info.exported) result.increment("exportedActivityTargets")
                        else result.increment("nonExportedActivityTargets")
                        if (!info.permission.isNullOrEmpty()) result.increment("permissionProtectedTargets")
                    } catch (_: PackageManager.NameNotFoundException) {
                        result.increment("unresolvedActivityTargets")
                    }
                }
                // Never print this string or any extra key/value. Persistence is explicit opt-in.
                if (save) candidates.put(JSONObject().put("intentUri", uri)
                    .put("postTime", snapshot.postTime).put("lostExtraCount", missing.size)
                    .put("isActivity", pending.isActivity))
            } catch (failure: Exception) {
                result.increment("extractionOrInspectionFailures")
                result.putString("failureClassOnly", failure.javaClass.simpleName)
                result.putAll(failureDetails(failure))
            }
        }
        if (save) {
            require(!savedFile().exists()) { "Probe file already exists; refusing to overwrite" }
            require(candidates.length() > 0) { "No candidates to save" }
            val saved = JSONObject().put("schema", 1).put("capturedProcess", Process.myPid())
                .put("capturedAt", System.currentTimeMillis()).put("sourcePackage", source)
                .put("candidates", candidates)
            context.openFileOutput(PROBE_FILE, android.content.Context.MODE_PRIVATE).use {
                it.write(saved.toString().toByteArray(Charsets.UTF_8))
            }
            result.putInt("privatelySavedCandidates", candidates.length())
        }
        report(result)
    }

    @Test fun optionalReadSavedCandidatesInNewProcessWithoutLaunching() {
        if (arguments.getString("probePhase") != "verify") return
        val saved = JSONObject(savedFile().readText())
        assertTrue("Run verification in a new process", saved.getInt("capturedProcess") != Process.myPid())
        val candidates = saved.getJSONArray("candidates")
        var decoded = 0
        for (index in 0 until candidates.length()) {
            try {
                Intent.parseUri(candidates.getJSONObject(index).getString("intentUri"), Intent.URI_INTENT_SCHEME)
                decoded++
            } catch (_: Exception) { /* Do not expose URI through exception text. */ }
        }
        assertTrue("One or more saved strings could not be read", decoded == candidates.length())
        report(Bundle().apply {
            putBoolean("readInDifferentProcess", true)
            putInt("decodedPrivateCandidates", decoded)
            putBoolean("realSourceLaunchAttempted", false)
        })
    }

    private fun listener(): OmnNotificationListenerService {
        // Reflection on our own field does not access another platform hidden API.
        val field = OmnNotificationListenerService::class.java.getDeclaredField("activeService")
            .apply { isAccessible = true }
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val service = (field.get(null) as java.lang.ref.WeakReference<*>).get() as? OmnNotificationListenerService
            if (service != null) return service
            Thread.sleep(100)
        }
        error("Listener did not connect for the source probe")
    }

    private fun savedFile() = File(context.filesDir, PROBE_FILE)
    private fun failureDetails(failure: Exception): Bundle {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
        return Bundle().apply {
            // Never emit exception messages: a source may include private Intent parameters.
            putString("exceptionClassChainOnly", causes.joinToString(" -> ") { it.javaClass.simpleName })
            putBoolean("containsSecurityException", causes.any { it is SecurityException })
            putBoolean("mentionsIntentSenderPermission", causes.any {
                it.message?.contains("android.permission.GET_INTENT_SENDER_INTENT") == true
            })
            putBoolean("hasIntentSenderPermission", context.checkSelfPermission(
                "android.permission.GET_INTENT_SENDER_INTENT") == PackageManager.PERMISSION_GRANTED)
        }
    }
    private fun report(result: Bundle) = instrumentation.sendStatus(0, result)
    private fun Bundle.increment(key: String) = putInt(key, getInt(key) + 1)

    private companion object { const val PROBE_FILE = "omn-intent-route-probe.json" }
}
