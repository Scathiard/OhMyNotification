package io.github.prince_dulb.ohmynotification.data

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.service.notification.NotificationListenerService
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.prince_dulb.ohmynotification.MainActivity
import io.github.prince_dulb.ohmynotification.capture.OmnNotificationListenerService
import io.github.prince_dulb.ohmynotification.testsource.SyntheticCarrierService as Protocol
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Properties
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Explicit three-UID experiment. Never scans or sends real notification capabilities. */
@RunWith(AndroidJUnit4::class)
class IndependentCarrierUpgradeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val holder get() = "${context.packageName}.test"

    @Test fun explicitPhase() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator-only experiment" }
        check(context.packageName == Protocol.TARGET)
        val phase = requireNotNull(InstrumentationRegistry.getArguments().getString("independentCarrierPhase"))
        withHolder { remote ->
            val result = when (phase) {
                "publish" -> publish(remote)
                "recover" -> recover(remote)
                "reboot-publish" -> {
                    val published = publish(remote)
                    check(context.getSharedPreferences("synthetic_reboot_probe", Context.MODE_PRIVATE).edit()
                        .putInt("boot", bootCount()).putLong("previousReceipt", receiptTime(receipt()))
                        .putLong("sourceUpdated", packageInfo(Protocol.SOURCE).lastUpdateTime).commit())
                    published
                }
                "reboot-recover" -> recoverAfterReboot(remote)
                else -> error("Specify publish or recover")
            }
            instrumentation.sendStatus(0, result)
        }
    }

    private fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)

    private fun recoverAfterReboot(remote: IBinder): Bundle {
        val baseline = context.getSharedPreferences("synthetic_reboot_probe", Context.MODE_PRIVATE)
        assertTrue("Missing pre-reboot baseline", baseline.contains("boot"))
        assertTrue("A complete system reboot is required", bootCount() > baseline.getInt("boot", -1))
        assertEquals(baseline.getLong("sourceUpdated", -1), packageInfo(Protocol.SOURCE).lastUpdateTime)
        assertTrue("Source must not repost after reboot", sources().isEmpty())
        val stored = try { call(remote, Protocol.GET) } catch (failure: RuntimeException) {
            fail("REBOOT_TARGET_UNAVAILABLE: system carrier lost; ${failure.javaClass.simpleName}")
            error("unreachable")
        }
        val action = requireNotNull(stored.getParcelable("handle", PendingIntent::class.java))
        assertEquals(Protocol.SOURCE, action.creatorPackage)
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val sentAt = System.currentTimeMillis()
        action.send(ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        }.toBundle())
        var landed = Properties()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            landed = receipt()
            if (receiptTime(landed) >= sentAt) break
            Thread.sleep(100)
        }
        assertEquals("OPENED", landed.getProperty("status"))
        assertEquals("action", landed.getProperty("case_id"))
        assertEquals("OMN_SYNTHETIC_TARGET_001", landed.getProperty("target_token"))
        assertTrue(receiptTime(landed) >= sentAt && receiptTime(landed) > baseline.getLong("previousReceipt", -1))
        return Bundle().apply { putBoolean("rebootPreciseTargetVerified", true) }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(name: String) = context.packageManager.getPackageInfo(name, 0)

    private fun publish(remote: IBinder): Bundle {
        val source = sources().single().notification
        check(source.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ==
            "OMN_TEST_ONLY_NOTIFICATION · OMN_SYNTHETIC_ACTION · publish")
        val action = requireNotNull(source.contentIntent)
        assertEquals(Protocol.SOURCE, action.creatorPackage)
        @Suppress("DEPRECATION")
        val target = context.packageManager.getActivityInfo(ComponentName(Protocol.SOURCE,
            "io.github.prince_dulb.ohmynotification.testsource.ControlledTargetJavaActivity"), 0)
        assertFalse(target.exported)
        val metadata = Bundle().apply {
            putString("nonce", UUID.randomUUID().toString())
            putParcelable("handle", action)
            putInt("mainPid", Process.myPid())
            putLong("mainVersion", packageInfo(context.packageName).longVersionCode)
            putLong("mainUpdated", packageInfo(context.packageName).lastUpdateTime)
            putLong("holderUpdated", packageInfo(holder).lastUpdateTime)
            putLong("sourceUpdated", packageInfo(Protocol.SOURCE).lastUpdateTime)
            putLong("previousReceipt", receiptTime(receipt()))
        }
        val confirmed = call(remote, Protocol.PUT, metadata)
        val identities = setOf(confirmed.getInt("holderUid"), confirmed.getInt("callerUid"), action.creatorUid)
        assertEquals("Source, recorder and holder must be distinct UIDs", 3, identities.size)
        assertEquals(action, confirmed.getParcelable("handle", PendingIntent::class.java))
        return Bundle().apply {
            putBoolean("threeDistinctUidsVerified", true)
            putBoolean("systemReadbackConfirmed", true)
            putInt("mainPid", Process.myPid())
            putInt("holderPid", confirmed.getInt("holderPid"))
            putLong("mainVersion", metadata.getLong("mainVersion"))
        }
    }

    private fun recover(remote: IBinder): Bundle {
        val stored = call(remote, Protocol.GET)
        assertEquals(packageInfo(holder).lastUpdateTime, stored.getLong("holderUpdated"))
        assertEquals(packageInfo(Protocol.SOURCE).lastUpdateTime, stored.getLong("sourceUpdated"))
        val version = packageInfo(context.packageName).longVersionCode
        assertTrue("Main APK must really be upgraded", version > stored.getLong("mainVersion"))
        assertTrue(packageInfo(context.packageName).lastUpdateTime > stored.getLong("mainUpdated"))
        assertNotEquals(stored.getInt("mainPid"), Process.myPid())
        assertNotEquals("Restart the unbound holder process before recovery",
            stored.getInt("publishedHolderPid"), stored.getInt("holderPid"))
        assertTrue("Original source notification must be removed", sources().isEmpty())
        val action = requireNotNull(stored.getParcelable("handle", PendingIntent::class.java))
        assertEquals(Protocol.SOURCE, action.creatorPackage)
        assertEquals(3, setOf(action.creatorUid, Process.myUid(), stored.getInt("holderUid")).size)
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val sentAt = System.currentTimeMillis()
        action.send(ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        }.toBundle())
        var landed = Properties()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            landed = receipt()
            if (receiptTime(landed) >= sentAt) break
            Thread.sleep(100)
        }
        assertEquals("OPENED", landed.getProperty("status"))
        assertEquals("action", landed.getProperty("case_id"))
        assertEquals("OMN_SYNTHETIC_TARGET_001", landed.getProperty("target_token"))
        assertTrue(receiptTime(landed) >= sentAt && receiptTime(landed) > stored.getLong("previousReceipt"))
        call(remote, Protocol.CLEAR, Bundle().apply { putString("nonce", stored.getString("nonce")) })
        return Bundle().apply {
            putBoolean("independentUpgradeVerified", true)
            putBoolean("threeDistinctUidsVerified", true)
            putBoolean("holderProcessRestarted", true)
            putBoolean("sourceNotificationRemoved", true)
            putLong("versionBefore", stored.getLong("mainVersion"))
            putLong("versionAfter", version)
            putLong("openedAtEpochMillis", receiptTime(landed))
            putBoolean("rebootVerified", false)
        }
    }

    private fun withHolder(block: (IBinder) -> Unit) {
        val queue = LinkedBlockingQueue<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { queue.offer(service) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        check(context.bindService(Intent().setComponent(ComponentName(holder, Protocol::class.java.name)),
            connection, Context.BIND_AUTO_CREATE))
        try { block(requireNotNull(queue.poll(5, TimeUnit.SECONDS)) { "Holder bind timed out" }) }
        finally { context.unbindService(connection) }
    }

    private fun call(remote: IBinder, operation: Int, request: Bundle = Bundle()): Bundle {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(Protocol.DESCRIPTOR)
            data.writeTypedObject(request, 0)
            check(remote.transact(operation, data, reply, 0))
            reply.readException()
            requireNotNull(reply.readTypedObject(Bundle.CREATOR))
        } finally { data.recycle(); reply.recycle() }
    }

    private fun sources(): List<android.service.notification.StatusBarNotification> {
        // Reflection only into this app's own listener singleton, not a hidden platform API.
        val field = OmnNotificationListenerService::class.java.getDeclaredField("activeService").apply { isAccessible = true }
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val listener = (field.get(null) as java.lang.ref.WeakReference<*>).get() as? NotificationListenerService
            if (listener != null) return listener.activeNotifications.filter {
                it.packageName == Protocol.SOURCE && it.tag == "OMN_TEST_ONLY_NOTIFICATION"
            }
            Thread.sleep(100)
        }
        error("Listener unavailable")
    }

    private fun receipt(): Properties {
        val fd = instrumentation.uiAutomation.executeShellCommand(
            "run-as ${Protocol.SOURCE} cat files/controlled-target-result.properties")
        return Properties().apply { ParcelFileDescriptor.AutoCloseInputStream(fd).use { load(it) } }
    }

    private fun receiptTime(receipt: Properties): Long = receipt.getProperty("opened_at_epoch_millis")?.toLong() ?: 0L
}
