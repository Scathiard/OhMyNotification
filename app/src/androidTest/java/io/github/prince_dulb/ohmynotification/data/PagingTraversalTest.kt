package io.github.prince_dulb.ohmynotification.data

import androidx.paging.AsyncPagingDataDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStore
import io.github.prince_dulb.ohmynotification.core.normalize.HmacSha256Fingerprinter
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizationLimits
import io.github.prince_dulb.ohmynotification.core.normalize.NotificationNormalizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production Pager with a bounded cache, not Compose rendering/scroll gestures. */
@RunWith(AndroidJUnit4::class)
class PagingTraversalTest {
    @Test fun tenThousandRecordsRemainReachableInBothDirectionsAfterCacheDrops() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, OmnDatabase::class.java).build()
        val repo = NotificationRepository(db, NotificationNormalizer(NormalizationLimits(),
            HmacSha256Fingerprinter(ByteArray(32) { 1 })), RuntimeActionStore(), MonitoringPolicyStore(context.packageName))
        try {
            db.withTransaction { repeat(10_000) { db.omnDao().insertItem(sample(it + 1)) } }
            withContext(Dispatchers.Main) {
                val differ = AsyncPagingDataDiffer(
                    diffCallback = object : DiffUtil.ItemCallback<NotificationItemEntity>() {
                        override fun areItemsTheSame(oldItem: NotificationItemEntity, newItem: NotificationItemEntity) = oldItem.itemId == newItem.itemId
                        override fun areContentsTheSame(oldItem: NotificationItemEntity, newItem: NotificationItemEntity) = oldItem == newItem
                    },
                    updateCallback = object : ListUpdateCallback {
                        override fun onInserted(position: Int, count: Int) = Unit
                        override fun onRemoved(position: Int, count: Int) = Unit
                        override fun onMoved(fromPosition: Int, toPosition: Int) = Unit
                        override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
                    },
                )
                val collector = launch { repo.livePagedItems(InboxViewFilter(), NotificationActionView.UNAVAILABLE_ARCHIVE).collectLatest(differ::submitData) }
                try {
                    withTimeout(45_000) {
                        while (differ.itemCount == 0) delay(10)
                        assertEquals(10_000L, differ.snapshot().items.first().itemId)
                        var loads = 0
                        while (differ.snapshot().items.last().itemId != 1L) {
                            val previous = differ.snapshot().items.last().itemId
                            differ.getItem(differ.itemCount - 1)
                            while (differ.snapshot().items.last().itemId == previous) delay(5)
                            assertTrue("History cache grew without bound", differ.itemCount <= 240)
                            loads++
                        }
                        assertTrue(loads > 200)
                        assertTrue("Newer pages were never dropped", differ.snapshot().items.first().itemId < 10_000L)
                        while (differ.snapshot().items.first().itemId != 10_000L) {
                            val previous = differ.snapshot().items.first().itemId
                            differ.getItem(0)
                            while (differ.snapshot().items.first().itemId == previous) delay(5)
                            assertTrue(differ.itemCount <= 240)
                        }
                    }
                } finally { collector.cancelAndJoin() }
            }
        } finally { db.close() }
    }

    private fun sample(index: Int) = NotificationItemEntity(
        sourcePackage = "example.paging", sourceUserRef = "synthetic", systemKey = "key-$index",
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
