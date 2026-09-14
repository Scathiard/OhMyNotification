package io.github.prince_dulb.ohmynotification.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.prince_dulb.ohmynotification.OmnApplication
import io.github.prince_dulb.ohmynotification.core.model.ObservedCallbackKind
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class OmnNotificationListenerService : NotificationListenerService() {
    private val graph get() = (application as OmnApplication).graph
    // Never block the system callback or retain unlimited copies while storage is stalled.
    // Refusal is an explicit, sticky capture-gap fault, not a successful capture.
    private val workQueue = Channel<ListenerWork>(capacity = 256)

    @Volatile
    private var listenerConnectionId: String? = null

    override fun onCreate() {
        super.onCreate()
        ListenerDiagnostics.record(ListenerDiagnosticEvent.SERVICE_CREATE)
        graph.serialScope.launch {
            for (work in workQueue) {
                isolateListenerWork(
                    block = { if (process(work)) markProcessingOperational(true) },
                    onFailure = { recordCaptureFailure() },
                )
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ListenerDiagnostics.record(ListenerDiagnosticEvent.SERVICE_CONNECT)
        graph.listenerRebindController.onConnectionEstablished()
        val connectionId = UUID.randomUUID().toString()
        listenerConnectionId = connectionId
        activeService = WeakReference(this)
        markProcessingOperational(false)
        ListenerRuntimeState.setConnected(true, connectionId)
        enqueue(ListenerWork.Connected(connectionId, System.currentTimeMillis()))

        val recoveryEpoch = ListenerRuntimeState.failureEpoch()
        runCatching { activeNotifications?.toList().orEmpty() }
            .onSuccess { notifications ->
                notifications.sortedBy(StatusBarNotification::getPostTime).forEach { notification ->
                    capture(notification, ObservedCallbackKind.RECOVERY_SNAPSHOT, null, connectionId)
                }
                enqueue(ListenerWork.RecoveryCompleted(connectionId, recoveryEpoch))
            }
            .onFailure {
                enqueue(ListenerWork.RecoveryFailed(connectionId, System.currentTimeMillis()))
            }
    }

    override fun onListenerDisconnected() {
        ListenerDiagnostics.record(ListenerDiagnosticEvent.SERVICE_DISCONNECT)
        clearActiveService(this)
        val connectionId = listenerConnectionId
        listenerConnectionId = null
        ListenerRuntimeState.setConnected(false)
        enqueue(ListenerWork.Disconnected(connectionId, System.currentTimeMillis()))
        graph.serialScope.launch {
            delay(REBIND_DELAY_MILLIS)
            if (!ListenerRuntimeState.isConnected()) {
                graph.listenerRebindController.request(ListenerRebindTrigger.AUTOMATIC)
            }
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(
        statusBarNotification: StatusBarNotification,
        rankingMap: RankingMap,
    ) {
        ListenerDiagnostics.count(ListenerDiagnostics.Counter.POST_CALLBACK)
        capture(
            statusBarNotification,
            ObservedCallbackKind.POST_OR_UPDATE,
            removalReason = null,
            connectionId = listenerConnectionId ?: return,
        )
    }

    override fun onNotificationRemoved(
        statusBarNotification: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) {
        ListenerDiagnostics.count(ListenerDiagnostics.Counter.REMOVAL_CALLBACK)
        capture(
            statusBarNotification,
            ObservedCallbackKind.REMOVED,
            removalReason = reason,
            connectionId = listenerConnectionId ?: return,
        )
    }

    override fun onDestroy() {
        ListenerDiagnostics.record(ListenerDiagnosticEvent.SERVICE_DESTROY)
        clearActiveService(this)
        ListenerRuntimeState.setConnected(false)
        listenerConnectionId?.let { connectionId ->
            enqueue(ListenerWork.Disconnected(connectionId, System.currentTimeMillis()))
        }
        listenerConnectionId = null
        workQueue.close()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        ListenerDiagnostics.record(ListenerDiagnosticEvent.TASK_REMOVED)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: android.content.Intent): android.os.IBinder? {
        ListenerDiagnostics.record(ListenerDiagnosticEvent.SERVICE_BIND)
        return super.onBind(intent)
    }

    private fun capture(
        statusBarNotification: StatusBarNotification,
        callbackKind: ObservedCallbackKind,
        removalReason: Int?,
        connectionId: String,
    ) {
        if (!(application as OmnApplication).storageReady.value) {
            recordCaptureFailure()
            return
        }
        val result = runCatching {
            graph.snapshotFactory.capture(
                statusBarNotification = statusBarNotification,
                callbackKind = callbackKind,
                listenerConnectionId = connectionId,
                policy = graph.policyStore.snapshot(),
                removalReason = removalReason,
            )
        }.getOrElse {
            recordCaptureFailure()
            return
        }
        if (result is SnapshotResult.Failed) {
            ListenerDiagnostics.count(ListenerDiagnostics.Counter.SNAPSHOT_FAILED)
            recordCaptureFailure()
        }
        if (result is SnapshotResult.Excluded) ListenerDiagnostics.count(ListenerDiagnostics.Counter.SNAPSHOT_EXCLUDED)
        if (result !is SnapshotResult.Captured) return
        ListenerDiagnostics.count(ListenerDiagnostics.Counter.SNAPSHOT_CAPTURED)
        enqueue(ListenerWork.Captured(result))
    }

    private fun enqueue(work: ListenerWork): Boolean {
        val accepted = workQueue.trySend(work).isSuccess
        if (!accepted) {
            ListenerDiagnostics.count(ListenerDiagnostics.Counter.QUEUE_REJECTED)
            recordCaptureFailure()
        }
        return accepted
    }

    private suspend fun recoverActiveNotificationsOnDemand(): Boolean {
        val connectionId = listenerConnectionId ?: return false
        val epoch = ListenerRuntimeState.failureEpoch()
        val notifications = runCatching { activeNotifications?.toList().orEmpty() }
            .getOrElse { recordCaptureFailure(); return false }
        notifications.sortedBy(StatusBarNotification::getPostTime).forEach { notification ->
            capture(notification, ObservedCallbackKind.RECOVERY_SNAPSHOT, null, connectionId)
        }
        val completed = CompletableDeferred<Unit>()
        if (!enqueue(ListenerWork.RecoveryBarrier(completed))) return false
        return withTimeoutOrNull(ON_DEMAND_RECOVERY_TIMEOUT_MILLIS) {
            completed.await()
            (epoch == ListenerRuntimeState.failureEpoch()).also { if (it) markProcessingOperational(true) }
        } ?: false
    }

    private fun markProcessingOperational(isOperational: Boolean) {
        if (!ListenerRuntimeState.setProcessingOperational(isOperational)) return
        graph.statusNotificationController.onProcessingHealthChanged(
            isOperational = isOperational,
            listenerConnected = ListenerRuntimeState.isConnected(),
        )
    }

    private fun recordCaptureFailure() {
        ListenerRuntimeState.recordFailure()
        markProcessingOperational(false)
    }

    private suspend fun process(work: ListenerWork): Boolean =
        when (work) {
            is ListenerWork.Connected -> {
                graph.repository.publishSummary { count, latest ->
                    graph.statusNotificationController.onListenerConnectionChanged(
                        isConnected = work.connectionId == listenerConnectionId,
                        currentRecordCount = count, latestItem = latest,
                    )
                }
                false
            }
            is ListenerWork.Captured -> {
                val captured = work.value
                val label = graph.sourceLabelResolver.resolve(captured.observation.identity.sourcePackage)
                val commit = graph.repository.processCaptured(captured, label, graph.statusNotificationController::onCommitted)
                ListenerDiagnostics.count(ListenerDiagnostics.Counter.CAPTURE_PROCESSED)
                commit != null
            }
            is ListenerWork.RecoveryCompleted ->
                work.connectionId == listenerConnectionId &&
                    (application as OmnApplication).storageReady.value &&
                    work.failureEpoch == ListenerRuntimeState.failureEpoch()
            is ListenerWork.RecoveryFailed -> { recordCaptureFailure(); false }
            is ListenerWork.Disconnected -> {
                graph.statusNotificationController.onListenerConnectionChanged(isConnected = false)
                false
            }
            is ListenerWork.RecoveryBarrier -> { work.completed.complete(Unit); false }
        }

    private sealed interface ListenerWork {
        data class Connected(val connectionId: String, val observedAtEpochMillis: Long) : ListenerWork
        data class Captured(val value: SnapshotResult.Captured) : ListenerWork
        data class RecoveryCompleted(val connectionId: String, val failureEpoch: Long) : ListenerWork
        data class RecoveryFailed(val connectionId: String, val observedAtEpochMillis: Long) : ListenerWork
        data class Disconnected(val connectionId: String?, val observedAtEpochMillis: Long) : ListenerWork
        data class RecoveryBarrier(val completed: CompletableDeferred<Unit>) : ListenerWork
    }

    companion object {
        @Volatile
        private var activeService = WeakReference<OmnNotificationListenerService>(null)

        internal suspend fun requestActiveNotificationRecovery(): Boolean =
            activeService.get()?.recoverActiveNotificationsOnDemand() ?: false

        private fun clearActiveService(service: OmnNotificationListenerService) {
            if (activeService.get() === service) activeService.clear()
        }

        const val REBIND_DELAY_MILLIS = 3_000L
        private const val ON_DEMAND_RECOVERY_TIMEOUT_MILLIS = 2_500L
    }
}

internal suspend fun isolateListenerWork(
    block: suspend () -> Unit,
    onFailure: (Exception) -> Unit,
): Boolean = try {
    block()
    true
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    runCatching { onFailure(failure) }
    false
}
