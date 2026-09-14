package io.github.prince_dulb.ohmynotification.data

import io.github.prince_dulb.ohmynotification.core.model.ActionCapabilitySet
import io.github.prince_dulb.ohmynotification.core.model.NotificationIdentity
import io.github.prince_dulb.ohmynotification.core.model.NotificationObservation
import io.github.prince_dulb.ohmynotification.core.model.ObservedCallbackKind
import io.github.prince_dulb.ohmynotification.core.model.RawVisibleContent
import io.github.prince_dulb.ohmynotification.core.model.SnapshotCopyWarning
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizedContent
import io.github.prince_dulb.ohmynotification.core.normalize.ContentFingerprinter
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizationLimits
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizationResult
import io.github.prince_dulb.ohmynotification.core.normalize.NotificationNormalizer
import org.junit.Assert.*
import org.junit.Test

/** Regression checks for the historical risk audit. */
class RiskAuditCharacterizationTest {
    @Test fun missedRemovalAcrossProcessBoundaryPreservesOldLifecycle() {
        val previous = NotificationItemMerger.merge(null, observation("old", 1), content("original"))
            .item.copy(itemId = 42)
        val recovered = NotificationItemMerger.merge(previous,
            observation("new", 100).copy(callbackKind = ObservedCallbackKind.RECOVERY_SNAPSHOT),
            content("different notification"))
        assertTrue(recovered.isNewItem)
        assertEquals(0L, recovered.item.itemId)
        assertEquals("different notification", recovered.item.title)
        assertEquals("original", previous.title)
        assertEquals(100L, recovered.item.sortTimeEpochMillis)
        assertEquals(1, recovered.item.lifecycleGeneration)
        assertTrue(recovered.item.identityUncertain)
    }

    @Test fun failedTextCopyPreservesPreviouslySavedText() {
        val previous = NotificationItemMerger.merge(null, observation("old", 1), content("saved text"))
            .item.copy(itemId = 42)
        val failedCopy = observation("old", 2).copy(copyWarnings = setOf(SnapshotCopyWarning.TITLE_COPY_FAILED))
        val normalizer = NotificationNormalizer(NormalizationLimits(), ContentFingerprinter { _, _, _ -> "synthetic" })
        val normalized = normalizer.normalize(failedCopy, "Synthetic") as NormalizationResult.Normalized
        val replacement = NotificationItemMerger.merge(previous, failedCopy, normalized.content).item
        assertEquals("saved text", replacement.title)
        assertFalse(NotificationItemMerger.hasSameCapturedState(previous, replacement))
    }

    @Test fun archiveSnapshotQueryIncludesClickTimeBoundary() {
        val query = buildPageItemsQuery(InboxViewFilter(), NotificationActionView.UNAVAILABLE_ARCHIVE,
            emptySet(), "itemId, sourcePackage, sourceUserRef", throughItemId = 42)
        assertEquals("SELECT itemId, sourcePackage, sourceUserRef FROM notification_items WHERE " +
            "(1 OR itemId IN (SELECT itemId FROM manual_archives)) AND itemId <= 42 ORDER BY sortTimeEpochMillis DESC, itemId DESC", query.sql)
        assertEquals(0, query.argCount)
    }

    private fun observation(runtime: String, time: Long) = NotificationObservation(
        runtimeSessionId = runtime, listenerConnectionId = "connection-$runtime",
        callbackKind = ObservedCallbackKind.POST_OR_UPDATE,
        identity = NotificationIdentity("example.audit", "personal", "reused-key", 1, null),
        postTimeEpochMillis = time, observedAtEpochMillis = time, observedElapsedNanos = time,
        content = RawVisibleContent(null, null, null, null, null, null, null, null, null),
        copyWarnings = emptySet(), actionCapabilities = ActionCapabilitySet(true, "example.audit", 0, false),
        policyRevision = 0, removalReason = null,
    )

    private fun content(title: String?) = NormalizedContent(title, null, "Synthetic", null,
        title, emptySet(), 1, title?.length ?: 0, 0)
}
