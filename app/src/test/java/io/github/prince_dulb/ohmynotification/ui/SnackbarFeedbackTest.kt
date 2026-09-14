package io.github.prince_dulb.ohmynotification.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SnackbarFeedbackTest {
    @Test fun cancelingTheOwningUiRemovesItsFeedbackAndDoesNotPoisonTheNextRequest() = runBlocking {
        val host = SnackbarFeedback()
        val canceled = async(start = CoroutineStart.UNDISPATCHED) {
            host.showLatestSnackbar("synthetic old", "undo")
        }
        yield()
        canceled.cancelAndJoin()
        assertTrue(canceled.isCancelled)
        assertNull(host.hostState.currentSnackbarData)
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            host.showLatestSnackbar("synthetic new")
        }
        try {
            yield()
            assertEquals("synthetic new", host.hostState.currentSnackbarData?.visuals?.message)
            host.hostState.currentSnackbarData!!.dismiss()
            assertEquals(SnackbarResult.Dismissed, next.await())
        } finally { next.cancelAndJoin() }
    }

    @Test fun aBurstKeepsOnlyTheMostRecentFeedback() = runBlocking {
        withTimeout(5_000) {
            val host = SnackbarFeedback()
            val messages = (1..4).map { index ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    host.showLatestSnackbar("synthetic $index", "undo")
                }
            }
            try {
                repeat(10) { yield() }
                assertEquals("synthetic 4", host.hostState.currentSnackbarData?.visuals?.message)
                messages.dropLast(1).forEach { assertEquals(SnackbarResult.Dismissed, it.await()) }
                host.hostState.currentSnackbarData!!.performAction()
                assertEquals(SnackbarResult.ActionPerformed, messages.last().await())
            } finally { messages.forEach { it.cancelAndJoin() } }
        }
    }

    @Test fun newerFeedbackReplacesUndoWithoutPerformingTheOldAction() = runBlocking {
        withTimeout(5_000) {
            val host = SnackbarFeedback()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                host.showLatestSnackbar("synthetic archive", "undo")
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                host.showLatestSnackbar("synthetic delete")
            }
            try {
                repeat(10) { yield() }
                assertEquals("synthetic delete", host.hostState.currentSnackbarData?.visuals?.message)
                assertEquals(SnackbarResult.Dismissed, first.await())
                host.hostState.currentSnackbarData!!.dismiss()
                assertEquals(SnackbarResult.Dismissed, second.await())
            } finally {
                first.cancelAndJoin()
                second.cancelAndJoin()
            }
        }
    }

    @Test fun undoIsBoundedAndOnlyAnActualActionClickPerformsIt() = runBlocking {
        val host = SnackbarFeedback()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            host.showLatestSnackbar("synthetic archive", "undo")
        }
        try {
            yield()
            assertEquals(SnackbarDuration.Long, host.hostState.currentSnackbarData?.visuals?.duration)
            host.hostState.currentSnackbarData!!.performAction()
            assertEquals(SnackbarResult.ActionPerformed, result.await())
        } finally { result.cancelAndJoin() }
    }
}
