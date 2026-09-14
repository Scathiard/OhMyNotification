package io.github.prince_dulb.ohmynotification.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.Properties
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Public-API, synthetic-only experiment. System memory is NOT durable disk storage. */
@RunWith(AndroidJUnit4::class)
class SystemCapabilityCarrierTest {
    @Test fun explicitPhase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val args = InstrumentationRegistry.getArguments()
        val phase = requireNotNull(args.getString("carrierPhase")) {
            "Specify publish, recover, foreign-publish or foreign-recover"
        }
        val result = when (phase) {
            "publish" -> publish(context, manager, args.getString("carrierCount", "64").toInt())
            "recover" -> recover(context, manager)
            "foreign-publish" -> publishForeign(context, manager)
            "foreign-recover" -> recoverForeign(context, manager)
            else -> error("Unsupported carrier phase")
        }
        instrumentation.sendStatus(0, result)
    }

    private fun requireSyntheticEmulator() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Foreign capability experiment is emulator-only"
        }
    }

    private fun syntheticSource(context: Context): String = "${context.packageName}.test"

    private fun activeSyntheticSources(context: Context): List<android.service.notification.StatusBarNotification> {
        // This is OMN's own field, not a hidden Android API. Only the fixed test UID is read.
        val field = io.github.prince_dulb.ohmynotification.capture.OmnNotificationListenerService::class.java
            .getDeclaredField("activeService").apply { isAccessible = true }
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val service = (field.get(null) as java.lang.ref.WeakReference<*>).get()
                as? android.service.notification.NotificationListenerService
            if (service != null) {
                return service.activeNotifications.filter {
                    it.packageName == syntheticSource(context) && it.tag == "OMN_TEST_ONLY_NOTIFICATION"
                }
            }
            Thread.sleep(100)
        }
        error("Listener unavailable for synthetic source")
    }

    private fun publishForeign(context: Context, manager: NotificationManager): Bundle {
        requireSyntheticEmulator()
        check(manager.areNotificationsEnabled())
        check(carrier(manager) == null) { "Do not overwrite a prior experiment" }
        val original = activeSyntheticSources(context).single().notification
        check(original.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ==
            "OMN_TEST_ONLY_NOTIFICATION · OMN_SYNTHETIC_ACTION · publish")
        val handle = requireNotNull(original.contentIntent)
        assertEquals(syntheticSource(context), handle.creatorPackage)
        assertNotEquals("Must be a genuinely foreign UID", Process.myUid(), handle.creatorUid)
        assertTrue(handle.isActivity)
        val target = ComponentName(syntheticSource(context),
            "io.github.prince_dulb.ohmynotification.testsource.ControlledTargetJavaActivity")
        @Suppress("DEPRECATION")
        assertFalse("Target must remain private", context.packageManager.getActivityInfo(target, 0).exported)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "OMN 合成凭证实验", NotificationManager.IMPORTANCE_MIN))
        manager.notify(TAG, ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("OMN 合成凭证实验")
            .setContentText("外来合成动作；不包含真实通知")
            .setVisibility(Notification.VISIBILITY_SECRET).setLocalOnly(true)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addExtras(Bundle().apply {
                putInt("carrierSchema", 2)
                putInt("createdInPid", Process.myPid())
                putInt("foreignCreatorUid", handle.creatorUid)
                putLong("previousReceipt", readSyntheticReceipt(context).getProperty("opened_at_epoch_millis")?.toLong() ?: 0L)
                putParcelableArrayList("syntheticHandles", arrayListOf(handle))
            }).build())
        val returned = awaitCarrier(manager).extras
            .getParcelableArrayList("syntheticHandles", PendingIntent::class.java)!!.single()
        assertEquals(handle, returned)
        return Bundle().apply {
            putBoolean("foreignUidVerified", true)
            putBoolean("privateTargetVerified", true)
            putInt("createdInPid", Process.myPid())
            putInt("foreignCreatorUid", handle.creatorUid)
        }
    }

    private fun recoverForeign(context: Context, manager: NotificationManager): Bundle {
        requireSyntheticEmulator()
        val extras = awaitCarrier(manager).extras
        assertEquals(2, extras.getInt("carrierSchema"))
        assertNotEquals(extras.getInt("createdInPid"), Process.myPid())
        assertTrue("Remove the source notification before recovery", activeSyntheticSources(context).isEmpty())
        // No getActivity/getBroadcast call here: recovery must use the system-held original.
        val handle = extras.getParcelableArrayList("syntheticHandles", PendingIntent::class.java)!!.single()
        assertEquals(syntheticSource(context), handle.creatorPackage)
        assertEquals(extras.getInt("foreignCreatorUid"), handle.creatorUid)
        assertNotEquals(Process.myUid(), handle.creatorUid)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.startActivitySync(Intent(context,
            io.github.prince_dulb.ohmynotification.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val sentAt = System.currentTimeMillis()
        handle.send(ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        }.toBundle())
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var receipt = Properties()
        while (SystemClock.elapsedRealtime() < deadline) {
            receipt = readSyntheticReceipt(context)
            if ((receipt.getProperty("opened_at_epoch_millis")?.toLong() ?: 0L) >= sentAt) break
            Thread.sleep(100)
        }
        assertEquals("OPENED", receipt.getProperty("status"))
        assertEquals("action", receipt.getProperty("case_id"))
        assertEquals("OMN_SYNTHETIC_TARGET_001", receipt.getProperty("target_token"))
        val openedAt = receipt.getProperty("opened_at_epoch_millis")?.toLong() ?: 0L
        assertTrue("Must be a fresh target receipt", openedAt >= sentAt && openedAt > extras.getLong("previousReceipt"))
        manager.cancel(TAG, ID) // Only the successful experiment's synthetic carrier.
        return Bundle().apply {
            putBoolean("foreignCrossProcessVerified", true)
            putBoolean("sourceNotificationRemoved", true)
            putBoolean("privateActivityOpened", true)
            putLong("openedAtEpochMillis", openedAt)
            putBoolean("upgradeVerified", false)
        }
    }

    private fun readSyntheticReceipt(context: Context): Properties {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "run-as ${syntheticSource(context)} cat files/controlled-target-result.properties")
        return Properties().apply {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream -> load(stream) }
        }
    }

    private fun publish(context: Context, manager: NotificationManager, count: Int): Bundle {
        require(count in 1..512) { "Bound the experiment to 512 synthetic capabilities" }
        check(manager.areNotificationsEnabled()) { "Existing notification permission is required" }
        check(carrier(manager) == null) { "A prior synthetic carrier exists; do not overwrite it" }
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "OMN 合成凭证实验", NotificationManager.IMPORTANCE_MIN))
        val nonce = UUID.randomUUID().toString()
        val action = "${context.packageName}.SYNTHETIC_CARRIER.$nonce"
        val handles = ArrayList<PendingIntent>(count)
        repeat(count) { index ->
            handles += PendingIntent.getBroadcast(context, index,
                Intent(action).setPackage(context.packageName).putExtra("syntheticIndex", index),
                PendingIntent.FLAG_IMMUTABLE)
        }
        val extras = Bundle().apply {
            putInt("carrierSchema", 1)
            putInt("createdInPid", Process.myPid())
            putInt("expectedCount", count)
            putString("syntheticAction", action)
            putParcelableArrayList("syntheticHandles", handles)
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("OMN 合成凭证实验")
            .setContentText("仅测试数据，等待下一进程读取；不包含真实通知")
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setLocalOnly(true).setOngoing(true).setOnlyAlertOnce(true)
            .addExtras(extras).build()
        val parcel = Parcel.obtain()
        val parcelBytes = try { notification.writeToParcel(parcel, 0); parcel.dataSize() }
            finally { parcel.recycle() }
        // Measure only. Never marshall a Binder-containing Parcel into a disk format.
        manager.notify(TAG, ID, notification)
        val restored = awaitCarrier(manager)
        val returned = restored.extras.getParcelableArrayList("syntheticHandles", PendingIntent::class.java)
        assertEquals("System did not preserve the complete handle set", count, returned?.size)
        return Bundle().apply {
            putInt("publishedSyntheticCount", count)
            putInt("notificationParcelBytes", parcelBytes)
            putBoolean("readBackFromSystem", true)
            putBoolean("crossProcessVerified", false)
        }
    }

    private fun recover(context: Context, manager: NotificationManager): Bundle {
        val extras = awaitCarrier(manager).extras
        assertEquals(1, extras.getInt("carrierSchema"))
        assertNotEquals("Recover must run in a different process", extras.getInt("createdInPid"), Process.myPid())
        val expected = extras.getInt("expectedCount")
        require(expected in 1..512)
        val action = requireNotNull(extras.getString("syntheticAction"))
        require(action.startsWith("${context.packageName}.SYNTHETIC_CARRIER."))
        val handles = requireNotNull(extras.getParcelableArrayList("syntheticHandles", PendingIntent::class.java))
        assertEquals(expected, handles.size)
        check(handles.all { it.creatorPackage == context.packageName && it.isBroadcast })
        val delivered = LinkedBlockingQueue<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                delivered.offer(intent.getIntExtra("syntheticIndex", -1))
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            val received = HashSet<Int>()
            handles.forEach { pending ->
                pending.send()
                received += requireNotNull(delivered.poll(5, TimeUnit.SECONDS)) { "Synthetic delivery timed out" }
            }
            assertEquals("Wrong synthetic destination payloads", (0 until expected).toSet(), received)
            // Only this experiment's synthetic handles/notification are canceled, after success.
            handles.forEach { it.cancel() }
            manager.cancel(TAG, ID)
            return Bundle().apply {
                putBoolean("crossProcessVerified", true)
                putInt("exactSyntheticDeliveries", received.size)
                putBoolean("recreatedPendingIntentsDuringRecovery", false)
                putBoolean("upgradeVerified", false)
                putBoolean("rebootVerified", false)
            }
        } finally { context.unregisterReceiver(receiver) }
    }

    private fun carrier(manager: NotificationManager): Notification? =
        manager.activeNotifications.firstOrNull { it.id == ID && it.tag == TAG }?.notification

    private fun awaitCarrier(manager: NotificationManager): Notification {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            carrier(manager)?.let { return it }
            Thread.sleep(50)
        }
        error("Synthetic system carrier is absent")
    }

    private companion object {
        const val CHANNEL = "omn_synthetic_carrier"
        const val TAG = "omn.synthetic.carrier"
        const val ID = 0x0D4F
    }
}
