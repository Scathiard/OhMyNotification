package io.github.prince_dulb.ohmynotification.data

import android.app.PendingIntent
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStatus
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStore
import io.github.prince_dulb.ohmynotification.capture.SnapshotResult
import io.github.prince_dulb.ohmynotification.core.model.NotificationObservation
import io.github.prince_dulb.ohmynotification.core.model.ObservedCallbackKind
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizationResult
import io.github.prince_dulb.ohmynotification.core.normalize.NotificationNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class NotificationCommit(
    val item: NotificationItemEntity,
    val changeKind: NotificationChangeKind,
) {
    val isNewItem: Boolean get() = changeKind == NotificationChangeKind.CREATED
    val updatesStatusSummary: Boolean get() =
        changeKind == NotificationChangeKind.CREATED || changeKind == NotificationChangeKind.UPDATED
}

enum class NotificationChangeKind {
    CREATED,
    UPDATED,
    NO_CONTENT_CHANGE,
    REMOVED,
}

class NotificationRepository(
    private val database: OmnDatabase,
    private val normalizer: NotificationNormalizer,
    private val runtimeActionStore: RuntimeActionStore,
    private val policyStore: MonitoringPolicyStore,
    private val onStorageFailure: () -> Unit = {},
) {
    private val dao = database.omnDao()
    private val mutations = Mutex()
    private val committedHighWater = AtomicLong(0)

    /** Capture synchronously on the UI click, before the selection query can suspend. */
    fun selectionBoundary(): Long = committedHighWater.get()

    fun revisions(itemId: Long) = dao.observeRevisions(itemId)

    fun item(itemId: Long) = dao.observeItem(itemId).catch { onStorageFailure(); emit(null) }

    fun pagedRevisions(itemId: Long) = Pager(PagingConfig(pageSize = 20, enablePlaceholders = false, maxSize = 100)) {
        dao.pageRevisions(itemId)
    }.flow

    suspend fun persistentTarget(itemId: Long): NotificationTargetEntity? = mutations.withLock { dao.target(itemId) }

    val sourceSummaries: Flow<List<SourceSummaryRow>> = dao.observeSourceSummaries().catch { onStorageFailure(); emit(emptyList()) }
    val excludedSources: Flow<List<ExcludedSourceEntity>> = dao.observeExcludedSources().catch { onStorageFailure(); emit(emptyList()) }
    val itemCount: Flow<Long> = dao.observeItemCount().catch { onStorageFailure(); emit(0) }
    val runtimeActionItemIds = runtimeActionStore.itemIds
    fun manualArchiveIds(ids: List<Long>) = dao.observeManualArchiveIds(ids).catch { onStorageFailure(); emit(emptyList()) }

    fun archiveCount(actionIds: Set<Long>): Flow<Long> = dao.observeMatchingCount(
        buildPageItemsQuery(InboxViewFilter(), NotificationActionView.UNAVAILABLE_ARCHIVE, actionIds, "COUNT(*)"),
    ).catch { onStorageFailure(); emit(0) }

    suspend fun selectionSnapshot(filter: InboxViewFilter, view: NotificationActionView, actionIds: Set<Long>, throughItemId: Long? = null): List<NotificationSelectionRow> =
        mutations.withLock {
            dao.selectionSnapshot(buildPageItemsQuery(filter, view, actionIds, "itemId, sourcePackage, sourceUserRef", throughItemId))
        }

    suspend fun archiveItems(ids: Set<Long>): ArchiveChange = mutations.withLock { database.withTransaction {
        val changed = ids.chunked(400).flatMap { chunk ->
            val existing = dao.existingItemIds(chunk).toSet()
            val archived = dao.manualArchives(chunk).mapTo(hashSetOf()) { it.itemId }
            (existing - archived).map { ManualArchiveEntity(it, System.currentTimeMillis()) }
                .also { dao.insertManualArchives(it) }
        }
        ArchiveChange(changed, wasArchive = true)
    } }

    suspend fun restoreItems(ids: Set<Long>): ArchiveChange = mutations.withLock { database.withTransaction {
        val entries = ids.chunked(400).flatMap { chunk ->
            dao.manualArchives(chunk).also { dao.removeManualArchives(chunk) }
        }
        ArchiveChange(entries, wasArchive = false)
    } }

    suspend fun undoArchiveChange(change: ArchiveChange) = mutations.withLock { database.withTransaction {
        if (change.wasArchive) {
            change.entries.forEach { dao.undoManualArchive(it.itemId, it.archivedAtEpochMillis) }
        } else {
            change.entries.chunked(400).forEach { chunk ->
                val existing = dao.existingItemIds(chunk.map { it.itemId }).toSet()
                dao.insertManualArchives(chunk.filter { it.itemId in existing })
            }
        }
    } }

    /** Recheck archive eligibility at commit time so actionable inbox records cannot be deleted. */
    suspend fun deleteArchivedItems(ids: Set<Long>): Int = mutations.withLock {
        val actionIds = runtimeActionItemIds.value
        val deletedIds = database.withTransaction {
            ids.chunked(400).flatMap { chunk ->
                val manual = dao.manualArchives(chunk).mapTo(hashSetOf()) { it.itemId }
                val eligible = dao.existingItemIds(chunk).filter { it in manual || it !in actionIds }
                dao.removeManualArchives(eligible)
                dao.deleteRevisions(eligible)
                dao.deleteTargets(eligible)
                dao.deleteItems(eligible)
                eligible
            }
        }
        runtimeActionStore.removeAll(deletedIds)
        deletedIds.size
    }

    suspend fun setSourcesExcluded(packages: Set<String>, excluded: Boolean) = mutations.withLock {
        database.withTransaction {
            packages.forEach { name ->
                val entry = ExcludedSourceEntity(name, System.currentTimeMillis())
                if (excluded) dao.insertExcludedSource(entry) else dao.deleteExcludedSource(entry)
            }
        }
        policyStore.replaceUserExclusions(dao.excludedPackageNames().toSet())
    }

    suspend fun currentItemCount(): Long = dao.itemCount()

    suspend fun latestItem(): NotificationItemEntity? = dao.latestItem()

    /** Read and apply one summary while captures cannot publish a newer incremental update. */
    suspend fun publishSummary(consumer: (Long, NotificationItemEntity?) -> Unit) = mutations.withLock {
        val snapshot = database.withTransaction { dao.itemCount() to dao.latestItem() }
        consumer(snapshot.first, snapshot.second)
    }

    /** A new capability invalidates the current source, not the Pager and its scroll anchor. */
    fun livePagedItems(viewFilter: InboxViewFilter, actionView: NotificationActionView): Flow<PagingData<NotificationItemEntity>> = channelFlow {
        val currentSource = AtomicReference<PagingSource<Int, NotificationItemEntity>?>(null)
        val observer = launch {
            runtimeActionItemIds.collect { currentSource.get()?.invalidate() }
        }
        try {
            Pager(PagingConfig(pageSize = 40, prefetchDistance = 12, enablePlaceholders = false, maxSize = 200)) {
                val actions = runtimeActionItemIds.value
                dao.pageItemsFromSources(buildPageItemsQuery(viewFilter, actionView, actions)).also {
                    currentSource.set(it)
                    if (runtimeActionItemIds.value != actions) it.invalidate()
                }
            }.flow.collect { send(it) }
        } finally { observer.cancel() }
    }

    fun pagedItems(
        viewFilter: InboxViewFilter,
        actionView: NotificationActionView,
        runtimeActionItemIds: Set<Long>,
    ): Flow<PagingData<NotificationItemEntity>> = Pager(
        config = PagingConfig(
            pageSize = 40,
            prefetchDistance = 12,
            enablePlaceholders = false,
            maxSize = 200,
        ),
        pagingSourceFactory = {
            dao.pageItemsFromSources(
                buildPageItemsQuery(
                    viewFilter = viewFilter,
                    actionView = actionView,
                    runtimeActionItemIds = runtimeActionItemIds,
                ),
            )
        },
    ).flow

    suspend fun processCaptured(
        captured: SnapshotResult.Captured,
        sourceLabel: String?,
        onCommitted: (NotificationCommit) -> Unit = {},
    ): NotificationCommit? = mutations.withLock {
        val observation = captured.observation
        val transactionResult = database.withTransaction {
            when (observation.callbackKind) {
                ObservedCallbackKind.REMOVED -> commitRemoval(observation)
                ObservedCallbackKind.POST_OR_UPDATE,
                ObservedCallbackKind.RECOVERY_SNAPSHOT,
                -> commitContent(observation, sourceLabel)
            }
        }
        val commit = transactionResult.commit

        if (observation.callbackKind != ObservedCallbackKind.REMOVED && commit != null) {
            runtimeActionStore.put(commit.item.itemId, captured.runtimeAction)
            committedHighWater.updateAndGet { maxOf(it, commit.item.itemId) }
        }
        commit?.let(onCommitted)
        commit
    }

    suspend fun reconcilePolicyFromDatabase() = mutations.withLock {
        policyStore.replaceUserExclusions(dao.excludedPackageNames().toSet())
        committedHighWater.set(dao.maximumItemId())
    }

    suspend fun setSourceExcluded(sourcePackage: String, excluded: Boolean) = mutations.withLock {
        val alreadyExcluded = sourcePackage in policyStore.snapshot().userExcludedPackages
        if (alreadyExcluded == excluded) return@withLock
        if (excluded) {
            dao.insertExcludedSource(
                ExcludedSourceEntity(sourcePackage, System.currentTimeMillis()),
            )
        } else {
            dao.deleteExcludedSource(
                ExcludedSourceEntity(sourcePackage, excludedAtEpochMillis = 0L),
            )
        }
        policyStore.setExcluded(sourcePackage, excluded)
    }

    suspend fun sendRuntimeAction(item: NotificationItemEntity): RuntimeActionStatus =
        mutations.withLock { runtimeActionStore.sendFromVisibleActivity(item.itemId) }

    fun hasRuntimeAction(item: NotificationItemEntity): Boolean = runtimeActionStore.contains(item.itemId)


    private suspend fun commitContent(
        observation: NotificationObservation,
        sourceLabel: String?,
    ): NotificationTransactionResult {
        val normalized = normalizer.normalize(observation, sourceLabel)
        if (normalized !is NormalizationResult.Normalized) return NotificationTransactionResult(null)

        val identity = observation.identity
        val previous = dao.latestForIdentity(
            identity.sourcePackage,
            identity.sourceUserRef,
            identity.systemKey,
        )
        val outcome = NotificationItemMerger.merge(previous, observation, normalized.content)
        val entity = outcome.item
        if (
            !outcome.isNewItem &&
            previous != null &&
            NotificationItemMerger.hasSameCapturedState(previous, entity)
        ) {
            return NotificationTransactionResult(
                NotificationCommit(previous, NotificationChangeKind.NO_CONTENT_CHANGE),
            )
        }
        val visibleChanged = previous != null && (previous.title != entity.title || previous.body != entity.body)
        if (!outcome.isNewItem && previous != null && visibleChanged) {
            dao.insertRevision(NotificationRevisionEntity(
                itemId = previous.itemId, recordedAtEpochMillis = previous.lastUpdatedAtEpochMillis,
                title = previous.title, body = previous.body,
                originalPostTimeEpochMillis = previous.originalPostTimeEpochMillis,
                contentFingerprint = previous.contentFingerprint, reason = "CONTENT_UPDATED",
            ))
        }
        val itemId = if (outcome.isNewItem) dao.insertItem(entity) else {
            dao.updateItem(entity)
            entity.itemId
        }
        val committed = entity.copy(itemId = itemId)
        val presentationChanged = previous == null ||
            previous.title != committed.title ||
            previous.body != committed.body ||
            previous.sourceLabelSnapshot != committed.sourceLabelSnapshot
        val changeKind = when {
            outcome.isNewItem -> NotificationChangeKind.CREATED
            presentationChanged -> NotificationChangeKind.UPDATED
            else -> NotificationChangeKind.NO_CONTENT_CHANGE
        }
        return NotificationTransactionResult(NotificationCommit(committed, changeKind))
    }

    private suspend fun commitRemoval(observation: NotificationObservation): NotificationTransactionResult {
        val identity = observation.identity
        val previous = dao.latestForIdentity(
            identity.sourcePackage,
            identity.sourceUserRef,
            identity.systemKey,
        ) ?: return NotificationTransactionResult(null)
        // A delayed removal for a reused key must not remove the newer post's lifecycle.
        if (previous.lastObservedPostTimeEpochMillis != null &&
            previous.lastObservedPostTimeEpochMillis != observation.postTimeEpochMillis) {
            return NotificationTransactionResult(null)
        }
        if (previous.isRemoved) {
            return NotificationTransactionResult(
                NotificationCommit(previous, NotificationChangeKind.NO_CONTENT_CHANGE),
            )
        }

        val removed = previous.copy(
            lastUpdatedAtEpochMillis = maxOf(
                previous.lastUpdatedAtEpochMillis,
                observation.observedAtEpochMillis,
            ),
            isRemoved = true,
            removedAtEpochMillis = observation.observedAtEpochMillis,
            lastRemovalReason = observation.removalReason,
            lastCallbackKind = observation.callbackKind.name,
            runtimeSessionIdAtLastCapture = observation.runtimeSessionId,
        )
        dao.updateItem(removed)
        return NotificationTransactionResult(NotificationCommit(removed, NotificationChangeKind.REMOVED))
    }
}

private data class NotificationTransactionResult(val commit: NotificationCommit?)

enum class NotificationActionView { ACTIONABLE, UNAVAILABLE_ARCHIVE }

data class ArchiveChange(val entries: List<ManualArchiveEntity>, val wasArchive: Boolean)

internal fun buildPageItemsQuery(
    viewFilter: InboxViewFilter,
    actionView: NotificationActionView,
    runtimeActionItemIds: Set<Long>,
    projection: String = "*",
    throughItemId: Long? = null,
): SupportSQLiteQuery {
    require(runtimeActionItemIds.all { itemId -> itemId > 0L })
    require(throughItemId == null || throughItemId >= 0L)
    val orderedItemIds = runtimeActionItemIds.sorted()
    val actionPredicate = when {
        orderedItemIds.isEmpty() && actionView == NotificationActionView.ACTIONABLE -> "0"
        orderedItemIds.isEmpty() -> "1"
        actionView == NotificationActionView.ACTIONABLE ->
            "itemId IN (${orderedItemIds.joinToString(separator = ",")})"
        else -> "itemId NOT IN (${orderedItemIds.joinToString(separator = ",")})"
    }
    val archivePredicate = "itemId IN (SELECT itemId FROM manual_archives)"
    val pagePredicate = if (actionView == NotificationActionView.ACTIONABLE) {
        "($actionPredicate AND NOT $archivePredicate)"
    } else {
        "($actionPredicate OR $archivePredicate)"
    }
    val sourceSet = if (viewFilter.includedSources.isNotEmpty()) {
        viewFilter.includedSources
    } else {
        viewFilter.excludedSources
    }
    val ordered = sourceSet.sortedWith(
        compareBy(AppUserKey::sourcePackage, AppUserKey::sourceUserRef),
    )
    val sourcePredicate = ordered.joinToString(separator = " OR ") {
        "(sourcePackage = ? AND sourceUserRef = ?)"
    }
    val arguments = ordered.flatMap { source ->
        listOf(source.sourcePackage, source.sourceUserRef)
    }.toTypedArray()
    val where = when {
        sourcePredicate.isEmpty() -> pagePredicate
        viewFilter.includedSources.isNotEmpty() -> "$pagePredicate AND ($sourcePredicate)"
        else -> "$pagePredicate AND NOT ($sourcePredicate)"
    }
    return SimpleSQLiteQuery(
        "SELECT $projection FROM notification_items WHERE $where" +
            (throughItemId?.let { " AND itemId <= $it" } ?: "") + " " +
            (if (projection == "COUNT(*)") "" else "ORDER BY sortTimeEpochMillis DESC, itemId DESC"),
        arguments,
    )
}
