package io.github.prince_dulb.ohmynotification.capture

import org.junit.Assert.*
import org.junit.Test

class ListenerRecoveryCoordinatorTest {
    private class Fixture : ListenerRecoveryPort {
        var access = true
        var live = false
        var operational = true
        var connection = "first"
        var binds = 0
        var unbinds = 0
        var rejectBind = false
        var rejectUnbind = false
        var now = 0L
        data class Timer(val at: Long, val block: () -> Unit, var canceled: Boolean = false)
        val timers = mutableListOf<Timer>()
        val coordinator = ListenerRecoveryCoordinator(this, { delay, block ->
            val timer = Timer(now + delay, block)
            timers += timer
            val cancel: () -> Unit = { timer.canceled = true }
            cancel
        })
        override fun accessGranted() = access
        override fun connected() = live
        override fun processingOperational() = operational
        override fun connectionId() = if (live) connection else null
        override fun requestBind() { binds++; if (rejectBind) throw SecurityException() }
        override fun requestUnbind() { unbinds++; if (rejectUnbind) throw SecurityException(); live = false }
        fun advance(millis: Long) {
            val end = now + millis
            while (true) {
                val timer = timers.filter { !it.canceled && it.at <= end }.minByOrNull { it.at } ?: break
                timer.canceled = true
                now = timer.at
                timer.block()
            }
            now = end
        }
        fun health() = coordinator.onHealthChanged()
    }

    @Test fun issuedRequestIsNotSuccessAndNoCallbackEndsInTimeout() = with(Fixture()) {
        coordinator.start()
        assertEquals(ListenerRecoveryState.WAITING, coordinator.state.value)
        advance(8_000)
        assertEquals(1, unbinds)
        assertEquals(ListenerRecoveryState.RESETTING, coordinator.state.value)
        advance(250)
        assertEquals(2, binds)
        assertEquals(ListenerRecoveryState.WAITING_AFTER_RESET, coordinator.state.value)
        advance(12_000)
        assertEquals(ListenerRecoveryState.TIMED_OUT, coordinator.state.value)
        advance(100_000)
        assertEquals(2, binds)
        assertFalse(timers.any { !it.canceled })
    }

    @Test fun delayedConnectionBeforeDeadlineAvoidsReset() = with(Fixture()) {
        coordinator.start(); advance(7_999); live = true; health(); advance(50_000)
        assertEquals(ListenerRecoveryState.RECOVERED, coordinator.state.value)
        assertEquals(0, unbinds)
    }

    @Test fun connectedButProcessingNotReadyDoesNotReportSuccessOrResetConnection() = with(Fixture()) {
        coordinator.start(); live = true; operational = false; health()
        assertEquals(ListenerRecoveryState.WAITING, coordinator.state.value)
        advance(8_000)
        assertEquals(ListenerRecoveryState.PROCESSING_FAILED, coordinator.state.value)
        assertEquals(0, unbinds)
        operational = true; health()
        assertEquals(ListenerRecoveryState.RECOVERED, coordinator.state.value)
    }

    @Test fun duplicateRequestsAndForegroundRefreshKeepOriginalDeadline() = with(Fixture()) {
        coordinator.start(); advance(4_000)
        repeat(30) { coordinator.start(); health() }
        assertEquals(1, binds)
        advance(4_000)
        assertEquals(1, unbinds)
        advance(250); live = true; health(); advance(20_000)
        assertEquals(ListenerRecoveryState.RECOVERED, coordinator.state.value)
        assertEquals(2, binds)
    }

    @Test fun revocationDuringResetDoesNotBindWithoutPermission() = with(Fixture()) {
        coordinator.start(); advance(8_000); access = false; health(); advance(50_000)
        assertEquals(ListenerRecoveryState.ACCESS_REQUIRED, coordinator.state.value)
        assertEquals(1, binds)
    }

    @Test fun revocationWithoutCallbackIsCheckedAtDeadline() = with(Fixture()) {
        coordinator.start(); access = false; advance(8_000)
        assertEquals(ListenerRecoveryState.ACCESS_REQUIRED, coordinator.state.value)
        assertEquals(0, unbinds)
    }

    @Test fun lateOldConnectionDuringResetCannotSkipMatchingBind() = with(Fixture()) {
        coordinator.start(); advance(8_000); live = true; health()
        assertEquals(ListenerRecoveryState.RESETTING, coordinator.state.value)
        advance(250)
        assertEquals(2, binds)
        assertEquals(ListenerRecoveryState.WAITING_AFTER_RESET, coordinator.state.value)
        connection = "fresh"; health()
        assertEquals(ListenerRecoveryState.RECOVERED, coordinator.state.value)
    }

    @Test fun exceptionsAreTerminalAndCancelDeadline() = with(Fixture()) {
        rejectBind = true; coordinator.start(); advance(50_000)
        assertEquals(ListenerRecoveryState.PLATFORM_REJECTED, coordinator.state.value)
        assertEquals(0, unbinds)
        rejectBind = false; rejectUnbind = true; coordinator.start(); advance(50_000)
        assertEquals(ListenerRecoveryState.PLATFORM_REJECTED, coordinator.state.value)
        assertEquals(1, unbinds)
        assertEquals(2, binds)
    }

    @Test fun healthyAndUnauthorizedStartsDoNotTouchBinding() = with(Fixture()) {
        live = true; coordinator.start()
        assertEquals(ListenerRecoveryState.RECOVERED, coordinator.state.value)
        access = false; coordinator.start()
        assertEquals(ListenerRecoveryState.ACCESS_REQUIRED, coordinator.state.value)
        assertEquals(0, binds)
    }

    @Test fun staleDeadlineCannotMutateSubsequentAttempt() = with(Fixture()) {
        coordinator.start(); val oldDeadline = timers.single().block
        live = true; health(); live = false; health(); coordinator.start()
        oldDeadline()
        assertEquals(ListenerRecoveryState.WAITING, coordinator.state.value)
        assertEquals(0, unbinds)
    }

    @Test fun oldConnectionCannotReportSuccessEvenAfterResetTimeout() = with(Fixture()) {
        coordinator.start(); advance(8_000); live = true; health(); advance(12_250)
        assertEquals(ListenerRecoveryState.TIMED_OUT, coordinator.state.value)
        health()
        assertEquals(ListenerRecoveryState.TIMED_OUT, coordinator.state.value)
        connection = "new"; health()
        assertEquals(ListenerRecoveryState.RECOVERED, coordinator.state.value)
    }

    @Test fun resetBindRejectionEndsAttemptWithoutAnotherReset() = with(Fixture()) {
        coordinator.start(); advance(8_000); rejectBind = true; advance(50_000)
        assertEquals(ListenerRecoveryState.PLATFORM_REJECTED, coordinator.state.value)
        assertEquals(1, unbinds)
        assertEquals(2, binds)
        assertFalse(timers.any { !it.canceled })
    }
}
