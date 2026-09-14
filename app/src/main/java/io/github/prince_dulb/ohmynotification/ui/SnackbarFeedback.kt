package io.github.prince_dulb.ohmynotification.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** UI-thread confined. Cancel obsolete requests, including ones still queued by Material. */
internal class SnackbarFeedback {
    val hostState = SnackbarHostState()
    private var activeRequest: Deferred<SnackbarResult>? = null

    suspend fun showLatestSnackbar(message: String, actionLabel: String? = null): SnackbarResult = coroutineScope {
        val request = async(start = CoroutineStart.LAZY) {
            hostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
        }
        activeRequest?.cancel()
        activeRequest = request
        try {
            request.await()
        } catch (_: CancellationException) {
            // Replacement dismisses the old feedback; cancellation of the UI still propagates.
            currentCoroutineContext().ensureActive()
            SnackbarResult.Dismissed
        } finally {
            if (activeRequest === request) activeRequest = null
        }
    }
}
