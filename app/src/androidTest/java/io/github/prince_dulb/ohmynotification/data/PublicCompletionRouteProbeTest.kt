package io.github.prince_dulb.ohmynotification.data

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.prince_dulb.ohmynotification.capture.OmnNotificationListenerService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Emulator-only experiment; send is NOT a read-only API. Never accepts real source packages. */
@RunWith(AndroidJUnit4::class)
class PublicCompletionRouteProbeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val source = "io.github.prince_dulb.ohmynotification.sourcefixture"

    private fun guard() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission("android.permission.GET_INTENT_SENDER_INTENT"))
        assertNotEquals(2000, Process.myUid())
    }
    private fun sourceProperties(file: String) = Properties().apply {
        val fd = instrumentation.uiAutomation.executeShellCommand("run-as $source cat files/$file")
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { load(it) }
    }
    private fun listener(): OmnNotificationListenerService {
        val field = OmnNotificationListenerService::class.java.getDeclaredField("activeService").apply { isAccessible = true }
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val instance = (field.get(null) as java.lang.ref.WeakReference<*>).get() as? OmnNotificationListenerService
            if (instance != null) return instance
            Thread.sleep(100)
        }
        error("Synthetic listener unavailable")
    }
    @Test fun foreignCallbackReportsActualTargetWithoutExtractionPermission() {
        guard()
        val action = requireNotNull(listener().activeNotifications.single {
            it.packageName == source && it.tag == "OMN_TEST_ONLY_NOTIFICATION"
        }.notification.contentIntent)
        assertNotEquals(Process.myUid(), action.creatorUid)
        assertEquals(source, action.creatorPackage)
        val expected = sourceProperties("durable-route-source.properties").getProperty("target_token")
        assertTrue(expected.startsWith("OMN_SYNTHETIC_REBOOT_"))
        val previousReceipt = sourceProperties("controlled-target-result.properties").getProperty("opened_at_epoch_millis")
        val returned = AtomicReference<Intent>()
        val done = CountDownLatch(1)
        val options = ActivityOptions.makeBasic().apply {
            setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED)
        }
        // Only public sending API; no getIntent, shell permission adoption, wrapper or proxy.
        action.send(context, 0, null, { _, intent, _, _, _ -> returned.set(intent); done.countDown() },
            Handler(Looper.getMainLooper()), null, options.toBundle())
        assertTrue("No completion callback", done.await(5, TimeUnit.SECONDS))
        val intent = requireNotNull(returned.get())
        assertEquals(expected, intent.getStringExtra("omn.synthetic.target_token"))
        assertEquals(source, intent.component?.packageName)
        Thread.sleep(1_000)
        val newReceipt = sourceProperties("controlled-target-result.properties").getProperty("opened_at_epoch_millis")
        instrumentation.sendStatus(0, Bundle().apply {
            putBoolean("publicCompletionExactTarget", true)
            putBoolean("ordinaryExtractionPermissionDenied", true)
            putBoolean("newTargetLandingObserved", previousReceipt != newReceipt)
            putBoolean("safeForUnattendedRealNotifications", false)
        })
        assertEquals("Explicit sender BAL denial did not prevent target launch", previousReceipt, newReceipt)
    }
    @Test fun readOnlyMetadataDoesNotRevealForeignTargetExtras() {
        guard()
        val notification = listener().activeNotifications.single {
            it.packageName == source && it.tag == "OMN_TEST_ONLY_NOTIFICATION"
        }.notification
        val action = requireNotNull(notification.contentIntent)
        assertEquals(source, action.creatorPackage)
        assertNotEquals(Process.myUid(), action.creatorUid)
        val expected = sourceProperties("durable-route-source.properties").getProperty("target_token")
        assertTrue(expected.startsWith("OMN_SYNTHETIC_REBOOT_"))
        val before = sourceProperties("controlled-target-result.properties").getProperty("opened_at_epoch_millis")
        // Non-SDK read-only probes: no exemptions, shell identity or fallback transactions.
        val tag = PendingIntent::class.java.getMethod("getTag", String::class.java)
            .invoke(action, "OMN_SYNTHETIC_TAG:") as String
        assertEquals("OMN_SYNTHETIC_TAG:" + ComponentName(source,
            "io.github.prince_dulb.ohmynotification.testsource.ControlledRebootTarget").flattenToShortString(), tag)
        assertFalse(tag.contains(expected))
        assertFalse(notification.extras.containsKey("omn.synthetic.target_token"))
        var queryDenied = false
        try {
            PendingIntent::class.java.getMethod("queryIntentComponents", Int::class.javaPrimitiveType)
                .invoke(action, 0)
        } catch (failure: java.lang.reflect.InvocationTargetException) {
            queryDenied = failure.cause is SecurityException
        }
        assertTrue("Component query must report the platform permission boundary", queryDenied)
        assertEquals(before, sourceProperties("controlled-target-result.properties").getProperty("opened_at_epoch_millis"))
        instrumentation.sendStatus(0, Bundle().apply {
            putBoolean("readOnlyTagReturnsComponentNotTargetExtras", true)
            putBoolean("componentQueryPermissionDenied", queryDenied)
            putBoolean("readOnlyProbeNoTargetLanding", true)
        })
    }
    @Test fun oneShotSendConsumesCapabilityEvenWithMissingTarget() {
        guard()
        val intent = Intent().setComponent(ComponentName(context.packageName, context.packageName + ".NoSuchSyntheticTarget"))
            .setAction("OMN_SYNTHETIC_ONE_SHOT_" + UUID.randomUUID())
        val action = PendingIntent.getActivity(context, 9127, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        try { action.send() } catch (_: PendingIntent.CanceledException) { /* No target exists. */ }
        var consumed = false
        try { action.send() } catch (_: PendingIntent.CanceledException) { consumed = true }
        assertTrue("Expected one-shot capability consumption", consumed)
        instrumentation.sendStatus(0, Bundle().apply {
            putBoolean("oneShotConsumedWithoutLanding", consumed)
            putBoolean("automaticSendIsNotReadOnly", true)
        })
    }
}
