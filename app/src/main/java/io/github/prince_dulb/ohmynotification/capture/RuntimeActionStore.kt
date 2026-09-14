package io.github.prince_dulb.ohmynotification.capture

import android.app.ActivityOptions
import android.app.PendingIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RuntimeActionStatus {
    ACCEPTED,
    CANCELED,
    NOT_FOUND,
    SECURITY_REJECTED,
}

class RuntimeActionStore {
    // These are live capabilities, not disposable UI cache entries. Do not silently evict them.
    // Only tiny ID/token references are held here; notification text remains paged in Room.
    private val handles = LinkedHashMap<Long, PendingIntent>()
    private val currentItemIds = MutableStateFlow<Set<Long>>(emptySet())

    val itemIds: StateFlow<Set<Long>> = currentItemIds.asStateFlow()

    @Synchronized
    fun put(itemId: Long, action: PendingIntent?) {
        require(itemId > 0L)
        val previouslyPresent = itemId in handles
        if (action == null) handles.remove(itemId) else handles[itemId] = action
        if (previouslyPresent != (action != null)) publishItemIds()
    }

    @Synchronized
    fun contains(itemId: Long): Boolean = itemId in handles

    @Synchronized
    fun remove(itemId: Long) {
        if (handles.remove(itemId) != null) publishItemIds()
    }

    @Synchronized
    fun removeAll(itemIds: Collection<Long>) {
        if (handles.keys.removeAll(itemIds.toSet())) publishItemIds()
    }

    @Synchronized
    fun sendFromVisibleActivity(itemId: Long): RuntimeActionStatus {
        val action = handles[itemId] ?: return RuntimeActionStatus.NOT_FOUND
        val options = ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        }
        return try {
            action.send(options.toBundle())
            RuntimeActionStatus.ACCEPTED
        } catch (_: PendingIntent.CanceledException) {
            handles.remove(itemId)
            publishItemIds()
            RuntimeActionStatus.CANCELED
        } catch (_: SecurityException) {
            // A permission or foreground restriction can be temporary. Keep the capability.
            RuntimeActionStatus.SECURITY_REJECTED
        }
    }

    @Synchronized
    fun size(): Int = handles.size

    private fun publishItemIds() {
        currentItemIds.value = handles.keys.toSet()
    }
}
