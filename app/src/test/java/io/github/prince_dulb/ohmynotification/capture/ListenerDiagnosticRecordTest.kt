package io.github.prince_dulb.ohmynotification.capture

import org.junit.Assert.*
import org.junit.Test

class ListenerDiagnosticRecordTest {
    @Test fun basicModeRetainsCriticalEventsButSkipsPageChurn() {
        assertTrue(ListenerDiagnosticEvent.UI_START.detailedOnly)
        assertTrue(ListenerDiagnosticEvent.UI_STOP.detailedOnly)
        listOf(ListenerDiagnosticEvent.PROCESS_START, ListenerDiagnosticEvent.SERVICE_CONNECT,
            ListenerDiagnosticEvent.SERVICE_DISCONNECT, ListenerDiagnosticEvent.HEALTH,
            ListenerDiagnosticEvent.STORAGE_FAILED, ListenerDiagnosticEvent.RECOVERY_STAGE,
            ListenerDiagnosticEvent.EXPORT).forEach { assertFalse(it.detailedOnly) }
    }

    @Test fun repeatedFaultsCoalesceWithoutHidingStateChanges() {
        val first = ListenerDiagnosticRecord(ListenerDiagnosticEvent.STORAGE_FAILED, 1, 1, 42, 1, true, false, true, true)
        val later = first.copy(wallMillis = 5, elapsedMillis = 5)
        assertTrue(later.isRedundantAfter(first))
        assertFalse(later.copy(processing = true).isRedundantAfter(first))
        assertFalse(later.copy(access = 0).isRedundantAfter(first))
        assertFalse(later.copy(pid = 43).isRedundantAfter(first))
        assertFalse(later.copy(event = ListenerDiagnosticEvent.PROCESS_START).isRedundantAfter(first))
        assertFalse(later.isRedundantAfter(null))
    }

    private fun record(time: Long) = ListenerDiagnosticRecord(
        ListenerDiagnosticEvent.UI_START, time, time, 42, 1, true, false, true, false,
    ).line()

    @Test fun journalRetainsOnlyBoundedNewestFixedFormatEvents() {
        val retained = ListenerDiagnosticRecord.retainedLines((0..999).joinToString("\n") { record(it.toLong()) })
        assertEquals(256, retained.size)
        assertEquals(record(744), retained.first())
        assertEquals(record(999), retained.last())
        assertTrue(retained.joinToString("\n").toByteArray().size < ListenerDiagnosticRecord.MAX_BYTES)
    }

    @Test fun malformedOrFreeFormDataCannotBeReExported() {
        val input = listOf(record(1), "PRIVATE_DATA 1 2 3 4 5 6 7 8 9",
            "UI_START 1 2 3 4 5 6 7 8 text", record(2) + " extra", "partial").joinToString("\n")
        assertEquals(listOf(record(1)), ListenerDiagnosticRecord.retainedLines(input))
    }
}
