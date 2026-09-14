package io.github.prince_dulb.ohmynotification.data

import android.app.PendingIntent
import android.content.Intent
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStore
import io.github.prince_dulb.ohmynotification.core.normalize.HmacSha256Fingerprinter
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizationLimits
import io.github.prince_dulb.ohmynotification.core.normalize.NotificationNormalizer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import io.github.prince_dulb.ohmynotification.capture.SnapshotResult
import io.github.prince_dulb.ohmynotification.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveOperationsTest {
    @Test fun summarySnapshotAndIncrementalCapturePublishInCommitOrder() = runBlocking {
        withTimeout(10_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database = Room.inMemoryDatabaseBuilder(context, OmnDatabase::class.java).build()
            val repo = NotificationRepository(database, NotificationNormalizer(NormalizationLimits(),
                HmacSha256Fingerprinter(ByteArray(32) { 1 })), RuntimeActionStore(), MonitoringPolicyStore(context.packageName))
            val releaseDatabase = CompletableDeferred<Unit>()
            try {
                repo.processCaptured(captured("original", "first", 1), "Synthetic")
                val databaseHeld = CompletableDeferred<Unit>()
                val blocker = launch { database.withTransaction { databaseHeld.complete(Unit); releaseDatabase.await() } }
                databaseHeld.await()
                val publications = mutableListOf<String>()
                val summary = async(start = CoroutineStart.UNDISPATCHED) {
                    repo.publishSummary { count, latest -> publications += "snapshot:$count:${latest?.title}" }
                }
                val capture = async(start = CoroutineStart.UNDISPATCHED) {
                    repo.processCaptured(captured("new", "second", 2), "Synthetic") { publications += "commit:${it.item.title}" }
                }
                assertFalse(summary.isCompleted)
                assertFalse(capture.isCompleted)
                releaseDatabase.complete(Unit)
                blocker.join(); summary.await(); capture.await()
                repo.publishSummary { count, latest -> publications += "snapshot:$count:${latest?.title}" }
                assertEquals(listOf("snapshot:1:first", "commit:second", "snapshot:2:second"), publications)
            } finally { releaseDatabase.complete(Unit); database.close() }
        }
    }

    @Test fun actionRecoveryCannotInterleaveBetweenDeleteEligibilityAndCommit() = runBlocking {
        withTimeout(10_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database = Room.inMemoryDatabaseBuilder(context, OmnDatabase::class.java).build()
            val actions = RuntimeActionStore()
            val repo = NotificationRepository(database, NotificationNormalizer(NormalizationLimits(),
                HmacSha256Fingerprinter(ByteArray(32) { 1 })), actions, MonitoringPolicyStore(context.packageName))
            val releaseDatabase = CompletableDeferred<Unit>()
            try {
                val item = repo.processCaptured(captured("race-key", "original", 1), "Synthetic")!!.item
                val databaseHeld = CompletableDeferred<Unit>()
                val blocker = launch {
                    database.withTransaction { databaseHeld.complete(Unit); releaseDatabase.await() }
                }
                databaseHeld.await()
                // UNDISPATCHED enters the repository mutex before Room has a free transaction.
                val recovery = async(start = CoroutineStart.UNDISPATCHED) {
                    repo.processCaptured(captured("race-key", "original", 1).copy(runtimeAction = testAction()), "Synthetic")
                }
                val deletion = async(start = CoroutineStart.UNDISPATCHED) { repo.deleteArchivedItems(setOf(item.itemId)) }
                assertFalse(recovery.isCompleted)
                assertFalse(deletion.isCompleted)
                releaseDatabase.complete(Unit)
                blocker.join()
                recovery.await()
                assertEquals(0, deletion.await())
                assertTrue(actions.contains(item.itemId))
                assertEquals(1L, database.omnDao().itemCount())
            } finally { releaseDatabase.complete(Unit); database.close() }
        }
    }

    @Test fun contentChangesPreservePriorTextAndSelectionExcludesPostClickArrivals() = runBlocking {
        withRepository { repo, _, _ ->
            val first = repo.processCaptured(captured("first-key", "original", 1), "Synthetic")!!.item
            val boundary = repo.selectionBoundary()
            repo.processCaptured(captured("later-key", "later", 2), "Synthetic")
            val selected = repo.selectionSnapshot(InboxViewFilter(), NotificationActionView.UNAVAILABLE_ARCHIVE,
                emptySet(), throughItemId = boundary)
            assertEquals(listOf(first.itemId), selected.map { it.itemId })
            val updated = repo.processCaptured(captured("first-key", "updated", 3), "Synthetic")!!.item
            assertEquals(first.itemId, updated.itemId)
            assertEquals("updated", updated.title)
            assertEquals(listOf("original"), repo.revisions(first.itemId).first().map { it.title })
        }
    }

    @Test fun actionReferencesAreNotEvictedAt512() {
        val store = RuntimeActionStore()
        val action = testAction()
        (1L..600L).forEach { store.put(it, action) }
        assertEquals(600, store.size())
        assertTrue(store.contains(1))
        assertTrue(store.contains(600))
    }

    @Test fun archiveRestoreUndoAndPermanentDeletionPreserveOtherData() = runBlocking {
        withRepository { repo, dao, actions ->
            val actionable = dao.insertItem(sampleItem(1))
            val unavailable = dao.insertItem(sampleItem(2))
            val pending = testAction()
            actions.put(actionable, pending)
            dao.insertHealthEvidence(HealthEvidenceEntity(kind = "OLD", occurredAtEpochMillis = 2, runtimeSessionId = "old", listenerConnectionId = null, itemId = actionable))
            val manual = repo.archiveItems(setOf(actionable, unavailable))
            assertEquals(2, manual.entries.size)
            assertEquals(emptyList<Long>(), ids(repo, NotificationActionView.ACTIONABLE, actions))
            assertEquals(setOf(actionable, unavailable), ids(repo, NotificationActionView.UNAVAILABLE_ARCHIVE, actions).toSet())
            val restored = repo.restoreItems(setOf(actionable, unavailable))
            assertEquals(listOf(actionable), ids(repo, NotificationActionView.ACTIONABLE, actions))
            assertEquals(listOf(unavailable), ids(repo, NotificationActionView.UNAVAILABLE_ARCHIVE, actions))
            repo.undoArchiveChange(restored)
            assertEquals(2, dao.manualArchives(listOf(actionable, unavailable)).size)
            repo.undoArchiveChange(manual)
            assertEquals(emptyList<ManualArchiveEntity>(), dao.manualArchives(listOf(actionable, unavailable)))
            assertEquals(0, repo.deleteArchivedItems(setOf(actionable)))
            repo.archiveItems(setOf(actionable))
            assertEquals(1, repo.deleteArchivedItems(setOf(actionable)))
            assertFalse(actions.contains(actionable))
            assertEquals(1, dao.healthEvidenceCountForItem(actionable))
            assertEquals(1L, dao.itemCount())
            assertEquals(emptyList<ManualArchiveEntity>(), dao.manualArchives(listOf(actionable)))
        }
    }

    @Test fun selectAllIncludesUnloadedRowsButNotLaterNotificationsAndDeduplicatesSources() = runBlocking {
        withRepository { repo, dao, actions ->
            val all = (1..850).map { dao.insertItem(sampleItem(it)) }.toSet()
            val work = dao.insertItem(sampleItem(900).copy(sourceUserRef = "work"))
            val filter = InboxViewFilter(includedSources = setOf(AppUserKey("personal", "example.archive")))
            val snapshot = repo.selectionSnapshot(filter, NotificationActionView.UNAVAILABLE_ARCHIVE, emptySet())
            assertEquals(all, snapshot.map { it.itemId }.toSet())
            assertEquals(1, snapshot.map { AppUserKey(it.sourceUserRef, it.sourcePackage) }.toSet().size)
            val later = dao.insertItem(sampleItem(901))
            assertEquals(850, repo.archiveItems(snapshot.map { it.itemId }.toSet()).entries.size)
            assertEquals(emptyList<ManualArchiveEntity>(), dao.manualArchives(listOf(later, work)))
            repo.setSourcesExcluded(snapshot.map { it.sourcePackage }.toSet(), true)
            assertEquals(listOf("example.archive"), dao.excludedPackageNames())
            repo.setSourcesExcluded(setOf("example.archive"), false)
            assertEquals(emptyList<String>(), dao.excludedPackageNames())
            assertEquals(850, repo.deleteArchivedItems(all))
            assertEquals(2L, dao.itemCount())
            assertEquals(setOf(later, work), ids(repo, NotificationActionView.UNAVAILABLE_ARCHIVE, actions).toSet())
        }
    }

    private suspend fun ids(repo: NotificationRepository, view: NotificationActionView, actions: RuntimeActionStore) =
        repo.selectionSnapshot(InboxViewFilter(), view, actions.itemIds.value).map { it.itemId }

    private suspend fun withRepository(block: suspend (NotificationRepository, OmnDao, RuntimeActionStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OmnDatabase::class.java).build()
        val actions = RuntimeActionStore()
        val repo = NotificationRepository(database, NotificationNormalizer(NormalizationLimits(),
            HmacSha256Fingerprinter(ByteArray(32) { 1 })), actions, MonitoringPolicyStore(context.packageName))
        try { block(repo, database.omnDao(), actions) } finally { database.close() }
    }

    private fun testAction(): PendingIntent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return PendingIntent.getActivity(context, 0, Intent("example.ARCHIVE_TEST").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
    }

    private fun captured(key: String, title: String, time: Long) = SnapshotResult.Captured(
        NotificationObservation("synthetic", "connection", ObservedCallbackKind.POST_OR_UPDATE,
            NotificationIdentity("example.archive", "personal", key, key.hashCode(), null),
            time, time, time, RawVisibleContent(title, null, null, null, null, null, null, null, null),
            emptySet(), ActionCapabilitySet(false, null, 0, false), 0, null), null,
    )

    private fun sampleItem(index: Int) = NotificationItemEntity(
        sourcePackage = "example.archive", sourceUserRef = "personal", systemKey = "key-$index",
        notificationId = index, tag = null, lifecycleGeneration = 0,
        firstReceivedAtEpochMillis = index.toLong(), lastUpdatedAtEpochMillis = index.toLong(),
        sortTimeEpochMillis = index.toLong(), originalPostTimeEpochMillis = null,
        title = "Synthetic $index", body = null, sourceLabelSnapshot = "Synthetic",
        contentFingerprint = null, normalizationWarnings = "", normalizationStrategyVersion = 1,
        titleOriginalCodePoints = 0, bodyOriginalCodePoints = 0, hadContentIntent = false,
        contentIntentCreatorPackage = null, notificationActionCount = 0, isRemoved = false,
        removedAtEpochMillis = null, lastRemovalReason = null, lastCallbackKind = "POST_OR_UPDATE",
        runtimeSessionIdAtLastCapture = "synthetic",
    )
}
