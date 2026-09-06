package io.github.prince_dulb.ohmynotification.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface OmnDao {
    @Query(
        """
        SELECT * FROM notification_items
        ORDER BY sortTimeEpochMillis DESC, itemId DESC
        """,
    )
    fun pageAllItems(): PagingSource<Int, NotificationItemEntity>

    @RawQuery(observedEntities = [NotificationItemEntity::class, ManualArchiveEntity::class])
    fun pageItemsFromSources(query: SupportSQLiteQuery): PagingSource<Int, NotificationItemEntity>

    @RawQuery
    suspend fun selectionSnapshot(query: SupportSQLiteQuery): List<NotificationSelectionRow>

    @RawQuery(observedEntities = [NotificationItemEntity::class, ManualArchiveEntity::class])
    fun observeMatchingCount(query: SupportSQLiteQuery): Flow<Long>

    @Query("SELECT itemId FROM manual_archives WHERE itemId IN (:ids)")
    fun observeManualArchiveIds(ids: List<Long>): Flow<List<Long>>

    @Query("SELECT * FROM manual_archives WHERE itemId IN (:ids)")
    suspend fun manualArchives(ids: List<Long>): List<ManualArchiveEntity>

    @Query("SELECT itemId FROM notification_items WHERE itemId IN (:ids)")
    suspend fun existingItemIds(ids: List<Long>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertManualArchives(entries: List<ManualArchiveEntity>)

    @Query("DELETE FROM manual_archives WHERE itemId IN (:ids)")
    suspend fun removeManualArchives(ids: List<Long>): Int

    @Query("DELETE FROM manual_archives WHERE itemId = :id AND archivedAtEpochMillis = :timestamp")
    suspend fun undoManualArchive(id: Long, timestamp: Long)

    @Query("DELETE FROM notification_items WHERE itemId IN (:ids)")
    suspend fun deleteItems(ids: List<Long>): Int

    @Query(
        """
        SELECT * FROM notification_items
        WHERE sourcePackage = :sourcePackage
          AND sourceUserRef = :sourceUserRef
          AND systemKey = :systemKey
        ORDER BY lifecycleGeneration DESC
        LIMIT 1
        """,
    )
    suspend fun latestForIdentity(
        sourcePackage: String,
        sourceUserRef: String,
        systemKey: String,
    ): NotificationItemEntity?

    @Insert
    suspend fun insertItem(item: NotificationItemEntity): Long

    @Update
    suspend fun updateItem(item: NotificationItemEntity)

    @Query("DELETE FROM notification_items WHERE itemId = :itemId")
    suspend fun deleteItem(itemId: Long): Int

    @Query("SELECT COUNT(*) FROM health_evidence WHERE itemId = :itemId")
    suspend fun healthEvidenceCountForItem(itemId: Long): Int

    @Insert
    suspend fun insertHealthEvidence(evidence: HealthEvidenceEntity): Long

    @Query(
        """
        DELETE FROM health_evidence
        WHERE evidenceId NOT IN (
            SELECT evidenceId FROM health_evidence
            ORDER BY evidenceId DESC
            LIMIT :keepCount
        )
        """,
    )
    suspend fun trimHealthEvidence(keepCount: Int)

    @Query(
        """
        SELECT sourcePackage,
               sourceUserRef,
               MAX(sourceLabelSnapshot) AS sourceLabelSnapshot,
               COUNT(*) AS recordCount,
               MAX(sortTimeEpochMillis) AS latestTimeEpochMillis
        FROM notification_items
        GROUP BY sourcePackage, sourceUserRef
        ORDER BY latestTimeEpochMillis DESC, sourcePackage ASC, sourceUserRef ASC
        """,
    )
    fun observeSourceSummaries(): Flow<List<SourceSummaryRow>>

    @Query("SELECT COUNT(*) FROM notification_items")
    fun observeItemCount(): Flow<Long>


    @Query("SELECT COUNT(*) FROM notification_items")
    suspend fun itemCount(): Long

    @Query(
        """
        SELECT * FROM notification_items
        ORDER BY sortTimeEpochMillis DESC, itemId DESC
        LIMIT 1
        """,
    )
    suspend fun latestItem(): NotificationItemEntity?

    @Query("SELECT * FROM excluded_sources ORDER BY sourcePackage ASC")
    fun observeExcludedSources(): Flow<List<ExcludedSourceEntity>>

    @Query("SELECT sourcePackage FROM excluded_sources")
    suspend fun excludedPackageNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExcludedSource(source: ExcludedSourceEntity)

    @Delete
    suspend fun deleteExcludedSource(source: ExcludedSourceEntity)
}
