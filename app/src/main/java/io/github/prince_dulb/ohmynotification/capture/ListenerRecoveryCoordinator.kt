package io.github.prince_dulb.ohmynotification.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ListenerRecoveryState {
    IDLE, WAITING, RESETTING, WAITING_AFTER_RESET,
    RECOVERED, TIMED_OUT, ACCESS_REQUIRED, PLATFORM_REJECTED, PROCESSING_FAILED;

    val running: Boolean get() = this == WAITING || this == RESETTING || this == WAITING_AFTER_RESET
}

internal interface ListenerRecoveryPort {
    fun accessGranted(): Boolean
    fun connected(): Boolean
    fun processingOperational(): Boolean
    fun connectionId(): String?
    fun requestBind()
    fun requestUnbind()
}

/** A process-owned, event-driven attempt. The scheduler holds at most one finite deadline. */
internal class ListenerRecoveryCoordinator(
    private val port: ListenerRecoveryPort,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val normalWaitMillis: Long = 8_000,
    private val resetPauseMillis: Long = 250,
    private val resetWaitMillis: Long = 12_000,
    private val onTransition: (ListenerRecoveryState) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(ListenerRecoveryState.IDLE)
    val state = mutableState.asStateFlow()
    private var cancelDeadline: (() -> Unit)? = null
    private var generation = 0L
    private var resetBaselineConnection: String? = null
    private var requiresFreshConnection = false

    @Synchronized
    fun start() {
        if (state.value.running) return
        guarded {
            if (!port.accessGranted()) finish(ListenerRecoveryState.ACCESS_REQUIRED)
            else if (healthy()) finish(ListenerRecoveryState.RECOVERED)
            else {
                transition(ListenerRecoveryState.WAITING)
                deadline(normalWaitMillis, ::normalTimeout)
                if (!port.connected()) port.requestBind()
            }
        }
    }

    @Synchronized
    fun onHealthChanged() {
        if (state.value == ListenerRecoveryState.IDLE) return
        guarded {
            if (!port.accessGranted()) finish(ListenerRecoveryState.ACCESS_REQUIRED)
            // After unbinding we must issue the matching bind, even if an old callback arrives.
            else if (state.value != ListenerRecoveryState.RESETTING && healthy()) {
                finish(ListenerRecoveryState.RECOVERED)
            } else if (state.value == ListenerRecoveryState.RECOVERED) {
                finish(ListenerRecoveryState.IDLE)
            }
        }
    }

    private fun normalTimeout() {
        if (resolveTerminal()) return
        transition(ListenerRecoveryState.RESETTING)
        deadline(resetPauseMillis) {
            if (!port.accessGranted()) finish(ListenerRecoveryState.ACCESS_REQUIRED)
            else {
                resetBaselineConnection = port.connectionId()
                requiresFreshConnection = true
                transition(ListenerRecoveryState.WAITING_AFTER_RESET)
                deadline(resetWaitMillis) {
                    if (!resolveTerminal()) finish(ListenerRecoveryState.TIMED_OUT)
                }
                port.requestBind()
                onHealthChanged()
            }
        }
        port.requestUnbind()
    }

    private fun resolveTerminal(): Boolean {
        val result = when {
            !port.accessGranted() -> ListenerRecoveryState.ACCESS_REQUIRED
            healthy() -> ListenerRecoveryState.RECOVERED
            freshConnection() -> ListenerRecoveryState.PROCESSING_FAILED
            else -> return false
        }
        finish(result)
        return true
    }

    private fun freshConnection() = port.connected() &&
        (!requiresFreshConnection ||
            port.connectionId() != resetBaselineConnection)

    private fun healthy() = freshConnection() && port.processingOperational()

    private fun deadline(delayMillis: Long, action: () -> Unit) {
        cancelDeadline?.invoke()
        val token = ++generation
        cancelDeadline = schedule(delayMillis) {
            synchronized(this) {
                if (token == generation) guarded(action)
            }
        }
    }

    private fun finish(result: ListenerRecoveryState) {
        generation++
        cancelDeadline?.invoke()
        cancelDeadline = null
        transition(result)
    }

    private fun transition(result: ListenerRecoveryState) {
        if (mutableState.value == result) return
        mutableState.value = result
        onTransition(result)
    }

    private fun guarded(action: () -> Unit) {
        try { action() }
        catch (_: RuntimeException) { finish(ListenerRecoveryState.PLATFORM_REJECTED) }
    }
}
