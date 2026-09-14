package io.github.prince_dulb.ohmynotification.data

import android.app.Notification
import android.app.Person
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.prince_dulb.ohmynotification.capture.NotificationSnapshotFactory
import io.github.prince_dulb.ohmynotification.capture.SnapshotResult
import io.github.prince_dulb.ohmynotification.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapshotBoundaryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val factory = NotificationSnapshotFactory(context.packageName, "synthetic-session")
    private val policy = MonitoringPolicySnapshot(setOf(context.packageName), emptySet(), NotificationType.entries.toSet(), 0)
    private fun snapshot(notification: Notification, kind: ObservedCallbackKind = ObservedCallbackKind.POST_OR_UPDATE,
        sourcePolicy: MonitoringPolicySnapshot = policy): SnapshotResult = factory.capture(
        StatusBarNotification("example.synthetic", "example.synthetic", 10, null, Process.myUid(), 0, 0,
            notification, Process.myUserHandle(), 100), kind, "connection", sourcePolicy)

    @Test fun standardInboxAndMessagingStylesPreserveTheirVisibleLines() {
        val inbox = Notification.Builder(context, "synthetic")
            .setStyle(Notification.InboxStyle().addLine("first line").addLine("second line")).build()
        assertEquals("first line\nsecond line", (snapshot(inbox) as SnapshotResult.Captured).observation.content.bigText)
        val messaging = Notification.Builder(context, "synthetic").setStyle(
            Notification.MessagingStyle(Person.Builder().setName("Me").build())
                .addMessage("first message", 1, Person.Builder().setName("Sender").build())
                .addMessage("second message", 2, Person.Builder().setName("Sender").build())).build()
        assertEquals("Sender: first message\nSender: second message",
            (snapshot(messaging) as SnapshotResult.Captured).observation.content.bigText)
    }

    @Test fun oversizedTextIsBoundedBeforeCopyAndReportsItsLimit() {
        val notification = Notification.Builder(context, "synthetic").build()
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "x".repeat(50_000))
        val captured = snapshot(notification) as SnapshotResult.Captured
        assertEquals(32_768, captured.observation.content.bigText!!.length)
        assertTrue(SnapshotCopyWarning.CAPTURE_TEXT_TRUNCATED in captured.observation.copyWarnings)
    }

    @Test fun excludedSourceRemovalKeepsIdentityButNeverCopiesItsNewText() {
        val notification = Notification.Builder(context, "synthetic").setContentText("not to be captured").build()
        val excluded = policy.copy(userExcludedPackages = setOf("example.synthetic"))
        assertTrue(snapshot(notification, sourcePolicy = excluded) is SnapshotResult.Excluded)
        val removal = snapshot(notification, ObservedCallbackKind.REMOVED, excluded) as SnapshotResult.Captured
        assertNull(removal.observation.content.text)
        assertNull(removal.runtimeAction)
        assertEquals("example.synthetic", removal.observation.identity.sourcePackage)
    }
}
