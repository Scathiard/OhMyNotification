package io.github.prince_dulb.ohmynotification.data

import android.app.PendingIntent
import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStore
import io.github.prince_dulb.ohmynotification.core.normalize.HmacSha256Fingerprinter
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizationLimits
import io.github.prince_dulb.ohmynotification.core.normalize.NotificationNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveOperationsTest {
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
