package io.github.prince_dulb.ohmynotification.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Diagnostic probes: demonstrate primitive/lifecycle boundaries, not device reliability. */
class RiskAuditConcurrencyTest {
    @Test fun oneDispatcherLaneDoesNotSerializeSuspendingTransactions() = runBlocking {
        withTimeout(5_000) {
            val dispatcher = Dispatchers.IO.limitedParallelism(1)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val first = launch(dispatcher) {
                events += "first-read-actions"
                started.complete(Unit)
                release.await()
                events += "first-delete-using-old-actions"
            }
            started.await()
            val second = launch(dispatcher) {
                events += "second-restores-action"
                release.complete(Unit)
            }
            first.join()
            second.join()
            assertEquals(listOf("first-read-actions", "second-restores-action", "first-delete-using-old-actions"), events)
        }
    }

    @Test fun automaticRebindBudgetResetsAfterSuccessfulConnection() {
        val gate = ListenerRebindGate()
        repeat(3) { assertTrue(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, it * 20_000L)) }
        assertFalse(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 86_400_000L))
        gate.onConnectionEstablished()
        assertTrue(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 86_400_001L))
        assertTrue(gate.tryAcquire(ListenerRebindTrigger.USER, 86_400_000L))
    }

    @Test fun acknowledgingAnOldFailureCannotEraseANewerGap() {
        ListenerRuntimeState.recordFailure()
        val acknowledgedEpoch = ListenerRuntimeState.failureEpoch()
        ListenerRuntimeState.recordFailure()
        assertFalse(ListenerRuntimeState.acknowledgeGap(acknowledgedEpoch))
        assertTrue(ListenerRuntimeState.hasKnownGap())
        assertTrue(ListenerRuntimeState.acknowledgeGap())
        assertFalse(ListenerRuntimeState.hasKnownGap())
    }

    @Test fun connectionDoesNotClearProcessingFailureAndGapRemainsAfterRecovery() {
        ListenerRuntimeState.setProcessingOperational(false)
        ListenerRuntimeState.recordFailure()
        ListenerRuntimeState.setConnected(true, "synthetic")
        assertFalse(ListenerRuntimeState.isProcessingOperational())
        ListenerRuntimeState.setProcessingOperational(true)
        assertTrue(ListenerRuntimeState.hasKnownGap())
        assertEquals(io.github.prince_dulb.ohmynotification.core.health.StatusPresentationState.KNOWN_CAPTURE_GAP,
            io.github.prince_dulb.ohmynotification.core.health.StatusPresentationDeriver.derive(
                true, true, true, true, ListenerRuntimeState.hasKnownGap()))
        ListenerRuntimeState.acknowledgeGap()
        ListenerRuntimeState.setConnected(false)
    }
}
