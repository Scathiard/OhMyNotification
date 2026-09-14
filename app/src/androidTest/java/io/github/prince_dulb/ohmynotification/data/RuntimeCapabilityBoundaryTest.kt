package io.github.prince_dulb.ohmynotification.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Platform boundary experiments; passing these does NOT prove durable notification navigation. */
@RunWith(AndroidJUnit4::class)
class RuntimeCapabilityBoundaryTest {
    @Test fun optionalActiveSourceFieldAuditReportsOnlyCounts() {
        val source = InstrumentationRegistry.getArguments().getString("sourcePackage") ?: return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Reflection is limited to OMN's own private field, NOT a hidden Android API. This
        // diagnostic remains in the test APK; no notification text or URI is exported.
        val field = io.github.prince_dulb.ohmynotification.capture.OmnNotificationListenerService::class.java
            .getDeclaredField("activeService").apply { isAccessible = true }
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        var service: android.service.notification.NotificationListenerService? = null
        while (service == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            service = (field.get(null) as java.lang.ref.WeakReference<*>).get() as? android.service.notification.NotificationListenerService
            if (service == null) Thread.sleep(100)
        }
        assertNotNull("Listener did not connect for the optional audit", service)
        val samples = service!!.activeNotifications.filter { it.packageName == source }
        var withSpan = 0
        var withExplicitUrl = 0
        samples.forEach { sample ->
            val extras = sample.notification.extras
            val texts = listOf(android.app.Notification.EXTRA_TITLE, android.app.Notification.EXTRA_TEXT,
                android.app.Notification.EXTRA_BIG_TEXT, android.app.Notification.EXTRA_SUB_TEXT,
                android.app.Notification.EXTRA_SUMMARY_TEXT).mapNotNull { extras.getCharSequence(it) } +
                extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES).orEmpty()
            if (texts.any { it is android.text.Spanned && it.getSpans(0, it.length, android.text.style.URLSpan::class.java).isNotEmpty() }) withSpan++
            if (texts.any { Regex("(?i)https?://|bilibili://|\\bBV[0-9A-Za-z]{10}\\b").containsMatchIn(it) }) withExplicitUrl++
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putInt("auditActiveSampleCount", samples.size)
            putInt("auditContentIntentCount", samples.count { it.notification.contentIntent != null })
            putInt("auditUrlSpanCount", withSpan)
            putInt("auditExplicitTargetTextCount", withExplicitUrl)
        })
    }

    @Test fun immutableDoesNotFreezeTheCreatorsUpdatedTarget() = withReceiver { context, action, delivered ->
        val original = PendingIntent.getBroadcast(context, 801,
            Intent(action).setPackage(context.packageName).putExtra("target", "original"), PendingIntent.FLAG_IMMUTABLE)
        try {
            original.send()
            assertEquals("original", delivered.poll(5, TimeUnit.SECONDS))
            val replacement = PendingIntent.getBroadcast(context, 801,
                Intent(action).setPackage(context.packageName).putExtra("target", "replacement"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            assertEquals(original, replacement)
            original.send()
            assertEquals("replacement", delivered.poll(5, TimeUnit.SECONDS))
        } finally { original.cancel() }
    }

    @Test fun canceledAndOneShotCapabilitiesCannotBeKeptAliveByHoldingReferences() = withReceiver { context, action, delivered ->
        val canceled = PendingIntent.getBroadcast(context, 802, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        canceled.cancel()
        assertThrows(PendingIntent.CanceledException::class.java) { canceled.send() }
        val oneShot = PendingIntent.getBroadcast(context, 803,
            Intent(action).setPackage(context.packageName).putExtra("target", "once"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        try {
            oneShot.send()
            assertEquals("once", delivered.poll(5, TimeUnit.SECONDS))
            assertThrows(PendingIntent.CanceledException::class.java) { oneShot.send() }
        } finally { oneShot.cancel() }
    }

    private fun withReceiver(block: (Context, String, LinkedBlockingQueue<String>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val action = "${context.packageName}.SYNTHETIC_CAPABILITY_${UUID.randomUUID()}"
        val delivered = LinkedBlockingQueue<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                delivered.offer(intent.getStringExtra("target") ?: "missing")
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try { block(context, action, delivered) } finally { context.unregisterReceiver(receiver) }
    }
}
