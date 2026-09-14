package io.github.prince_dulb.ohmynotification.core.health

enum class StatusPresentationState {
    LISTENING,
    PROCESSING_INTERRUPTED,
    KNOWN_CAPTURE_GAP,
    WAITING_FOR_CONNECTION,
    LISTENER_INTERRUPTED,
    ACCESS_REQUIRED,
}

object StatusPresentationDeriver {
    fun derive(
        listenerAccessGranted: Boolean,
        connectionObserved: Boolean,
        listenerConnected: Boolean,
        processingOperational: Boolean,
        knownCaptureGap: Boolean = false,
    ): StatusPresentationState = when {
        !listenerAccessGranted -> StatusPresentationState.ACCESS_REQUIRED
        listenerConnected && !processingOperational -> StatusPresentationState.PROCESSING_INTERRUPTED
        listenerConnected && knownCaptureGap -> StatusPresentationState.KNOWN_CAPTURE_GAP
        listenerConnected -> StatusPresentationState.LISTENING
        connectionObserved -> StatusPresentationState.LISTENER_INTERRUPTED
        else -> StatusPresentationState.WAITING_FOR_CONNECTION
    }
}
